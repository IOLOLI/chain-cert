package com.chainsip.security;

import lombok.Data;

/**
 * 当前登录用户（由 JWT 解析得到）。
 *
 * <p>{@code role} 与 {@code institutionCode} 是"链下视角"：决定界面与挑哪把机构私钥发交易。
 * 链上是否真有该角色，由合约的 {@code roleOf(address)} 决定。
 */
@Data
public class AuthUser {

    private Long accountId;

    private String username;

    private String role;

    private String displayName;

    /** 机构链账户编码，用于挑选发交易用的私钥 */
    private String institutionCode;

    public boolean hasRole(String... roles) {
        if (role == null) {
            return false;
        }
        for (String r : roles) {
            if (r.equalsIgnoreCase(role)) {
                return true;
            }
        }
        return false;
    }
}
