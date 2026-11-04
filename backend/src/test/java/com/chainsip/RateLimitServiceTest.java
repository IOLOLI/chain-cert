package com.chainsip;

import com.chainsip.service.RateLimitService;
import com.chainsip.service.RedisCacheService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RateLimitServiceTest {

    private final RedisCacheService redis = mock(RedisCacheService.class);
    private final RateLimitService service = new RateLimitService(redis);

    @Test
    @DisplayName("达到上限前放行，超过上限拒绝")
    void enforcesFixedWindowLimit() {
        when(redis.increment(anyString(), any(Duration.class))).thenReturn(3L, 4L);

        assertTrue(service.allow("api", "user:1", 3, Duration.ofMinutes(1)));
        assertFalse(service.allow("api", "user:1", 3, Duration.ofMinutes(1)));
    }

    @Test
    @DisplayName("Redis 不可用时 fail-open")
    void redisUnavailableFallsOpen() {
        when(redis.increment(anyString(), any(Duration.class))).thenReturn(null);

        assertTrue(service.allow("api", "user:1", 1, Duration.ofMinutes(1)));
    }

    @Test
    @DisplayName("limit <= 0 表示不限制")
    void nonPositiveLimitDisablesLimiting() {
        assertTrue(service.allow("api", "user:1", 0, Duration.ofMinutes(1)));
    }
}
