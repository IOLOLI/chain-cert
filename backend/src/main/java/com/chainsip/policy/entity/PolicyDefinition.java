package com.chainsip.policy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 参数字典（{@code cs_policy_definition}）。
 *
 * <p>一个参数编码对应一行：类型（NUMBER / RATE / INT / JSON）、全国默认值、值域与政策文号。
 * 版本值放在 {@link PolicyParameter}，本表只描述"这个参数是什么"，不随年度变化。
 *
 * <p>{@code defaultValue} 是三级回落里的第二级：参数表没有生效版本时用它，
 * 它再缺失时才用代码里的硬默认（= 合约常量）。
 */
@Data
@TableName("cs_policy_definition")
public class PolicyDefinition {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 参数编码，如 contrib.employee.rate */
    private String paramCode;

    /** 中文名，如「职工个人缴费比例」 */
    private String paramName;

    /** NUMBER / RATE / INT / JSON（TEXT 预留给非结构化说明性参数） */
    private String paramType;

    /** 全国默认值（参数表缺失时兜底） */
    private String defaultValue;

    /** 数值下限（管理端校验用，RATE / NUMBER 可空） */
    private BigDecimal valueMin;

    /** 数值上限 */
    private BigDecimal valueMax;

    /** NATIONAL / CITY（一期主要是 NATIONAL，预留城市级） */
    private String regionScope;

    /** 缴费 / 资格 / 退休 / 待遇 */
    private String category;

    /** ENABLED / DISABLED（停用的参数不允许再追加版本） */
    private String status;

    /** 政策依据文号，如 国发〔2005〕38号 */
    private String policyDoc;

    private String remark;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
