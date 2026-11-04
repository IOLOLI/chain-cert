package com.chainsip.policy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 参数版本值（{@code cs_policy_parameter}）。
 *
 * <p><b>只追加、不改写</b>：政策调整时新插一行并把上一行的 {@code validTo} 截止到生效前一日，
 * 因此"某年某月的政策口径"永远查得到，历史核定单复算也不会漂移。
 *
 * <p>{@code status}：DRAFT（已预置、尚未到生效日）/ ACTIVE（生效中）/ TERMINATED（已截止）。
 * DRAFT→ACTIVE 由 {@code PolicyActivationScheduler} 每日切换，不提供"提前生效"写接口。
 */
@Data
@TableName("cs_policy_parameter")
public class PolicyParameter {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String paramCode;

    /** NATIONAL 或城市名（与合约 {@code bureau.cities} 的城市口径一致） */
    private String regionCode;

    /** 原始字符串值；JSON 型参数存 JSON 文本 */
    private String paramValue;

    /** 生效起（含） */
    private LocalDate validFrom;

    /** 生效止（含）；null = 长期有效 */
    private LocalDate validTo;

    /** 同一 (paramCode, regionCode) 内递增 */
    private Integer versionNo;

    private String status;

    /** 操作账号 */
    private String createdBy;

    private LocalDateTime createdAt;
}
