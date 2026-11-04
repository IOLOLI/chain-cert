package com.chainsip;

import org.fisco.bcos.sdk.BcosSDK;
import org.fisco.bcos.sdk.client.Client;
import org.fisco.bcos.sdk.contract.precompiled.consensus.ConsensusService;
import org.fisco.bcos.sdk.crypto.keypair.CryptoKeyPair;
import org.fisco.bcos.sdk.model.RetCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 可选运维工具：把新节点加入为 observer（FISCO BCOS 2.x 共识接口，非合约调用）。
 *
 * <p>默认不执行，显式带参数才跑：
 * <pre>
 * mvn -o test -Dtest=AddObserverNodeTest -Dchainsip.addObserver=true ^
 *     -Dchainsip.observerNodeId=&lt;128位nodeid&gt; ^
 *     -Dchainsip.deploy.privateKey=&lt;管理员私钥64位hex&gt;
 * </pre>
 *
 * <p>调用的是节点内置的共识 precompiled（ConsensusService），因此不需要合约地址，
 * 也不需要重启 node0-3；addObserver 经 PBFT 共识生效，新节点会立刻开始同步区块。
 */
@EnabledIfSystemProperty(named = "chainsip.addObserver", matches = "true")
public class AddObserverNodeTest {

    @Test
    public void addObserver() throws Exception {
        String nodeId = System.getProperty("chainsip.observerNodeId", "").trim();
        if (nodeId.isEmpty()) {
            throw new IllegalStateException("缺少 -Dchainsip.observerNodeId=<128位nodeid>");
        }
        String privateKey = System.getProperty("chainsip.deploy.privateKey", "").trim();
        if (privateKey.isEmpty()) {
            privateKey = System.getenv("CHAINSIP_ADMIN_PRIVATE_KEY");
        }

        Path root = Paths.get("").toAbsolutePath();
        System.setProperty("java.library.ffipath", root.resolve("sdk/native").toString());
        Files.createDirectories(root.resolve("sdk/account"));

        String content = new String(Files.readAllBytes(root.resolve("sdk/config.toml")),
                StandardCharsets.ISO_8859_1);
        String absConf = root.resolve("sdk/conf").toString().replace("\\", "/");
        String absAccount = root.resolve("sdk/account").toString().replace("\\", "/");
        String cfg = content.replace("certPath = \"conf\"", "certPath = \"" + absConf + "\"")
                .replace("keyStoreDir = \"account\"", "keyStoreDir = \"" + absAccount + "\"");
        Path cfgFile = Files.createTempFile("observer-sdk-config-", ".toml");
        Files.write(cfgFile, cfg.getBytes(StandardCharsets.ISO_8859_1));

        BcosSDK sdk = BcosSDK.build(cfgFile.toString());
        Client client = sdk.getClient(1);
        CryptoKeyPair keyPair = client.getCryptoSuite().createKeyPair(privateKey);

        System.out.println("### 当前块高 = " + client.getBlockNumber().getBlockNumber());
        System.out.println("### observer(before) = " + client.getObserverList().getObserverList());
        System.out.println("### sealer(before) = " + client.getSealerList().getSealerList());

        ConsensusService consensus = new ConsensusService(client, keyPair);
        RetCode ret = consensus.addObserver(nodeId);
        System.out.println("### addObserver ret = " + ret);

        Thread.sleep(3000);
        List<String> observers = client.getObserverList().getObserverList();
        List<String> sealers = client.getSealerList().getSealerList();
        System.out.println("### observer(after) = " + observers);
        System.out.println("### sealer(after) = " + sealers);
        System.out.println("### contains node4 = " + observers.contains(nodeId));
    }
}
