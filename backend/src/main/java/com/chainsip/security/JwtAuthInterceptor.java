package com.chainsip.security;

import com.chainsip.common.BizException;
import com.chainsip.common.ErrorCode;
import com.chainsip.config.JwtProperties;
import com.chainsip.entity.Account;
import com.chainsip.mapper.AccountMapper;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Arrays;

/**
 * 登录与角色拦截器：解析 JWT 放入 {@link AuthContext}，并按 {@link RequireRole} 做链下角色门禁。
 */
@Component
public class JwtAuthInterceptor implements HandlerInterceptor {

    private final JwtService jwtService;
    private final JwtProperties properties;
    private final AccountMapper accountMapper;

    public JwtAuthInterceptor(JwtService jwtService, JwtProperties properties, AccountMapper accountMapper) {
        this.jwtService = jwtService;
        this.properties = properties;
        this.accountMapper = accountMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }

        // 只对真正的 @RequestMapping 处理器做门禁。路径不存在时（例如某个接口已经被删除）
        // Spring 会把这个请求交给兜底的静态资源处理器，它同样落在 /api/** 上、也会走本拦截器；
        // 若在这里因为没有 Authorization 头就抛异常，异常发生在 HandlerMethod 之外，
        // @RestControllerAdvice 兜不住，结果变成 500 + Spring 默认错误页。
        // 放行这类"没有真实接口"的请求，让它自然落到 404，语义才对。
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }

        String header = request.getHeader(properties.getHeader());
        if (header == null || !header.startsWith(properties.getPrefix())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "缺少登录凭证：" + properties.getHeader());
        }

        AuthUser user = jwtService.parse(header.substring(properties.getPrefix().length()).trim());
        // 回查账号：账号被禁用 / 删除后旧 token 立即失效；角色变更也以数据库当前值为准，
        // 而不是信任 token 里的旧 role（否则改角色/禁用都拦不住已登录会话）。
        if (user.getAccountId() == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "登录凭证无效");
        }
        Account account = accountMapper.selectById(user.getAccountId());
        if (account == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "账号不存在或已删除");
        }
        if (!"ENABLED".equalsIgnoreCase(account.getStatus())) {
            throw new BizException(ErrorCode.ACCOUNT_DISABLED, "账号已被禁用");
        }
        user.setRole(account.getRole());
        user.setInstitutionCode(account.getInstitutionCode());
        AuthContext.set(user);

        HandlerMethod handlerMethod = (HandlerMethod) handler;
        RequireRole required = handlerMethod.getMethodAnnotation(RequireRole.class);
        if (required == null) {
            required = handlerMethod.getBeanType().getAnnotation(RequireRole.class);
        }
        if (required != null && !user.hasRole(required.value())) {
            throw new BizException(ErrorCode.FORBIDDEN,
                    "当前角色 " + user.getRole() + " 无权访问，需要 " + Arrays.toString(required.value()));
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        AuthContext.clear();
    }
}
