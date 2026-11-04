package com.chainsip;

import org.fisco.bcos.sdk.BcosSDK;
import org.fisco.bcos.sdk.client.Client;
import org.fisco.bcos.sdk.crypto.keypair.CryptoKeyPair;
import org.fisco.bcos.sdk.transaction.manager.AssembleTransactionProcessor;
import org.fisco.bcos.sdk.transaction.manager.TransactionProcessorFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * 联调辅助：用 FISCO BCOS 2.x Java SDK 直接读链，核对机构地址的真实角色（roleOf）与社保局授权（getBureau）。
 *
 * <p>这是"实测报告"的证据工具，不做任何写操作（只 {@code sendCallByContractLoader}）。
 * 入口固定用后端 ADMIN 机构密钥（配置里注入的那个地址），只读调用不依赖 msg.sender 权限。
 *
 * <p><b>默认不执行</b>：只有显式带上 {@code -Dcheck.addresses=0x..[,0x..]} 才跑，
 * 否则整类跳过（Skipped），不会让 {@code mvn clean package} 变红：
 *
 * <pre>
 * mvn -s settings-2x.xml "-Dmaven.repo.local=~/.m2/repository" test -Dtest=ChainReadCheckTest ^
 *     -Dcheck.addresses=0x41be...,0x4bdd...
 * </pre>
 */
public class ChainReadCheckTest {

    /** 合约地址由系统属性或环境变量注入，仓库不保存任何真实部署地址。 */
    private static final String CORE = System.getProperty("check.core",
            System.getenv().getOrDefault("CHAINSIP_CORE_ADDRESS", ""));
    private static final String TRANSFER = System.getProperty("check.transfer",
            System.getenv().getOrDefault("CHAINSIP_TRANSFER_ADDRESS", ""));

    /** 待核对的机构地址（逗号分隔），由系统属性 -Dcheck.addresses=0x..,0x.. 传入。 */
    private static String[] addresses() {
        String raw = System.getProperty("check.addresses", "");
        Assumptions.assumeTrue(!raw.trim().isEmpty(),
                "未指定 -Dcheck.addresses=0x..，跳过链上只读核对");
        return raw.split(",");
    }

    @Test
    public void readRoles() throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        System.setProperty("java.library.ffipath", root.resolve("sdk/native").toString());
        Files.createDirectories(root.resolve("sdk/account"));

        String content = new String(Files.readAllBytes(root.resolve("sdk/config.toml")),
                StandardCharsets.ISO_8859_1);
        String absConf = root.resolve("sdk/conf").toString().replace("\\", "/");
        String absAccount = root.resolve("sdk/account").toString().replace("\\", "/");
        String cfg = content.replace("certPath = \"conf\"", "certPath = \"" + absConf + "\"")
                .replace("keyStoreDir = \"account\"", "keyStoreDir = \"" + absAccount + "\"");
        Path cfgFile = Files.createTempFile("read-check-sdk-config-", ".toml");
        Files.write(cfgFile, cfg.getBytes(StandardCharsets.ISO_8859_1));

        BcosSDK sdk = BcosSDK.build(cfgFile.toString());
        Client client = sdk.getClient(1);
        String senderKey = System.getProperty("check.senderPrivateKey");
        if (senderKey == null || senderKey.trim().isEmpty()) {
            senderKey = System.getenv("CHAINSIP_ADMIN_PRIVATE_KEY");
        }
        Assumptions.assumeTrue(senderKey != null && !senderKey.trim().isEmpty(),
                "未指定 -Dcheck.senderPrivateKey 或 CHAINSIP_ADMIN_PRIVATE_KEY，跳过链上只读核对");
        CryptoKeyPair keyPair = client.getCryptoSuite().createKeyPair(senderKey.trim());
        String abiDir = root.resolve("src/main/resources/abi").toString();
        AssembleTransactionProcessor processor =
                TransactionProcessorFactory.createAssembleTransactionProcessor(client, keyPair, abiDir, abiDir);

        System.out.println("### 区块高度 = " + client.getBlockNumber().getBlockNumber());
        System.out.println("### 调用者（只读，不影响结果） = " + keyPair.getAddress());
        for (String address : addresses()) {
            String addr = address.trim();
            System.out.println("### ---- " + addr + " ----");
            System.out.println("    CORE.roleOf        = "
                    + call(processor, "ChainSIP", CORE, "roleOf", new ArrayList<>(Arrays.asList(addr))));
            System.out.println("    CORE.isBureau      = "
                    + call(processor, "ChainSIP", CORE, "isBureau", new ArrayList<>(Arrays.asList(addr))));
            System.out.println("    CORE.isSponsor     = "
                    + call(processor, "ChainSIP", CORE, "isSponsor", new ArrayList<>(Arrays.asList(addr))));
            System.out.println("    CORE.isRegulator   = "
                    + call(processor, "ChainSIP", CORE, "isRegulator", new ArrayList<>(Arrays.asList(addr))));
            System.out.println("    TRANSFER.getBureau = "
                    + call(processor, "ChainSIPTransfer", TRANSFER, "getBureau", new ArrayList<>(Arrays.asList(addr))));
        }
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
