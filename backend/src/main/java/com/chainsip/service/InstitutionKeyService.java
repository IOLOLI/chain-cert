package com.chainsip.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chainsip.chain.dto.ChainAccount;
import com.chainsip.common.BizException;
import com.chainsip.common.ErrorCode;
import com.chainsip.crypto.AesGcmCipher;
import com.chainsip.crypto.WalletSignatureVerifier;
import com.chainsip.entity.InstitutionKey;
import com.chainsip.mapper.InstitutionKeyMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 机构链账户管理：生成/导入机构私钥（AES-256-GCM 加密落库），并按机构编码取出可发交易的链账户。
 *
 * <p>这是整套系统里密级最高的一块：拿到某机构私钥 = 拿到该地址在合约里的全部权限。
 * 因此：私钥只以密文落库、绝不出现在任何接口响应里、只在发交易瞬间于内存中解密。
 */
@Service
public class InstitutionKeyService {

    private static final Logger log = LoggerFactory.getLogger(InstitutionKeyService.class);

    /** 职工链上密钥的编码前缀：编码形如 {@code EMP_AB12CD}（与 SPONSOR_ / BUREAU_ 同风格）。 */
    public static final String PREFIX_EMPLOYEE = "EMP";

    /** 编码后缀用的十六进制字母表。 */
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    /** 生成编码后缀的随机源。 */
    private final SecureRandom random = new SecureRandom();

    private final InstitutionKeyMapper mapper;
    private final AesGcmCipher cipher;
    private final WalletSignatureVerifier signatureVerifier;

    public InstitutionKeyService(InstitutionKeyMapper mapper, AesGcmCipher cipher,
                                 WalletSignatureVerifier signatureVerifier) {
        this.mapper = mapper;
        this.cipher = cipher;
        this.signatureVerifier = signatureVerifier;
    }

    /** 随机生成一把新私钥并加密落库。 */
    @Transactional
    public InstitutionKey generate(String code, String name, String role, String remark) {
        WalletSignatureVerifier.NewKeyPair keyPair = signatureVerifier.generateKeyPair();
        return save(code, name, role, keyPair.getAddress(), keyPair.getPrivateKey(), remark);
    }

    /** 导入已有的机构私钥（例如部署合约的那个 ADMIN 私钥）。 */
    @Transactional
    public InstitutionKey importKey(String code, String name, String role, String privateKeyHex, String remark) {
        String address = signatureVerifier.addressOf(privateKeyHex);
        return save(code, name, role, address, privateKeyHex, remark);
    }

    private InstitutionKey save(String code, String name, String role, String address,
                                String privateKeyHex, String remark) {
        InstitutionKey existing = findByCode(code);
        if (existing != null) {
            throw new BizException(ErrorCode.CONFLICT, "机构编码已存在：" + code);
        }
        InstitutionKey byAddress = mapper.selectOne(
                new LambdaQueryWrapper<InstitutionKey>().eq(InstitutionKey::getAddress, address));
        if (byAddress != null) {
            throw new BizException(ErrorCode.CONFLICT, "该地址已登记为机构链账户：" + address);
        }

        AesGcmCipher.CipherText encrypted = cipher.encrypt(privateKeyHex);
        InstitutionKey entity = new InstitutionKey();
        entity.setCode(code);
        entity.setName(name);
        entity.setRole(role);
        entity.setAddress(address);
        entity.setPrivateKeyCipher(encrypted.getCipherBase64());
        entity.setIv(encrypted.getIvBase64());
        entity.setEnabled(true);
        entity.setRemark(remark);
        entity.setCreatedAt(LocalDateTime.now());
        mapper.insert(entity);
        log.info("登记机构链账户：code={}, role={}, address={}", code, role, address);
        return entity;
    }

    public InstitutionKey findByCode(String code) {
        if (code == null) {
            return null;
        }
        return mapper.selectOne(new LambdaQueryWrapper<InstitutionKey>().eq(InstitutionKey::getCode, code));
    }

    /**
     * 生成一个尚未使用的链上密钥编码：{@code <prefix>_<6位随机大写十六进制>}。
     *
     * <p>碰撞（编码已存在）时自动重试，连续 8 次仍冲突才报错——这与原先
     * {@code AccountHierarchyService} 里的口径一致，现在统一放在这里，避免两处各写一份格式。
     */
    public String nextCode(String prefix) {
        String normalized = prefix == null || prefix.trim().isEmpty() ? PREFIX_EMPLOYEE : prefix.trim();
        for (int attempt = 0; attempt < 8; attempt++) {
            StringBuilder suffix = new StringBuilder(6);
            for (int i = 0; i < 6; i++) {
                suffix.append(HEX[random.nextInt(HEX.length)]);
            }
            String code = normalized + "_" + suffix;
            if (findByCode(code) == null) {
                return code;
            }
        }
        throw new BizException(ErrorCode.INTERNAL_ERROR, "生成 " + normalized + " 编码失败（连续 8 次冲突），请重试");
    }

    /** 删除机构链账户（用于"用配置里的私钥替换 ADMIN 机构密钥"，见 AdminKeyBootstrap）。 */
    public void deleteByCode(String code) {
        InstitutionKey existing = findByCode(code);
        if (existing != null) {
            mapper.deleteById(existing.getId());
            log.warn("已删除机构链账户：code={}, address={}", code, existing.getAddress());
        }
    }

    public InstitutionKey requireByCode(String code) {
        InstitutionKey key = findByCode(code);
        if (key == null) {
            throw new BizException(ErrorCode.INSTITUTION_KEY_NOT_FOUND, "未登记机构链账户：" + code);
        }
        if (Boolean.FALSE.equals(key.getEnabled())) {
            throw new BizException(ErrorCode.INSTITUTION_KEY_NOT_FOUND, "机构链账户已停用：" + code);
        }
        return key;
    }

    /** 取出可用于发交易的链账户（私钥在此解密，只活在内存里）。 */
    public ChainAccount resolveChainAccount(String institutionCode) {
        InstitutionKey key = requireByCode(institutionCode);
        String privateKey = cipher.decrypt(key.getPrivateKeyCipher(), key.getIv());
        return new ChainAccount(key.getCode(), key.getAddress(), privateKey);
    }

    public List<InstitutionKey> listAll() {
        return mapper.selectList(new LambdaQueryWrapper<InstitutionKey>().orderByAsc(InstitutionKey::getId));
    }

    /**
     * 全部 {@code code → address} 映射（只取这两列，不把私钥密文读进内存）。
     *
     * <p>用于"账号列表一次性补齐 chainAddress"：列表接口按账号逐条查库会变成 N+1，
     * 这里一次查全表再在内存里查表，代价固定且不触碰密文。
     */
    public Map<String, String> addressByCode() {
        List<InstitutionKey> keys = mapper.selectList(new LambdaQueryWrapper<InstitutionKey>()
                .select(InstitutionKey::getCode, InstitutionKey::getAddress));
        Map<String, String> map = new LinkedHashMap<>();
        for (InstitutionKey key : keys) {
            if (key.getCode() != null) {
                map.put(key.getCode(), key.getAddress() == null ? "" : key.getAddress());
            }
        }
        return map;
    }

    public void setEnabled(String code, boolean enabled) {
        InstitutionKey key = requireByCode(code);
        key.setEnabled(enabled);
        mapper.updateById(key);
    }

    /** 对外视图：绝不包含私钥密文与 IV。 */
    public static class View {
        public String code;
        public String name;
        public String role;
        public String address;
        public Boolean enabled;
        public String remark;

        public static View of(InstitutionKey key) {
            View view = new View();
            view.code = key.getCode();
            view.name = key.getName();
            view.role = key.getRole();
            view.address = key.getAddress();
            view.enabled = key.getEnabled();
            view.remark = key.getRemark();
            return view;
        }
    }
}
