package com.chainsip;

import com.chainsip.common.BizException;
import com.chainsip.common.ErrorCode;
import com.chainsip.service.IdempotencyService;
import com.chainsip.service.RedisCacheService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdempotencyServiceTest {

    private final RedisCacheService redis = mock(RedisCacheService.class);
    private final IdempotencyService service = new IdempotencyService(redis, new ObjectMapper());

    @Test
    @DisplayName("同一份请求正在处理时返回 409，且不再执行业务")
    void duplicateRequestIsRejected() {
        when(redis.tryLock(anyString(), anyString(), any(Duration.class)))
                .thenReturn(RedisCacheService.LockResult.BUSY);

        BizException error = assertThrows(BizException.class,
                () -> service.run("payment", "account-1", "employee-1", () -> "unexpected"));

        assertEquals(ErrorCode.CONFLICT, error.getErrorCode());
    }

    @Test
    @DisplayName("Redis 不可用时 fail-open，业务照常执行")
    void redisUnavailableFallsBackToBusiness() {
        when(redis.tryLock(anyString(), anyString(), any(Duration.class)))
                .thenReturn(RedisCacheService.LockResult.UNAVAILABLE);

        String result = service.run("payment", "account-1", "employee-1", () -> "ok");

        assertEquals("ok", result);
        verify(redis, never()).releaseLock(anyString(), anyString());
    }

    @Test
    @DisplayName("业务失败时释放幂等键，允许用户修正后立即重试")
    void businessFailureReleasesKey() {
        when(redis.tryLock(anyString(), anyString(), any(Duration.class)))
                .thenReturn(RedisCacheService.LockResult.ACQUIRED);

        assertThrows(IllegalStateException.class,
                () -> service.run("payment", "account-1", "employee-1", () -> {
                    throw new IllegalStateException("chain failed");
                }));

        verify(redis).releaseLock(anyString(), anyString());
    }

    @Test
    @DisplayName("业务成功时保留幂等键，覆盖客户端重试窗口")
    void businessSuccessKeepsKey() {
        when(redis.tryLock(anyString(), anyString(), any(Duration.class)))
                .thenReturn(RedisCacheService.LockResult.ACQUIRED);

        assertEquals("ok", service.run("payment", "account-1", "employee-1", () -> "ok"));
        verify(redis, never()).releaseLock(anyString(), anyString());
    }
}
