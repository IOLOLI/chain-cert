package com.chainsip.chain.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 发交易的返回结果。注意区分两个"失败"：
 * <ul>
 *   <li>{@code success=false} 且 {@code transactionHash=null}：交易根本没上链（编码失败、合约回滚等）；</li>
 *   <li>{@code success=false} 但 {@code transactionHash} 有值：交易已上链、但合约执行回滚（状态码非 0），
 *       回滚原因见 {@code message}。</li>
 * </ul>
 */
@Data
@AllArgsConstructor
public class SendResult {

    private boolean success;

    private String transactionHash;

    private Long blockNumber;

    /** 链上回执状态码，0 表示成功 */
    private Integer status;

    private List<Object> returnValues;

    /** SDK 解析出的事件 JSON（原始字符串）：事件名 → 非索引参数的位置数组 */
    private String eventsJson;

    /** 回执日志 JSON（原始字符串）：用于解码索引参数（topics），补充 employee / sponsor 地址 */
    private String logsJson;

    /** 回执信息 / 回滚原因 */
    private String message;
}
