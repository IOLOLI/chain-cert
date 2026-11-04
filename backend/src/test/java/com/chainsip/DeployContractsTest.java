package com.chainsip;

import org.fisco.bcos.sdk.BcosSDK;
import org.fisco.bcos.sdk.client.Client;
import org.fisco.bcos.sdk.crypto.keypair.CryptoKeyPair;
import org.fisco.bcos.sdk.transaction.manager.AssembleTransactionProcessor;
import org.fisco.bcos.sdk.transaction.manager.TransactionProcessorFactory;
import org.fisco.bcos.sdk.transaction.model.dto.TransactionResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * 可选的部署工具：把 contracts（0.6.10）的两个合约部署到已运行的 FISCO BCOS 2.11 链。
 *
 * <p>用后端的 ADMIN 机构私钥（0x1111…）作为部署者 —— 这样合约里的第一个 ADMIN 就是后端自己，
 * 不需要再做 addAdmin 交接。部署完打印两个地址，写进 application.yml 的
 * chainsip.chain.contracts.core / .transfer 即可。
 *
 * <p><b>默认不参与构建</b>：它会在链上产生真实交易（每次执行都部署一对新合约），
 * 所以用 {@link EnabledIfSystemProperty} 关掉了，只有显式带上 {@code -Dchainsip.deploy=true} 才执行：
 *
 * <pre>
 * mvn -s settings-2x.xml "-Dmaven.repo.local=~/.m2/repository" test -Dtest=DeployContractsTest ^
 *     -Dchainsip.deploy=true -Dchainsip.deploy.privateKey=&lt;部署者私钥64位hex&gt;
 * </pre>
 *
 * <p>如果你按文档在 WeBASE 里手工部署合约，这个类可以一直不用。
 */
@EnabledIfSystemProperty(named = "chainsip.deploy", matches = "true")
public class DeployContractsTest {

    /**
     * 部署者私钥：从系统属性 {@code chainsip.deploy.privateKey} 或环境变量 {@code CHAINSIP_ADMIN_PRIVATE_KEY} 读取。
     *
     * <p>本类刻意<b>不内置任何私钥</b>：部署者会成为合约里的第一个 ADMIN，
     * 用哪个账户部署就填哪个账户的私钥（例如控制台部署账户的私钥）。
     */
    private static String deployerKey() {
        String key = System.getProperty("chainsip.deploy.privateKey");
        if (key == null || key.trim().isEmpty()) {
            key = System.getenv("CHAINSIP_ADMIN_PRIVATE_KEY");
        }
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalStateException("未提供部署者私钥：请加 -Dchainsip.deploy.privateKey=<64位hex>，"
                    + "或先设置环境变量 CHAINSIP_ADMIN_PRIVATE_KEY");
        }
        return key.trim();
    }

    @Test
    public void deploy() throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        System.setProperty("java.library.ffipath", root.resolve("sdk/native").toString());
        Files.createDirectories(root.resolve("sdk/account"));

        // 用绝对证书路径构造配置（与网关 materializeConfig 的做法一致）
        String content = new String(Files.readAllBytes(root.resolve("sdk/config.toml")),
                StandardCharsets.ISO_8859_1);
        String absConf = root.resolve("sdk/conf").toString().replace("\\", "/");
        String absAccount = root.resolve("sdk/account").toString().replace("\\", "/");
        String cfg = content.replace("certPath = \"conf\"", "certPath = \"" + absConf + "\"")
                .replace("keyStoreDir = \"account\"", "keyStoreDir = \"" + absAccount + "\"");
        Path cfgFile = Files.createTempFile("deploy-sdk-config-", ".toml");
        Files.write(cfgFile, cfg.getBytes(StandardCharsets.ISO_8859_1));

        BcosSDK sdk = BcosSDK.build(cfgFile.toString());
        Client client = sdk.getClient(1);
        System.out.println("### groupId = " + client.getGroupId());
        System.out.println("### 当前区块高度 = " + client.getBlockNumber().getBlockNumber());

        CryptoKeyPair keyPair = client.getCryptoSuite().createKeyPair(deployerKey());
        String deployer = keyPair.getAddress();
        System.out.println("### 部署者地址（该账户会成为合约第一个 ADMIN）= " + deployer);

        String abiDir = root.resolve("src/main/resources/abi").toString();
        String binDir = abiDir;
        AssembleTransactionProcessor processor =
                TransactionProcessorFactory.createAssembleTransactionProcessor(client, keyPair, abiDir, binDir);

        System.out.println("\n### 部署 ChainSIP（无参）...");
        TransactionResponse core = processor.deployByContractLoader("ChainSIP", new ArrayList<>());
        System.out.println("    returnCode=" + core.getReturnCode() + " msg=" + core.getReturnMessage()
                + " values=" + core.getValues());
        String coreAddr = core.getContractAddress();
        System.out.println("    >>> ChainSIP = " + coreAddr);
        if (coreAddr == null || coreAddr.isEmpty()) {
            System.out.println("    ✗ 部署失败，终止");
            return;
        }

        System.out.println("\n### 部署 ChainSIPTransfer(核心地址)...");
        TransactionResponse transfer = processor.deployByContractLoader("ChainSIPTransfer",
                new ArrayList<>(Arrays.asList(coreAddr)));
        System.out.println("    returnCode=" + transfer.getReturnCode() + " msg=" + transfer.getReturnMessage()
                + " values=" + transfer.getValues());
        String transferAddr = transfer.getContractAddress();
        System.out.println("    >>> ChainSIPTransfer = " + transferAddr);

        System.out.println("\n### 链上校验");
        System.out.println("    roleOf(部署者) = " + call(processor, "ChainSIP", coreAddr, "roleOf",
                new ArrayList<>(Arrays.asList(deployer))));
        System.out.println("    核心合约登记的转移合约 = " + call(processor, "ChainSIP", coreAddr, "transferContract",
                new ArrayList<>()));

        System.out.println("\n############ 写入 application.yml 的值 ############");
        System.out.println("chainsip.chain.contracts.core: " + coreAddr);
        System.out.println("chainsip.chain.contracts.transfer: " + transferAddr);
    }

    private String call(AssembleTransactionProcessor processor, String contractName, String to,
                        String method, java.util.List<Object> params) {
        try {
            return String.valueOf(processor.sendCallByContractLoader(contractName, to, method, params).getValues());
        } catch (Throwable t) {
            return "调用异常: " + t.getClass().getSimpleName() + " " + t.getMessage();
        }
    }
}
