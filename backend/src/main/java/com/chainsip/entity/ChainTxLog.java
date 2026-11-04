package com.chainsip.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 上链操作留痕：每次 send 交易都记一笔，便于审计、排障与前端轮询回执。
 */
@Data
@TableName("cs_chain_tx_log")
public class ChainTxLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String institutionCode;

    private String senderAddress;

    private String contractName;

    private String functionName;

    private String paramsJson;

    private String txHash;

    private Long blockNumber;

    /** 链上回执状态码 */
    private Integer status;

    private Boolean success;

    private String message;

    private LocalDateTime createdAt;
}
