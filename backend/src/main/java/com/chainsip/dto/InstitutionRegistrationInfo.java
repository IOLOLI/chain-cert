package com.chainsip.dto;

import lombok.Data;

/**
 * 「待上链注册」列表的一行：一个已在链下登记机构密钥、但在链上还没注册（或已注册但未授权）的机构。
 *
 * <p>{@code missing} 是给前端直接展示的结论串，只有三种取值：
 * <ul>
 *   <li>{@code 未注册} —— 链上 roleOf(address) 与该机构登记的角色不一致（含 NONE）；</li>
 *   <li>{@code 未授权} —— 仅 BUREAU：已注册上链但 getBureau().authorized == false；</li>
 *   <li>{@code ""} —— 一切就绪；按契约这种行不会出现在列表里。</li>
 * </ul>
 */
@Data
public class InstitutionRegistrationInfo {

    /** 机构链账户编码，例如 BUREAU_AB12CD —— 也就是 register 接口的 {code} 路径参数 */
    private String code;

    /** 链下登记的角色：BUREAU / SPONSOR / REGULATOR */
    private String role;

    /** 机构名称（去空白，可能为空串） */
    private String name;

    /** 机构链上地址 */
    private String address;

    /** 链上 roleOf(address) 的实际结果：NONE / ADMIN / BUREAU / SPONSOR / REGULATOR / UNKNOWN */
    private String onChainRole;

    /** 角色是否已在链上注册到位 */
    private Boolean registered;

    /** 是否已授权；只有 BUREAU 有意义，其余角色与 registered 同值 */
    private Boolean authorized;

    /** 结论串：未注册 / 未授权 / ""（已就绪） */
    private String missing;
}
