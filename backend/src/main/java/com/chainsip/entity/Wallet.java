package com.chainsip.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 账号 ↔ 链上地址绑定（链下）。
 *
 * <p>为什么链下：链上本来就是按地址索引的，不需要再存一份"账号→地址"的映射；
 * 而链上存了也没用——合约权限只认 msg.sender，不认平台账号。
 *
 * <p><b>绑定一律由平台自动完成</b>：链上地址（机构链账户 / 职工 {@code EMP_} 密钥）的私钥
 * 本来就由平台托管，账号创建时由 {@code AuthService.bindPlatformAddress} 直接写入本项目
 * （{@code verified=true}），用户无需、也无处再签名；{@code remark} 记录来源
 * （如"创建账号时自动生成并绑定"）。平台不提供手动绑定接口。
 */
@Data
@TableName("cs_wallet")
public class Wallet {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long accountId;

    private String address;

    private String groupId;

    private Boolean verified;

    private Boolean primaryWallet;

    private LocalDateTime boundAt;

    private LocalDateTime createdAt;

    /** 绑定来源备注：平台自动绑定写"创建账号时自动生成并绑定"之类。 */
    private String remark;
}
