package com.chainsip.crypto;

import com.chainsip.config.CryptoProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 机构链账户私钥的落库加密：AES-256-GCM，每条记录独立 12 字节随机 IV。
 *
 * <p>私钥是这套系统里密级最高的机密——拿到某机构私钥就等于拿到该地址在合约里的全部权限，
 * 所以绝不明文落库：主密钥来自配置（生产用环境变量注入），密文与 IV 分开存列。
 * 更稳妥的做法是接 KMS/HSM，这里留出替换点（{@link #encrypt} / {@link #decrypt}）。
 */
@Component
public class AesGcmCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec keySpec;
    private final SecureRandom random = new SecureRandom();

    public AesGcmCipher(CryptoProperties properties) {
        this.keySpec = new SecretKeySpec(sha256(properties.getMasterKey().getBytes(StandardCharsets.UTF_8)), "AES");
    }

    public CipherText encrypt(String plainText) {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherBytes = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return new CipherText(
                    Base64.getEncoder().encodeToString(cipherBytes),
                    Base64.getEncoder().encodeToString(iv));
        } catch (Exception e) {
            throw new IllegalStateException("私钥加密失败: " + e.getMessage(), e);
        }
    }

    public String decrypt(String cipherBase64, String ivBase64) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keySpec,
                    new GCMParameterSpec(TAG_BITS, Base64.getDecoder().decode(ivBase64)));
            byte[] plain = cipher.doFinal(Base64.getDecoder().decode(cipherBase64));
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("私钥解密失败（主密钥是否被更改过？）: " + e.getMessage(), e);
        }
    }

    private byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    @Data
    @AllArgsConstructor
    public static class CipherText {
        private String cipherBase64;
        private String ivBase64;
    }
}
