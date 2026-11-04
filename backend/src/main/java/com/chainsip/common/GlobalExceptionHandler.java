package com.chainsip.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理：把业务异常、参数校验失败、链上异常分别映射成明确的错误码，
 * 避免把 Java 堆栈直接暴露给前端。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResult<Void>> handleBiz(BizException e) {
        // 日志保留原始 message（含合约错误名），响应只给中文人话
        log.warn("业务异常: code={}, message={}", e.getErrorCode().getCode(), e.getMessage());
        return ResponseEntity.status(httpStatusOf(e.getErrorCode()))
                .body(ApiResult.fail(e.getErrorCode(), ContractErrorMessages.forClient(e.getErrorCode(), e.getMessage())));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ResponseEntity<ApiResult<Void>> handleValidation(BindException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + defaultMessage(f))
                .collect(Collectors.joining("; "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResult.fail(ErrorCode.BAD_REQUEST, detail));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResult<Void>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResult.fail(ErrorCode.BAD_REQUEST, e.getMessage()));
    }

    /**
     * 请求体不是合法 JSON（畸形 JSON / 字段类型不匹配等）。
     *
     * <p>不返回 500、也不泄漏 Jackson 异常类名与解析细节（这些属于技术实现，会暴露框架版本），
     * 只给一句中文提示；原始异常详情只进日志。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResult<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败：{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResult.fail(ErrorCode.BAD_REQUEST, "请求体格式错误"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResult<Void>> handleOthers(Exception e) {
        log.error("未预期的异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResult.fail(ErrorCode.INTERNAL_ERROR, "服务器内部错误"));
    }

    private String defaultMessage(FieldError f) {
        return f.getDefaultMessage() == null ? "参数不合法" : f.getDefaultMessage();
    }

    private HttpStatus httpStatusOf(ErrorCode code) {
        switch (code) {
            case UNAUTHORIZED:
            case PASSWORD_ERROR:
            case ACCOUNT_DISABLED:
            case NONCE_EXPIRED:
            case WALLET_SIGNATURE_INVALID:
                return HttpStatus.UNAUTHORIZED;
            case FORBIDDEN:
                return HttpStatus.FORBIDDEN;
            case NOT_FOUND:
            case ACCOUNT_NOT_FOUND:
                return HttpStatus.NOT_FOUND;
            case BAD_REQUEST:
            // 用户名重复按"请求不合法"返回 400（业务码仍是 1001），与账号开通接口的契约一致
            case ACCOUNT_EXISTS:
                return HttpStatus.BAD_REQUEST;
            case CONFLICT:
            case WALLET_ALREADY_BOUND:
                return HttpStatus.CONFLICT;
            case RATE_LIMITED:
                return HttpStatus.TOO_MANY_REQUESTS;
            case CHAIN_NOT_READY:
                return HttpStatus.SERVICE_UNAVAILABLE;
            case CONTRACT_NOT_DEPLOYED:
                return HttpStatus.SERVICE_UNAVAILABLE;
            default:
                return HttpStatus.OK;
        }
    }
}
