package com.chainsip.dto;

import lombok.Data;

/**
 * 「待上链注册」逐条注册的结果。
 *
 * <p>注意：即使 {@code ok=false}，HTTP 状态仍是 200 —— 管理员在前端逐条点"注册"时，
 * 需要一次拿到每一条的成功/失败与原因，而不是被第一个失败中断整批操作。
 */
@Data
public class InstitutionRegisterResult {

    /** 机构链账户编码 */
    private String code;

    /** 链下登记的角色 */
    private String role;

    /** 机构链上地址 */
    private String address;

    /** 是否注册成功（BUREAU 要求"注册 + 授权"都成功才算 true） */
    private boolean ok;

    /** 结果说明：成功含地址，失败含真实错误 */
    private String message;
}
