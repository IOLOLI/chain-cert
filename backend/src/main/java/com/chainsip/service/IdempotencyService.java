package com.chainsip.service;

import com.chainsip.common.BizException;
import com.chainsip.common.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 写接口幂等：同一账号在短窗口内重复提交同一份业务数据时，只放行第一次。
 *
 * <p>用途是挡住"页面双击 / 网络重试 / 前端连点"造成的重复上链交易。
 * 链上合约虽然也有状态校验，但重复交易会白白消耗 gas、产生无意义的失败日志。
 *
 * <p>语义选择：
 * <ul>
 *   <li>Redis 可用且 key 已存在 → 直接返回 409，提示"请勿重复提交"；</li>
 *   <li>Redis 不可用 → fail-open，照常执行业务，不让幂等组件阻断主流程；</li>
 *   <li>业务执行抛异常 → 释放 key，允许用户修正后立即重试；</li>
 *   <li>业务执行成功 → 保留 key 到 TTL 结束，窗口内的重复请求被挡住。</li>
 * </ul>
 */
@Service
public class IdempotencyService {

    private static final Duration DEFAULT_TTL = Duration.ofSeconds(120);

    private final RedisCacheService redisCacheService;
    private final ObjectMapper objectMapper;

    public IdempotencyService(RedisCacheService redisCacheService, ObjectMapper objectMapper) {
        this.redisCacheService = redisCacheService;
        this.objectMapper = objectMapper;
    }

    public <T> T run(String namespace, String identity, Object payload, Supplier<T> action) {
        String key = key(namespace, identity, payload);
        String token = UUID.randomUUID().toString();
        RedisCacheService.LockResult result = redisCacheService.tryLock(key, token, DEFAULT_TTL);
        if (result == RedisCacheService.LockResult.BUSY) {
            throw new BizException(ErrorCode.CONFLICT, "请求已提交或正在处理，请勿重复提交");
        }
        if (result == RedisCacheService.LockResult.UNAVAILABLE) {
            return action.get();
        }
        try {
            T value = action.get();
            // 成功时故意不释放：让 key 留到 TTL 结束，覆盖"响应丢失后客户端重试"的窗口。
            return value;
        } catch (RuntimeException e) {
            redisCacheService.releaseLock(key, token);
            throw e;
        }
    }

    private String key(String namespace, String identity, Object payload) {
        String safeNamespace = namespace == null ? "default" : namespace;
        String safeIdentity = identity == null ? "anonymous" : identity;
        String raw = safeIdentity + "|" + json(payload);
        return "idem:" + safeNamespace + ":" + sha256(raw);
    }

    private String json(Object payload) {
        if (payload == null) {
            return "";
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return String.valueOf(payload);
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                out.append(String.format("%02x", b & 0xff));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
