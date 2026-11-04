package com.chainsip.crypto;

import com.chainsip.common.BizException;
import com.chainsip.common.ErrorCode;
import com.webank.wedpr.crypto.CryptoResult;
import com.webank.wedpr.crypto.NativeInterface;
import org.fisco.bcos.sdk.crypto.CryptoSuite;
import org.fisco.bcos.sdk.crypto.keypair.CryptoKeyPair;
import org.fisco.bcos.sdk.crypto.keypair.ECDSAKeyPair;
import org.fisco.bcos.sdk.crypto.signature.ECDSASignatureResult;
import org.fisco.bcos.sdk.crypto.signature.SignatureResult;
import org.fisco.bcos.sdk.model.CryptoType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 钱包签名验证：把"账号绑定地址"这一步做成需要私钥签名证明地址归属，而不是让用户随手填一个地址。
 *
 * <p>为什么必须验签：不验签的话任何人都能把别人的链上地址绑到自己账号上，进而以别人的身份
 * 发起链上交易（合约只会认 msg.sender，一旦我们拿错私钥发交易，受害的是被冒名者）。
 *
 * <p>兼容三种常见签名约定，只要任意一种能恢复出用户声明的地址，就算验签通过：
 * <ol>
 *   <li>FISCO SDK / WebASE 约定：对消息本身做 keccak256 后签名；</li>
 *   <li>EIP-191 {@code personal_sign}（MetaMask 等浏览器钱包）；</li>
 *   <li>客户端直接对 {@code keccak256(message)} 的十六进制串签名。</li>
 * </ol>
 * 多方案并存不会削弱安全性：想让某个方案恢复出目标地址，仍需要针对该方案伪造出合法签名，
 * 这与直接伪造签名同样困难。
 *
 * <h2>SDK 3.x → 2.x 的移植要点（本文件的核心差异）</h2>
 * 3.x 的 {@code CryptoSuite} 提供了现成的
 * {@code recoverAddress(String hash, SignatureResult sig)}，一行就能"由签名恢复地址"。
 * <b>2.x 完全没有这个 API</b>：用 {@code javap} 核对过
 * {@code CryptoSuite} / {@code Signature} / {@code ECDSASignature} 的全部 public 成员，
 * 只有 {@code sign} / {@code verify}，没有任何 recover。
 *
 * <p>更麻烦的是，<b>2.x 的 {@code verify} 第一个参数是公钥，不是地址</b>：
 * <pre>
 *   实测（SDK 2.10.1，secp256k1）：
 *     suite.verify(pub,  hash, sig) = true      // pub = 128 位十六进制公钥
 *     suite.verify(addr, hash, sig) = false     // 地址不行，原生库报
 *                                               // "Matching signature public key failed"
 * </pre>
 * 也就是说 2.x 公开 API 里没有"地址 → 签名者"的判定能力，
 * 而验签的业务前提恰恰是"只拿到用户声明的地址 + 签名 + 消息"。
 *
 * <h3>怎么补上</h3>
 * 2.x 依赖的 WeDPR 原生库其实暴露了公钥恢复（用 {@code javap} 在
 * {@code com.webank.wedpr.crypto.NativeInterface} 上找到）：
 * <pre>
 *   secp256k1RecoverPublicKey(String message, String signature) -&gt; CryptoResult
 * </pre>
 * 于是恢复流程等价重建为：
 * <pre>
 *   3.x:  cryptoSuit.recoverAddress(hash, sig)
 *   2.x:  NativeInterface.secp256k1RecoverPublicKey(hash, sig).getPublicKey()
 *           -&gt; ECDSAKeyPair.getAddressByPublicKey(publicKey)
 * </pre>
 * 实测三者闭环一致（同一把私钥）：
 * <pre>
 *   addr                                  = <SIGNER_ADDRESS>
 *   recoverPublicKey(hash, sig)           = 04 4f355bdc...bb2f1c1   （带 04 未压缩前缀）
 *   getAddressByPublicKey(上面这串)        = <SIGNER_ADDRESS>  ✓
 * </pre>
 * 因此<b>对外语义与 3.x 完全一致</b>：仍然是"恢复出地址再比对"，
 * {@link #recoverCandidates} 也仍然能"无中生有"地列出签名者真实地址。
 */
@Component
public class WalletSignatureVerifier {

    private static final Logger log = LoggerFactory.getLogger(WalletSignatureVerifier.class);
    private static final String ZERO_ADDRESS = "0x0000000000000000000000000000000000000000";

    static {
        // 必须在任何 CryptoSuite 构造之前把 WeDPR 原生库指到可写目录，
        // 否则 SDK 会去写 ~/.fisco/nativeutils/native.lock，在只读用户目录下直接崩。
        WdprNativeLibraryBootstrap.provision();
    }

    /** 只做本地签名/验签，不需要连链，因此独立构造一个 ECDSA 套件。 */
    private final CryptoSuite cryptoSuite = new CryptoSuite(CryptoType.ECDSA_TYPE);

    /** 验签：签名恢复出的地址之一等于声明地址即通过。 */
    public boolean verify(String message, String signatureHex, String expectedAddress) {
        if (message == null || signatureHex == null || expectedAddress == null) {
            return false;
        }
        String expected = normalizeAddress(expectedAddress);
        for (String recovered : recoverCandidates(message, signatureHex)) {
            if (expected.equalsIgnoreCase(normalizeAddress(recovered))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 返回所有可行方案恢复出的候选地址（诊断用，便于前端排查签名方式不一致）。
     *
     * <p>与 3.x 版本行为一致：不需要调用方提供候选地址，直接由签名恢复。
     */
    public List<String> recoverCandidates(String message, String signatureHex) {
        List<String> candidates = new ArrayList<>();
        if (message == null || signatureHex == null) {
            return candidates;
        }

        String signature;
        try {
            // 构造一次只为校验长度/格式；2.x 的 ECDSASignatureResult(String) 要求恰好 65 字节
            SignatureResult parsed = new ECDSASignatureResult(normalizeSignature(signatureHex));
            signature = parsed.convertToString();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.WALLET_SIGNATURE_INVALID, "签名格式不正确，应为 65 字节十六进制");
        }

        // 方案 1（主）：FISCO SDK / WebASE 约定 —— 签的是 keccak256(消息) 这个 32 字节哈希。
        // 注意 SDK 的 sign/recover 要求入参是 64 位十六进制哈希字符串，
        // 直接传 UTF-8 原文会抛 "must be a hex string of length 64"。
        tryRecover(candidates, () -> recoverAddress(cryptoSuite.hash(message), signature), "fisco-hash");

        // 方案 2：EIP-191 personal_sign（MetaMask 等浏览器钱包）——
        // 签的是 keccak256("\x19Ethereum Signed Message:\n" + 长度 + 消息)
        // 注意 2.x 的 CryptoSuite.hash(byte[]) 不是 public（3.x 的可用），
        // 因此走 Hash#hashBytes(byte[]) —— 它直接返回十六进制字符串，正好可交给原生恢复接口。
        tryRecover(candidates,
                () -> recoverAddress(cryptoSuite.getHashImpl().hashBytes(eip191(message)), signature),
                "eip191");

        // 方案 3：调用方已经把消息哈希好，直接把 64 位哈希当原文传进来
        if (message.matches("^(0x)?[0-9a-fA-F]{64}$")) {
            String hash = message.startsWith("0x") || message.startsWith("0X")
                    ? message.substring(2) : message;
            tryRecover(candidates, () -> recoverAddress(hash.toLowerCase(Locale.ROOT), signature), "raw-hash");
        }

        if (candidates.isEmpty()) {
            log.warn("签名无法恢复出任何地址，message 长度={}, signature={}", message.length(), signatureHex);
        }
        return candidates;
    }

    /** 用私钥对消息签名（本地自测、以及给前端演示"该用什么方式签名"时使用）。 */
    public String signMessage(String privateKeyHex, String message) {
        CryptoKeyPair keyPair = loadKeyPair(privateKeyHex);
        // 与验签保持一致：先对消息做哈希，再对该哈希签名
        return cryptoSuite.sign(cryptoSuite.hash(message), keyPair).convertToString();
    }

    /** 由私钥推导链上地址（机构链账户登记、自测用）。 */
    public String addressOf(String privateKeyHex) {
        return normalizeAddress(loadKeyPair(privateKeyHex).getAddress());
    }

    /** 生成一个新的链账户（机构私钥由本服务生成并加密落库）。 */
    public NewKeyPair generateKeyPair() {
        // 2.x 没有 generateRandomKeyPair()；createKeyPair() 的无参重载就是"随机生成"。
        CryptoKeyPair keyPair = cryptoSuite.createKeyPair();
        return new NewKeyPair(normalizeAddress(keyPair.getAddress()), normalizePrivateKey(keyPair.getHexPrivateKey()));
    }

    // ------------------------------------------------------------------
    // 内部：2.x 的"由签名恢复地址"
    // ------------------------------------------------------------------

    /**
     * 由 {@code keccak256} 哈希与 65 字节签名恢复签名者地址。
     *
     * <p>这是 3.x {@code CryptoSuite.recoverAddress(hash, sig)} 在 2.x 上的等价实现，
     * 走的是 WeDPR 原生库的 {@code secp256k1RecoverPublicKey}。
     *
     * @param hash         64 位十六进制哈希（不带 0x）
     * @param signatureHex 130 位十六进制签名（r||s||v）
     * @return 恢复出的地址；恢复失败返回 null
     */
    private String recoverAddress(String hash, String signatureHex) {
        CryptoResult result = NativeInterface.secp256k1RecoverPublicKey(hash, signatureHex);
        if (result == null) {
            return null;
        }
        String publicKey = result.getPublicKey();
        if (publicKey == null || publicKey.isEmpty()) {
            return null;
        }
        // 原生库返回的是带 04 前缀的未压缩公钥；SDK 的 getAddressByPublicKey 两种都吃，
        // 但去掉前缀更符合 SDK 内部约定（CryptoKeyPair.getHexPublicKey() 同样不带前缀）。
        String normalizedPublicKey = publicKey.startsWith("04") && publicKey.length() == 130
                ? publicKey.substring(2) : publicKey;
        return ECDSAKeyPair.getAddressByPublicKey(normalizedPublicKey);
    }

    /** 用私钥构造密钥对（2.x 是 {@code createKeyPair(String)}，3.x 叫 {@code loadKeyPair}）。 */
    private CryptoKeyPair loadKeyPair(String privateKeyHex) {
        return cryptoSuite.createKeyPair(normalizePrivateKey(privateKeyHex));
    }

    private void tryRecover(List<String> candidates, RecoverCall call, String label) {
        try {
            String address = call.call();
            if (address != null && address.length() > 0 && !ZERO_ADDRESS.equalsIgnoreCase(normalizeAddress(address))) {
                String normalized = normalizeAddress(address);
                if (!candidates.contains(normalized)) {
                    candidates.add(normalized);
                }
            }
        } catch (Exception e) {
            log.debug("验签方案 [{}] 不适用：{}", label, e.getMessage());
        }
    }

    /** EIP-191 personal_sign 的待签名原文：\x19Ethereum Signed Message:\n{长度}{消息} */
    private byte[] eip191(String message) {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        byte[] prefix = ("\u0019Ethereum Signed Message:\n" + body.length).getBytes(StandardCharsets.UTF_8);
        byte[] result = Arrays.copyOf(prefix, prefix.length + body.length);
        System.arraycopy(body, 0, result, prefix.length, body.length);
        return result;
    }

    private String normalizeSignature(String signatureHex) {
        String s = signatureHex.trim().replace(" ", "");
        if (s.startsWith("0x") || s.startsWith("0X")) {
            s = s.substring(2);
        }
        if (s.length() != 130) {
            throw new BizException(ErrorCode.WALLET_SIGNATURE_INVALID, "签名长度应为 130 个十六进制字符");
        }
        return s.toLowerCase(Locale.ROOT);
    }

    private String normalizePrivateKey(String privateKeyHex) {
        String s = privateKeyHex.trim();
        if (s.startsWith("0x") || s.startsWith("0X")) {
            s = s.substring(2);
        }
        if (s.length() != 64) {
            throw new BizException(ErrorCode.BIZ_ERROR, "私钥长度应为 64 个十六进制字符");
        }
        return s.toLowerCase(Locale.ROOT);
    }

    private String normalizeAddress(String address) {
        String s = address == null ? "" : address.trim();
        if (s.startsWith("0x") || s.startsWith("0X")) {
            s = s.substring(2);
        }
        return "0x" + s.toLowerCase(Locale.ROOT);
    }

    @FunctionalInterface
    private interface RecoverCall {
        String call() throws Exception;
    }

    /** 新生成的链账户。 */
    public static class NewKeyPair {
        private final String address;
        private final String privateKey;

        public NewKeyPair(String address, String privateKey) {
            this.address = address;
            this.privateKey = privateKey;
        }

        public String getAddress() {
            return address;
        }

        public String getPrivateKey() {
            return privateKey;
        }
    }
}
