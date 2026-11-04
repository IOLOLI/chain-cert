package com.chainsip.common;

import lombok.Data;

/**
 * 统一响应体。所有 REST 接口都返回这一层包装，前端只需判断 {@code code == 0}。
 */
@Data
public class ApiResult<T> {

    private int code;
    private String message;
    private T data;
    private long timestamp = System.currentTimeMillis();

    public static <T> ApiResult<T> ok() {
        return ok(null);
    }

    public static <T> ApiResult<T> ok(T data) {
        ApiResult<T> r = new ApiResult<>();
        r.code = ErrorCode.OK.getCode();
        r.message = ErrorCode.OK.getMessage();
        r.data = data;
        return r;
    }

    public static <T> ApiResult<T> fail(ErrorCode errorCode) {
        return fail(errorCode.getCode(), errorCode.getMessage());
    }

    public static <T> ApiResult<T> fail(ErrorCode errorCode, String message) {
        return fail(errorCode.getCode(), message);
    }

    public static <T> ApiResult<T> fail(int code, String message) {
        ApiResult<T> r = new ApiResult<>();
        r.code = code;
        r.message = message;
        return r;
    }
}
