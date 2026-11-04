package com.chainsip.service;

import com.chainsip.common.BizException;
import com.chainsip.common.ErrorCode;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * 链上调用参数工具。
 *
 * <p>合约里地址是 address、金额与时间戳是 uintN、开关是 bool。SDK 的 ABI 编码对
 * uintN 要求 BigInteger，而 HTTP JSON 里的数字会被反序列化成 Integer/Long，
 * 因此这里统一做一次归一化，避免"类型对不上"的编码错误。
 */
public final class ChainParams {

    private ChainParams() {
    }

    public static List<Object> of(Object... args) {
        List<Object> list = new ArrayList<>();
        if (args == null) {
            return list;
        }
        for (Object arg : args) {
            list.add(normalize(arg));
        }
        return list;
    }

    public static List<Object> normalizeAll(List<Object> params) {
        List<Object> list = new ArrayList<>();
        if (params == null) {
            return list;
        }
        for (Object param : params) {
            list.add(normalize(param));
        }
        return list;
    }

    public static Object normalize(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigInteger || value instanceof String || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return BigInteger.valueOf(((Number) value).longValue());
        }
        if (value instanceof BigDecimal) {
            return ((BigDecimal) value).toBigInteger();
        }
        if (value instanceof Number) {
            return BigInteger.valueOf(((Number) value).longValue());
        }
        return value;
    }

    /** 金额 / 时间戳等无符号整数参数：允许字符串或数字，拒绝负数。 */
    public static BigInteger uint(Object value, String fieldName) {
        if (value == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, fieldName + " 不能为空");
        }
        try {
            BigInteger result = value instanceof BigInteger
                    ? (BigInteger) value
                    : new BigInteger(String.valueOf(value).trim());
            if (result.signum() < 0) {
                throw new BizException(ErrorCode.BAD_REQUEST, fieldName + " 不能为负数");
            }
            return result;
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, fieldName + " 不是合法的整数：" + value);
        }
    }

    public static String address(Object value, String fieldName) {
        String address = value == null ? null : String.valueOf(value).trim();
        if (address == null || !address.startsWith("0x") || address.length() != 42) {
            throw new BizException(ErrorCode.BAD_REQUEST, fieldName + " 应为 0x + 40 位十六进制地址");
        }
        return address;
    }

    public static String text(Object value, String fieldName, boolean required) {
        String text = value == null ? null : String.valueOf(value);
        if (required && (text == null || text.trim().isEmpty())) {
            throw new BizException(ErrorCode.BAD_REQUEST, fieldName + " 不能为空");
        }
        return text;
    }
}
