package com.chainsip.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 跨域配置（chainsip.cors.*）。
 *
 * <p>默认 {@code ["*"]} 仅供本地前端联调（Vite 5173 端口）；生产环境必须通过
 * {@code CHAINSIP_CORS_ALLOWED_ORIGIN_PATTERNS} 收紧为具体域名，禁止携带凭据的 {@code *}。
 */
@Data
@Component
@ConfigurationProperties(prefix = "chainsip.cors")
public class CorsProperties {

    private List<String> allowedOriginPatterns = new ArrayList<>(Collections.singletonList("*"));
}
