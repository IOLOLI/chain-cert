package com.chainsip.dto;

import lombok.Data;

/**
 * 层级化开通账号的返回体（BUREAU → SPONSOR、SPONSOR → EMPLOYEE，ADMIN 侧仍返回 {@link AccountView}）。
 *
 * <p>{@code institutionCode} / {@code chainAddress} 存在的意义：新开的机构账号在链上还没有角色，
 * 必须由 ADMIN 用新地址去发起链上注册（合约里 addSponsorAccount / addBureaus / addRegulatorAccount
 * 都只允许 ADMIN 调用）。因此这里把"待注册的地址 + 下一步该调哪个接口"一并返回，
 * 前端/管理员不用再去翻数据库。
 *
 * <p>职工（EMPLOYEE）同样会拿到一把自动生成的链上密钥（编码形如 {@code EMP_AB12CD}）：
 * 这两个字段都会返回真实值。字段始终存在且为字符串，前端不必做 undefined 判断
 * （机构密钥解析不到时返回空串）。
 */
@Data
public class AccountCreateResult {

    /** 新账号的视图（不含任何私钥信息） */
    private AccountView account;

    /** 链上密钥编码，例如 SPONSOR_AB12CD；职工账号为 EMP_AB12CD */
    private String institutionCode;

    /** 该账号自己的链上地址（0x + 40 位）；职工账号同样有 */
    private String chainAddress;

    /** 给管理员/用户看的下一步提示（含应调用的接口路径） */
    private String hint;

    /** 链上注册时要用的机构名称（缴费单位名称 / 社保局名称），可能为空串 */
    private String name;

    /** 链上注册时要用的城市（缴费单位 / 社保局），可能为空串 */
    private String city;

    /**
     * 是否已自动完成链上注册：
     * <ul>
     *   <li>{@code true} —— 创建时已按角色注册上链（BUREAU 还完成了授权）；</li>
     *   <li>{@code false} —— 未注册：缺 name/city，或链上调用失败（账号仍然创建成功）；</li>
     *   <li>{@code null} —— 该角色无需链上注册（ADMIN / EMPLOYEE）。</li>
     * </ul>
     */
    private Boolean onChainRegistered;

    /** 链上注册结果说明：成功、缺字段提示、或链上失败的真实错误 */
    private String onChainMessage;
}
