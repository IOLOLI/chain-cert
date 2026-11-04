package com.chainsip.chain.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 发交易时使用的机构链账户（机构编码 + 地址 + 已解密的私钥）。
 *
 * <p>私钥只在这一层的入参里以明文短时存在，不落日志、不进响应体。
 */
@Data
@AllArgsConstructor
public class ChainAccount {

    private String institutionCode;

    private String address;

    private String privateKeyHex;
}
