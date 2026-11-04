package com.chainsip.policy.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 管理端「调整政策参数」请求体。
 *
 * <p>一次请求 = 追加一个版本：{@code validFrom} 留空表示今天生效；填未来日期即"预置"，
 * 到期由每日定时任务自动切换（DRAFT → ACTIVE），不需要改代码、不需要发版。
 */
@Data
public class PolicyModifyRequest {

    @NotBlank(message = "参数编码不能为空")
    private String paramCode;

    /** NATIONAL 或城市名；BUREAU 不传时默认本局服务城市，ADMIN 只能是 NATIONAL */
    private String regionCode;

    @NotBlank(message = "参数值不能为空")
    private String paramValue;

    /** 生效日期 yyyy-MM-dd；留空 = 今天 */
    private String validFrom;

    @NotBlank(message = "变更原因不能为空（审计要求）")
    private String reason;
}
