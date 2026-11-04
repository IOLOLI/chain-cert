package com.chainsip.service;

import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 基于 Redis 的轻量限流：固定窗口计数（INCR + 首次设置 TTL）。
 *
 * <p>限流是"保护链节点"的第二道闸门：缓存挡住重复读之后，仍要防止单个账号或 IP
 * 用不同参数持续打昂贵接口。Redis 不可用时按 fail-open 放行，保证核心业务不被限流组件拖垮。
 */
@Service
public class RateLimitService {

    private final RedisCacheService redisCacheService;

    public RateLimitService(RedisCacheService redisCacheService) {
        this.redisCacheService = redisCacheService;
    }

    /**
     * @param namespace 限流维度（api / expensive / login）
     * @param identity  账号、IP 或"IP:用户名"
     * @param limit     窗口内允许的次数；<=0 表示不限制
     * @param window    窗口长度
     * @return true=放行；false=超限
     */
    public boolean allow(String namespace, String identity, int limit, Duration window) {
        if (limit <= 0) {
            return true;
        }
        String key = "rate:" + namespace + ":" + (identity == null ? "anonymous" : identity);
        Long current = redisCacheService.increment(key, window);
        return current == null || current <= limit;
    }
}
