package com.chainsip.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 平台账号（链下）。密码只存 PBKDF2 派生值，绝不上链。
 *
 * <p>role / institutionCode 只用于"界面展示 + 挑选发交易用哪把机构私钥"；
 * 真正的权限判断在合约里（{@code roleOf(address)}），链下角色与链上不一致时交易会被合约回滚。
 */
@Data
@TableName("cs_account")
public class Account {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    /** PBKDF2-HMAC-SHA256 派生值，格式见 {@code PasswordHasher} */
    private String passwordHash;

    private String displayName;

    /** ADMIN / SPONSOR / BUREAU / REGULATOR / EMPLOYEE */
    private String role;

    /** 关联的机构链账户编码（cs_institution_key.code），决定发交易时用哪把私钥 */
    private String institutionCode;

    /** ENABLED / DISABLED */
    private String status;

    /**
     * <b>创建者账号 id</b>：层级开号时写入"是谁建的"（ADMIN 建的写 ADMIN 的 id、社保局建的写该社保局的 id、
     * 单位建的职工写该单位的 id）。系统初始化的 admin 为 {@code null}。
     *
     * <p>用途：机构账号列表按登录者范围收口时，"创建归属"这一条就靠它
     * （见 {@code AccountHierarchyService#listMyEmployeeAccounts}），与"链上归属"取并集。
     * 老库由 {@code AccountCreatedByMigration} 在启动时幂等补列。
     */
    private Long createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
