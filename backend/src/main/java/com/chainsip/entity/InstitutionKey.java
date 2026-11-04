package com.chainsip.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 机构链账户（链下托管）。
 *
 * <p>为什么要按机构托管多把私钥：合约按 {@code msg.sender} 判权，如果后端只用一把平台私钥
 * 发所有交易，ADMIN / BUREAU / SPONSOR / REGULATOR 在合约眼里就是同一个地址，
 * 角色权限模型直接失效。因此每个机构（平台、每个社保局、每家单位、监管方）各持一把私钥，
 * 后端按"当前登录账号所属机构"挑选私钥签名。
 *
 * <p>私钥以 AES-256-GCM 密文落库，主密钥来自配置/环境变量。
 */
@Data
@TableName("cs_institution_key")
public class InstitutionKey {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 机构编码，如 ADMIN / BUREAU_A / SPONSOR_S1 / REGULATOR */
    private String code;

    private String name;

    /** ADMIN / SPONSOR / BUREAU / REGULATOR */
    private String role;

    /** 链上地址（部署合约的 ADMIN 地址、或由 ADMIN 在链上注册的机构地址） */
    private String address;

    /** AES-256-GCM 密文（Base64） */
    private String privateKeyCipher;

    /** 该次加密使用的随机 IV（Base64） */
    private String iv;

    private Boolean enabled;

    private String remark;

    private LocalDateTime createdAt;
}
