package com.chainsip.controller;

import com.chainsip.common.ApiResult;
import com.chainsip.common.BizException;
import com.chainsip.common.ErrorCode;
import com.chainsip.config.CryptoProperties;
import com.chainsip.crypto.WalletSignatureVerifier;
import com.chainsip.dto.AccountView;
import com.chainsip.dto.LoginRequest;
import com.chainsip.dto.LoginResponse;
import com.chainsip.dto.Requests;
import com.chainsip.entity.Account;
import com.chainsip.security.AuthContext;
import com.chainsip.security.AuthUser;
import com.chainsip.service.AuthService;
import com.chainsip.service.RateLimitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.time.Duration;

/**
 * 认证与账号信息。
 *
 * <p>这一层完全是链下的：账号、密码、会话都在这里；链上只认交易签名里的地址。
 *
 * <p><b>不提供自助注册</b>：账号一律由上级按层级创建
 * （ADMIN → BUREAU/SPONSOR/REGULATOR、BUREAU → SPONSOR、SPONSOR → EMPLOYEE，
 * 见 {@code AccountHierarchyService} 与 {@code AdminController#createAccount}）。
 *
 * <p><b>不提供手动地址绑定</b>：链上地址在创建账号时由后端自动生成并自动绑定
 * （见 {@code AuthService.bindPlatformAddress}），用户无需验签绑定，因此没有
 * 下发消息 / 提交签名 / 地址列表这三个接口；地址信息随 {@link #me()} 的
 * {@code chainAddress} / {@code primaryAddress} / {@code wallets} 一起返回。
 */
@Tag(name = "01 认证与账号", description = "登录 / 我的信息 / 改密码 / 改账号信息 / 联调辅助签名")
@RestController
@RequestMapping("/api/auth")
@Validated
public class AuthController {

    private final AuthService authService;
    private final WalletSignatureVerifier signatureVerifier;
    private final CryptoProperties cryptoProperties;
    private final RateLimitService rateLimitService;

    /**
     * 是否信任反向代理头（X-Forwarded-For）来记录客户端 IP。
     * 默认 false：直接用 {@code getRemoteAddr()}，避免任意客户端伪造审计 IP。
     * 仅在明确部署在可信反向代理之后时置 true。
     */
    @Value("${chainsip.audit.trust-proxy-headers:false}")
    private boolean trustProxyHeaders;

    @Value("${chainsip.rate-limit.login-per-minute:10}")
    private int loginPerMinute;

    public AuthController(AuthService authService, WalletSignatureVerifier signatureVerifier,
                          CryptoProperties cryptoProperties, RateLimitService rateLimitService) {
        this.authService = authService;
        this.signatureVerifier = signatureVerifier;
        this.cryptoProperties = cryptoProperties;
        this.rateLimitService = rateLimitService;
    }

    @Operation(summary = "登录，返回 JWT")
    @PostMapping("/login")
    public ApiResult<LoginResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        String ip = clientIp(httpRequest);
        String username = request.getUsername() == null ? "" : request.getUsername().trim().toLowerCase();
        if (!rateLimitService.allow("login", ip + ":" + username, loginPerMinute, Duration.ofMinutes(1))) {
            throw new BizException(ErrorCode.RATE_LIMITED,
                    "登录尝试过于频繁，请稍后再试（每分钟最多 " + loginPerMinute + " 次）");
        }
        return ApiResult.ok(authService.login(request, ip, httpRequest.getHeader("User-Agent")));
    }

    @Operation(summary = "我的信息（含已绑定地址与链上角色）")
    @GetMapping("/me")
    public ApiResult<AccountView> me() {
        AuthUser user = AuthContext.require();
        return ApiResult.ok(authService.toView(authService.requireAccount(user.getAccountId()), true));
    }

    @Operation(summary = "自助修改密码（登录用户改自己的密码；只校验原密码，不涉及管理员重置）")
    @PostMapping("/password")
    public ApiResult<Boolean> changePassword(@Valid @RequestBody Requests.ChangePassword request) {
        authService.changePassword(AuthContext.require().getAccountId(),
                request.getOldPassword(), request.getNewPassword());
        return ApiResult.ok(Boolean.TRUE);
    }

    @Operation(summary = "自助修改账号信息（用户名 / 显示名，需当前密码验证；账号取登录态，只能改自己）")
    @PostMapping("/profile")
    public ApiResult<Map<String, Object>> changeProfile(@Valid @RequestBody Requests.ChangeProfile request) {
        Account account = authService.changeProfile(AuthContext.require().getAccountId(),
                request.getNewUsername(), request.getNewDisplayName(), request.getPassword());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("username", account.getUsername());
        data.put("displayName", account.getDisplayName());
        return ApiResult.ok(data);
    }

    @Operation(summary = "联调辅助（无业务调用方）：用请求里自带的私钥对消息签名，仅 allow-demo-sign=true 时可用；生产可置 false")
    @PostMapping("/demo/sign")
    public ApiResult<Map<String, Object>> demoSign(@Valid @RequestBody Requests.DemoSign request) {
        if (!cryptoProperties.isAllowDemoSign()) {
            throw new BizException(ErrorCode.FORBIDDEN, "联调辅助接口已关闭（chainsip.crypto.allow-demo-sign=false）");
        }
        Map<String, Object> data = new HashMap<>();
        data.put("address", signatureVerifier.addressOf(request.getPrivateKey()));
        data.put("signature", signatureVerifier.signMessage(request.getPrivateKey(), request.getMessage()));
        return ApiResult.ok(data);
    }

    private String clientIp(HttpServletRequest request) {
        if (trustProxyHeaders) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isEmpty()) {
                return forwarded.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }
}
