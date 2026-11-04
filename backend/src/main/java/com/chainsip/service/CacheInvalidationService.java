package com.chainsip.service;

import org.springframework.stereotype.Service;

/**
 * 链上写操作后的缓存失效。
 *
 * <p>缓存 TTL 是为了兜底；写操作成功后主动删除相关前缀，前台刷新即可看到新余额/新状态。
 * 写操作频率远低于查询，因此这里按模块前缀清理，换取更简单的正确性。
 */
@Service
public class CacheInvalidationService {

    private final RedisCacheService cache;

    public CacheInvalidationService(RedisCacheService cache) {
        this.cache = cache;
    }

    /** 一次链上写操作可能同时影响辖区总览、监管大屏、事件索引和转移列表。 */
    public void afterChainWrite() {
        cache.deleteByPrefix("bureau:");
        cache.deleteByPrefix("regulator:");
        cache.deleteByPrefix("index:");
        cache.deleteByPrefix("transfer:");
        cache.deleteByPrefix("chain:insurance:");
        cache.deleteByPrefix("chain:sponsor:");
        cache.deleteByPrefix("chain:persons:");
        cache.deleteByPrefix("chain:bureau-cities:");
    }
}
