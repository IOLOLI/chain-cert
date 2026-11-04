package com.chainsip;

import com.chainsip.config.CryptoProperties;
import com.chainsip.crypto.AesGcmCipher;
import com.chainsip.crypto.PasswordHasher;
import com.chainsip.crypto.WalletSignatureVerifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 链下密码学自测：密码派生、机构私钥加密、钱包签名与地址恢复。
 *
 * <p>这些用例不依赖链，可以在起链之前先跑通，确认认证链路本身没问题。
 */
class CryptoAndWalletTest {

    private static final String DEV_PRIVATE_KEY =
            "1111111111111111111111111111111111111111111111111111111111111111";

    @Test
    @DisplayName("PBKDF2 密码派生：同一密码可校验，错误密码不通过，盐随机")
    void passwordHashRoundTrip() {
        PasswordHasher hasher = new PasswordHasher();
        String hash1 = hasher.hash("ChangeMe_123456");
        String hash2 = hasher.hash("ChangeMe_123456");

        assertTrue(hasher.matches("ChangeMe_123456", hash1), "正确密码应通过校验");
        assertFalse(hasher.matches("admin124", hash1), "错误密码必须失败");
        assertNotEquals(hash1, hash2, "相同密码两次派生的盐应不同（防彩虹表）");
        assertFalse(hasher.matches("ChangeMe_123456", "明文或损坏的存储值"), "非法格式应安全失败");
    }

    @Test
    @DisplayName("机构私钥加密：AES-256-GCM 加解密往返一致，密文每次不同")
    void institutionKeyEncryptionRoundTrip() {
        CryptoProperties properties = new CryptoProperties();
        properties.setMasterKey("chainsip-test-master-key-32bytes!");
        AesGcmCipher cipher = new AesGcmCipher(properties);

        AesGcmCipher.CipherText first = cipher.encrypt(DEV_PRIVATE_KEY);
        AesGcmCipher.CipherText second = cipher.encrypt(DEV_PRIVATE_KEY);

        assertNotEquals(first.getCipherBase64(), second.getCipherBase64(), "随机 IV 应让密文每次都不同");
        assertTrue(DEV_PRIVATE_KEY.equals(cipher.decrypt(first.getCipherBase64(), first.getIvBase64())),
                "解密结果应与原文一致");

        // 换主密钥后必须解密失败（保证密文与主密钥绑定）
        CryptoProperties other = new CryptoProperties();
        other.setMasterKey("another-master-key-please-change-32bytes");
        AesGcmCipher otherCipher = new AesGcmCipher(other);
        boolean failed = false;
        try {
            otherCipher.decrypt(first.getCipherBase64(), first.getIvBase64());
        } catch (RuntimeException e) {
            failed = true;
        }
        assertTrue(failed, "错误主密钥不应能解密");
    }

    @Test
    @DisplayName("钱包签名：能恢复出地址、验签通过，消息或地址不符必须失败")
    void walletSignatureVerify() {
        WalletSignatureVerifier verifier = new WalletSignatureVerifier();

        String address = verifier.addressOf(DEV_PRIVATE_KEY);
        String message = "ChainSIP 区块链养老保险平台 · 钱包地址绑定\n账号：sponsor1\n地址：" + address;
        String signature = verifier.signMessage(DEV_PRIVATE_KEY, message);

        System.out.println("[自测] 私钥推导地址 = " + address);
        System.out.println("[自测] 签名 = " + signature);
        System.out.println("[自测] 恢复出的候选地址 = " + verifier.recoverCandidates(message, signature));

        assertTrue(verifier.verify(message, signature, address), "本人签名应通过验签");
        assertFalse(verifier.verify(message + "（被篡改）", signature, address), "消息被改动后必须失败");
        assertFalse(verifier.verify(message, signature, "0x0000000000000000000000000000000000000001"),
                "换成别人的地址必须失败");
        assertTrue(address.startsWith("0x") && address.length() == 42, "地址应为 0x + 40 位");
    }
}
