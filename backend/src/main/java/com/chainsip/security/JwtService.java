package com.chainsip.security;

import com.chainsip.common.BizException;
import com.chainsip.common.ErrorCode;
import com.chainsip.config.JwtProperties;
import com.chainsip.entity.Account;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;

/**
 * JWT 签发与解析。登录态完全在链下，链上不需要"会话"这个概念。
 *
 * <p>用的是 jjwt 0.11.x：它自带 Base64 实现，不依赖 JDK 11+ 已移除的 JAXB，
 * 因此在 JDK 8 / 11 / 17 / 21 上行为一致（旧版 0.9.x 在 JDK 9+ 上会在签发 token 时
 * 抛 {@code NoClassDefFoundError: javax/xml/bind/DatatypeConverter}）。
 */
@Service
public class JwtService {

    /** 与 JwtProperties 里的仓库默认值一致：出现即说明部署者没改过密钥。 */
    private static final String DEV_SECRET_MARK = "chainsip-dev-jwt-secret";

    private final JwtProperties properties;
    private final SecretKey signingKey;

    public JwtService(JwtProperties properties, Environment environment) {
        this.properties = properties;
        byte[] secret = properties.getSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            // HS256 要求密钥至少 256 位；这里直接拒绝弱密钥，避免上线后才发现
            throw new IllegalStateException("chainsip.jwt.secret 至少需要 32 字节（当前 "
                    + secret.length + " 字节），请更换为更长的随机串");
        }
        // 默认密钥一旦公开，任何人都能伪造 ADMIN token（链下角色门禁被整体绕过）；
        // 非 dev 环境直接拒绝启动，逼着部署者用 CHAINSIP_JWT_SECRET 换成自己的密钥。
        if (!isDev(environment) && properties.getSecret().contains(DEV_SECRET_MARK)) {
            throw new IllegalStateException("非 dev 环境禁止使用仓库默认 JWT 密钥，"
                    + "请通过环境变量 CHAINSIP_JWT_SECRET 覆盖后再启动");
        }
        this.signingKey = Keys.hmacShaKeyFor(secret);
    }

    private boolean isDev(Environment environment) {
        return environment != null && Arrays.asList(environment.getActiveProfiles()).contains("dev");
    }

    public String issue(Account account) {
        Date now = new Date();
        Date expire = new Date(now.getTime() + properties.getExpireMinutes() * 60_000L);
        return Jwts.builder()
                .setSubject(account.getUsername())
                .claim("uid", account.getId())
                .claim("role", account.getRole())
                .claim("name", account.getDisplayName())
                .claim("inst", account.getInstitutionCode())
                .setIssuedAt(now)
                .setExpiration(expire)
                .signWith(signingKey)
                .compact();
    }

    public AuthUser parse(String token) {
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(signingKey)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();

            AuthUser user = new AuthUser();
            Object uid = claims.get("uid");
            user.setAccountId(uid instanceof Number ? ((Number) uid).longValue() : null);
            user.setUsername(claims.getSubject());
            user.setRole((String) claims.get("role"));
            user.setDisplayName((String) claims.get("name"));
            user.setInstitutionCode((String) claims.get("inst"));
            return user;
        } catch (ExpiredJwtException e) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "登录状态已过期，请重新登录");
        } catch (Exception e) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "登录凭证无效");
        }
    }
}
