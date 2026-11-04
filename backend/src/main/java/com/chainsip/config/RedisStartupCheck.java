package com.chainsip.config;

import com.chainsip.service.RedisCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 启动后检查一次 Redis。Redis 不是关键依赖，失败只写 warn，不阻断启动。
 */
@Component
public class RedisStartupCheck {

    private static final Logger log = LoggerFactory.getLogger(RedisStartupCheck.class);

    private final RedisCacheService redisCacheService;
    private final RedisProperties redisProperties;

    public RedisStartupCheck(RedisCacheService redisCacheService, RedisProperties redisProperties) {
        this.redisCacheService = redisCacheService;
        this.redisProperties = redisProperties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void check() {
        String endpoint = redisProperties.getHost() + ":" + redisProperties.getPort()
                + "（db=" + redisProperties.getDatabase() + "）";
        if (redisCacheService.probe()) {
            log.info("Redis 连接自检通过：{}", endpoint);
        } else {
            log.warn("Redis 连接自检失败：{}。缓存将自动跳过，限流/幂等自动放行，不影响核心业务；"
                    + "请确认 Redis 已启动（本机 Docker 示例：docker start chainsip-redis）", endpoint);
        }
    }
}
