package com.chainsip.policy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 政策参数变更审计（{@code cs_policy_audit_log}）。
 *
 * <p>管理端每次追加版本写一行：谁（operator）、何时（operateAt）、把哪个参数
 * 从什么值改成什么值（oldValue → newValue）、依据什么（reason）、哪天生效（effectiveDate）。
 * 首次配置时 {@code oldValue} 为空。
 *
 * <p>这张表是"参数变更可追溯"的落点——只有版本表的话，只能看到"现在是什么"，
 * 看不到"为什么变成现在这样"。
 */
@Data
@TableName("cs_policy_audit_log")
public class PolicyAuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String paramCode;

    private String regionCode;

    private Integer versionNo;

    /** 变更前的值；首次配置为空 */
    private String oldValue;

    private String newValue;

    /** 该版本的生效日 */
    private LocalDate effectiveDate;

    /** 变更原因（管理端必填，审计要求） */
    private String reason;

    /** 操作人账号 */
    private String operator;

    private LocalDateTime operateAt;
}
