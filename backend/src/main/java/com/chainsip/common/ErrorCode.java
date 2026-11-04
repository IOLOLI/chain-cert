package com.chainsip.common;

import lombok.Getter;

/**
 * 错误码。HTTP 状态码与业务码分开：HTTP 用 200 返回业务失败也可以，但这里对认证/权限/链异常
 * 同时设置对应的 HTTP 状态，方便前端与网关统一处理。
 */
@Getter
public enum ErrorCode {

    OK(0, "成功"),
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未登录或登录状态已失效"),
    FORBIDDEN(403, "当前账号没有该操作权限"),
    NOT_FOUND(404, "资源不存在"),
    CONFLICT(409, "状态冲突"),
    RATE_LIMITED(429, "请求过于频繁"),

    ACCOUNT_EXISTS(1001, "用户名已存在"),
    ACCOUNT_NOT_FOUND(1002, "账号不存在"),
    PASSWORD_ERROR(1003, "用户名或密码错误"),
    ACCOUNT_DISABLED(1004, "账号已被禁用"),
    // 保留：手动地址绑定已下线，这三个错误码不再抛出，仅为兼容历史日志/前端提示保留
    WALLET_ALREADY_BOUND(1005, "该地址已被其他账号绑定"),
    WALLET_SIGNATURE_INVALID(1006, "钱包签名验证失败"),
    NONCE_EXPIRED(1007, "签名随机数已失效，请重新获取"),
    INSTITUTION_KEY_NOT_FOUND(1008, "当前账号未配置可用的机构链账户"),

    CHAIN_NOT_READY(2001, "链未就绪：请检查 SDK 配置、证书与合约地址"),
    CHAIN_CALL_ERROR(2002, "链上调用失败"),
    CHAIN_RECEIPT_FAILED(2003, "交易已上链但执行失败"),
    CONTRACT_NOT_DEPLOYED(2004, "合约地址未配置"),

    BIZ_ERROR(3001, "业务处理失败"),
    INTERNAL_ERROR(500, "服务器内部错误");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
