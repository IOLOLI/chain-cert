package com.chainsip.chain.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 只读调用的返回结果（对应合约的 view 函数，不产生交易、不消耗链上资源）。
 */
@Data
@AllArgsConstructor
public class CallResult {

    /** 解码后的返回值列表（大整数为 BigInteger，结构体为 List，地址为 String） */
    private List<Object> values;

    /** 原始 JSON 字符串，便于前端原样展示结构体与大整数 */
    private String rawValues;

    private String message;

    /** 便捷取第一个返回值 */
    public Object first() {
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    public String firstAsString() {
        Object v = first();
        return v == null ? null : String.valueOf(v);
    }
}
