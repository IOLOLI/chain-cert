package com.chainsip.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 退休核定单（{@code cs_pension_determination}）。
 *
 * <p>链上只有"退休状态 + 每月个人账户发放额"两个字段，回答不了"当时按什么政策算的"。
 * 本表把一次核定拆成四块留档：
 * <ol>
 *   <li><b>入参</b>：退休日、年龄、累计月数、缴费基数、个人账户余额；</li>
 *   <li><b>政策快照</b>：核定当天的 {@code PolicyContext} 全量 JSON + SHA-256；</li>
 *   <li><b>结果</b>：基础养老金 / 个人账户养老金 / 合计（链上 wei 口径）；</li>
 *   <li><b>链路</b>：{@code retireEmployee} 的交易哈希与状态（PENDING → APPROVED / FAILED）。</li>
 * </ol>
 *
 * <p>有快照在，几年后政策怎么调都能复算出同一结果——这是"政策参数化"能被审计的前提。
 */
@Data
@TableName("cs_pension_determination")
public class PensionDetermination {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 职工链上地址（小写） */
    private String employeeAddress;

    private LocalDate retireDate;

    /** 退休时周岁 */
    private Integer retireAge;

    /** 核定累计缴费月数 */
    private Integer paidMonths;

    /** 退休时缴费基数（wei） */
    private BigDecimal paymentBase;

    /** 退休时个人账户余额（wei） */
    private BigDecimal accountBalance;

    /** 基础养老金月额（统筹池支出，wei） */
    private BigDecimal basicMonthly;

    /** 个人账户养老金月额（个人账户支出，wei） */
    private BigDecimal personalMonthly;

    /** 合计（wei） */
    private BigDecimal monthlyTotal;

    /** 核定时 PolicyContext 的全量 JSON（不可变） */
    private String policySnapshot;

    /** 快照 SHA-256（与链上存证指纹同口径） */
    private String policySnapshotHash;

    /** 关键参数版本摘要，如 qual.min.months#6;benefit.basic.rate.yearly#3 */
    private String policyVersionRef;

    /** PENDING（已落库待上链）/ APPROVED / FAILED / SUPERSEDED */
    private String status;

    /** retireEmployee 交易哈希 */
    private String determinationTxHash;

    /** LIVE = 本次核定；LEGACY = 存量退休人员补建 */
    private String source;

    private String operator;

    private LocalDateTime determinedAt;
}
