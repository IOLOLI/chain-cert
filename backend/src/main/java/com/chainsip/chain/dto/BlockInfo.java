package com.chainsip.chain.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 最新区块信息（大整数一律转字符串返回，避免前端 JavaScript 精度丢失）。
 */
@Data
@AllArgsConstructor
public class BlockInfo {

    private String blockNumber;

    private String blockHash;

    private String latestTransactionHash;

    private int transactionCount;

    private String blockTimestamp;

    private String groupId;

    private String nodeVersion;
}
