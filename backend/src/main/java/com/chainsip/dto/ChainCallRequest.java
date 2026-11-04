package com.chainsip.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.util.List;

/**
 * 通用链上调用请求：让接口文档里可以直接调试合约的任意 view 函数（只读）。
 */
@Data
public class ChainCallRequest {

    /** 合约名：ChainSIP（核心）或 ChainSIPTransfer（转移） */
    @NotBlank(message = "合约名不能为空")
    private String contract;

    @NotBlank(message = "函数名不能为空")
    private String function;

    /** 参数列表，按合约 ABI 顺序；大整数用字符串传，地址用 0x 开头字符串 */
    private List<Object> params;
}
