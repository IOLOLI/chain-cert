package com.chainsip.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 链上事件索引（链下）。
 *
 * <p>合约刻意没有提供"遍历所有公司/职工"的接口（避免链上循环与 gas 爆炸），
 * 名单类查询按区块链惯例交给事件索引：监听合约事件写入本表，前端即可做列表、分页、报表。
 * 链上余额与状态始终是唯一真相，本表随时可以丢弃重建（重放事件即可）。
 */
@Data
@TableName("cs_chain_event")
public class ChainEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String txHash;

    private Long blockNumber;

    private String contractName;

    private String functionName;

    private String eventName;

    /** 关联职工地址（若事件涉及） */
    private String employeeAddress;

    /** 关联单位地址（若事件涉及） */
    private String sponsorAddress;

    /** 缴费合计 / 托管金额等主金额 */
    private BigDecimal amount;

    private BigDecimal personalAmount;

    private BigDecimal overallAmount;

    /** 事件原始 JSON，保留全部字段便于后续扩展 */
    private String payload;

    private LocalDateTime createdAt;
}
