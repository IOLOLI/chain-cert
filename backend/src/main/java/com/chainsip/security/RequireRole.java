package com.chainsip.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 角色门禁：标注在 Controller 类或方法上，由 {@link JwtAuthInterceptor} 校验。
 *
 * <p>注意：这只是"链下第一道闸门"，用来给出友好的错误提示与界面隔离；
 * 最终裁决权在合约（合约会按 msg.sender 再判一次，链下角色与链上不一致时交易照样回滚）。
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {

    /** 允许的角色，如 {"ADMIN"} 或 {"ADMIN", "REGULATOR"} */
    String[] value();
}
