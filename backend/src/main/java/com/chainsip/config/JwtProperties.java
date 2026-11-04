package com.chainsip.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 配置（chainsip.jwt.*）。认证完全在链下，链上只认交易签名。
 */
@Data
@Component
@ConfigurationProperties(prefix = "chainsip.jwt")
public class JwtProperties {

    private String secret = "chainsip-dev-jwt-secret-change-me-before-production-0123456789";

    private String header = "Authorization";

    private String prefix = "Bearer ";

    private long expireMinutes = 720L;
}
