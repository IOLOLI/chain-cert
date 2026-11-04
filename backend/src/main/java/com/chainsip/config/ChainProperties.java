package com.chainsip.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 链相关配置（chainsip.chain.*）。合约地址在 WebASE 部署完成后填入配置或环境变量。
 *
 * <p><b>2.x 与 3.x 的一处结构性差异</b>：3.x 的 config.toml 里有 {@code [network] defaultGroup}，
 * 群组号写在 SDK 配置文件里；2.x 的配置文件根本没有这个字段，群组号是在 Java 侧通过
 * {@code BcosSDK.getClient(groupId)} 选定的。因此群组号在这里做成配置项，
 * 既用于取 Client，也用于 {@code /api/chain/status} 展示。
 */
@Data
@ConfigurationProperties(prefix = "chainsip.chain")
public class ChainProperties {

    /** 是否启用链交互。false 时应用照常启动，链相关接口直接返回"未启用"。 */
    private boolean enabled = true;

    /** FISCO BCOS Java SDK 2.x 的配置文件路径（文件系统路径，非 classpath）。 */
    private String sdkConfigFile = "./sdk/config.toml";

    /**
     * 群组 ID。FISCO BCOS 2.x 的 build_chain 默认只有一个群组，编号为 1。
     * 对应 {@code BcosSDK.getClient(groupId)} 的入参。
     */
    private int groupId = 1;

    /** 节点 channel 端口地址列表，仅用于状态展示与排查提示（真正连接用的 peers 在 config.toml 里）。 */
    private List<String> peers = new ArrayList<>();

    /** ABI 资源目录（classpath 内），启动时导出到临时目录供 SDK 的文件式加载器使用。 */
    private String abiResourceDir = "abi";

    /** 请求超时（毫秒）。 */
    private long timeoutMillis = 15_000L;

    private Contracts contracts = new Contracts();

    @Data
    public static class Contracts {
        /** 核心合约 ChainSIP 的部署地址 */
        private String core = "";
        /** 转移合约 ChainSIPTransfer 的部署地址 */
        private String transfer = "";
    }

    /** 核心合约 ABI 名称（同时也是 SDK 合约加载器使用的合约名） */
    public static final String CORE = "ChainSIP";

    /** 转移合约 ABI 名称 */
    public static final String TRANSFER = "ChainSIPTransfer";

    public String addressOf(String contractName) {
        if (CORE.equals(contractName)) {
            return contracts.getCore();
        }
        if (TRANSFER.equals(contractName)) {
            return contracts.getTransfer();
        }
        throw new IllegalArgumentException("未知合约名: " + contractName);
    }

    public boolean isDeployed(String contractName) {
        String address = addressOf(contractName);
        return address != null && address.trim().length() > 0;
    }
}
