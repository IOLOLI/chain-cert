package com.chainsip.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 加解密相关配置（chainsip.crypto.*）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "chainsip.crypto")
public class CryptoProperties {

    /** 机构私钥落库加密的主密钥（AES-256-GCM）。生产用环境变量注入随机值。 */
    private String masterKey = "chainsip-dev-master-key-change-me-before-production";

    /**
     * 可选：ADMIN 机构链账户的私钥（64 位十六进制，可带 0x 前缀）。
     *
     * <p>非空时，后端启动会用它替换数据库里 code=ADMIN 的机构密钥：
     * 这样当你用 FISCO 控制台（或 WeBASE）部署合约时，直接把那个"部署账户"的私钥填进来即可 ——
     * 链上第一个 ADMIN 就是该部署账户，于是链上链下是同一个 ADMIN，不需要 addAdmin 交接。
     *
     * <p>建议用环境变量注入，不要写进仓库：{@code CHAINSIP_ADMIN_PRIVATE_KEY}。
     * 留空则保持数据库现状（即默认使用演示密钥）。
     */
    private String adminPrivateKey = "";

    /**
     * 是否开放"联调辅助"接口 {@code POST /api/auth/demo/sign}（用调用方自带的私钥对消息签名）。
     *
     * <p><b>定位：仅联调辅助</b>——它只用请求里传入的私钥签名、不涉及服务端密钥，方便在没有浏览器钱包时
     * 手工验证"私钥 → 地址 / 签名"的推导。它<b>前后端都没有业务调用方</b>（前端已不封装该接口、
     * 也没有任何页面依赖它），只供手工联调（curl / Swagger）使用。
     *
     * <p>本项目<b>不内置任何演示账号或演示密钥</b>，这个开关只控制该辅助接口本身；
     * <b>生产环境可以（也建议）置为 false</b>。
     */
    private boolean allowDemoSign = true;
}
