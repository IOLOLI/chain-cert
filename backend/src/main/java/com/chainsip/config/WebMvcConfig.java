package com.chainsip.config;

import com.chainsip.security.JwtAuthInterceptor;
import com.chainsip.security.RateLimitInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;
import java.util.List;

/**
 * Web 层配置：跨域（前端本地调试）与登录拦截器注册。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebMvcConfig.class);

    private final JwtAuthInterceptor jwtAuthInterceptor;
    private final RateLimitInterceptor rateLimitInterceptor;
    private final CorsProperties corsProperties;
    private final Environment environment;

    public WebMvcConfig(JwtAuthInterceptor jwtAuthInterceptor, RateLimitInterceptor rateLimitInterceptor,
                        CorsProperties corsProperties,
                        Environment environment) {
        this.jwtAuthInterceptor = jwtAuthInterceptor;
        this.rateLimitInterceptor = rateLimitInterceptor;
        this.corsProperties = corsProperties;
        this.environment = environment;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtAuthInterceptor)
                .addPathPatterns("/api/**")
                // 登录不需要登录态；联调辅助接口仅在 chainsip.crypto.allow-demo-sign=true 时可用
                .excludePathPatterns(
                        "/api/auth/login",
                        "/api/auth/demo/**",
                        "/api/public/**");

        // 放在登录拦截器之后：能拿到 AuthContext 时按账号限流，未登录（如登录接口）按 IP 限流。
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/public/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        List<String> patterns = corsProperties.getAllowedOriginPatterns();
        if (patterns == null || patterns.isEmpty()) {
            patterns = Arrays.asList("*");
        }
        // 生产环境禁止"任意来源 + 允许凭据"；非 dev 使用 * 时给出醒目告警（不阻断，因本项目鉴权走 Authorization 头）。
        if (!isDev() && patterns.contains("*")) {
            log.warn("CORS 允许任意来源（chainsip.cors.allowed-origin-patterns 含 *），生产环境应收紧为具体域名");
        }
        registry.addMapping("/**")
                .allowedOriginPatterns(patterns.toArray(new String[0]))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    private boolean isDev() {
        return environment != null && Arrays.asList(environment.getActiveProfiles()).contains("dev");
    }
}
