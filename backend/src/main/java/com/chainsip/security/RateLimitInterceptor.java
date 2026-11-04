package com.chainsip.security;

import com.chainsip.common.BizException;
import com.chainsip.common.ErrorCode;
import com.chainsip.service.RateLimitService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.time.Duration;

/**
 * API 限流拦截器：在 JWT 解析之后执行，优先按账号限流，未登录时按 IP 限流。
 *
 * <p>普通接口给较宽的额度，辖区扫描、监管大屏、链上索引这类"一次请求打很多次链"的接口
 * 单独用更小的额度，避免单个用户把链节点读穿。
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final RateLimitService rateLimitService;

    @Value("${chainsip.rate-limit.enabled:true}")
    private boolean enabled;

    @Value("${chainsip.rate-limit.api-per-minute:300}")
    private int apiPerMinute;

    @Value("${chainsip.rate-limit.expensive-per-minute:60}")
    private int expensivePerMinute;

    public RateLimitInterceptor(RateLimitService rateLimitService) {
        this.rateLimitService = rateLimitService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())
                || !enabled || !(handler instanceof HandlerMethod)) {
            return true;
        }
        String identity = identity(request);
        boolean expensive = isExpensive(request.getRequestURI());
        int limit = expensive ? expensivePerMinute : apiPerMinute;
        String namespace = expensive ? "expensive" : "api";
        if (!rateLimitService.allow(namespace, identity, limit, WINDOW)) {
            throw new BizException(ErrorCode.RATE_LIMITED,
                    "请求过于频繁，请稍后再试（" + (expensive ? "重查询" : "普通接口")
                            + "每分钟最多 " + limit + " 次）");
        }
        return true;
    }

    private String identity(HttpServletRequest request) {
        AuthUser user = AuthContext.get();
        if (user != null && user.getAccountId() != null) {
            return "user:" + user.getAccountId();
        }
        return "ip:" + request.getRemoteAddr();
    }

    private boolean isExpensive(String uri) {
        if (uri == null) {
            return false;
        }
        return uri.startsWith("/api/bureau/city/")
                || uri.startsWith("/api/bureau/pension/")
                || uri.startsWith("/api/regulator/")
                || uri.startsWith("/api/index/")
                || uri.startsWith("/api/admin/cities")
                || uri.startsWith("/api/admin/system-stats");
    }
}
