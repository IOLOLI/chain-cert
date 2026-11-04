package com.chainsip.config;

import com.chainsip.entity.Account;
import com.chainsip.entity.InstitutionKey;
import com.chainsip.service.AuthService;
import com.chainsip.service.InstitutionKeyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 用配置里的私钥替换 ADMIN 机构链账户。
 *
 * <p><b>解决什么问题</b>：合约里的第一个 ADMIN 是"部署合约时用的那个账户"。如果你用 FISCO 控制台部署，
 * 链上 ADMIN 就是控制台账户（如 {@code 0xc0b7ce…}），而后端默认用的是内置演示 ADMIN 私钥 ——
 * 两者不一致时，后端的 ADMIN 类操作（注册公司/社保局/监管、setTransferContract、注资）都会被回滚成 {@code NotAnAdmin}。
 *
 * <p>把控制台账户的私钥填到 {@code chainsip.crypto.admin-private-key}（或环境变量
 * {@code CHAINSIP_ADMIN_PRIVATE_KEY}）后，本组件在每次启动时把 {@code code=ADMIN} 的机构密钥换成它，
 * 于是链上链下是同一个 ADMIN，链上只需保留一个管理员。
 *
 * <p>执行顺序：{@link Ordered#HIGHEST_PRECEDENCE} + 1（仅晚于 {@link WalletSchemaMigration}
 * 那条补列的 DDL），仍早于 {@link com.chainsip.service.DataSeeder}，
 * 因此首次启动（空库）时演示播种也不会再用演示 ADMIN 私钥覆盖它（DataSeeder 里也加了同样的判断）。
 *
 * <p><b>地址绑定</b>：ADMIN 私钥也是平台托管的，因此这里会顺手把新的 ADMIN 地址绑定到
 * {@code admin} 账号（无需验签，见 {@code AuthService.bindPlatformAddress}）。但本组件可能跑在
 * {@code admin} 账号创建<b>之前</b>（首次启动的空库场景），那时只记日志，
 * 由 {@link com.chainsip.service.DataSeeder} 建完账号后调用
 * {@code AuthService.backfillPlatformBindings()} 统一补绑。
 *
 * <p>留空该配置时本组件什么都不做，保持数据库现状。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class AdminKeyBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminKeyBootstrap.class);

    /** 机构链账户编码，与 DataSeeder / cs_account.institution_code 保持一致。 */
    private static final String ADMIN_CODE = AuthService.ROLE_ADMIN;

    private final CryptoProperties cryptoProperties;
    private final InstitutionKeyService institutionKeyService;
    private final AuthService authService;

    public AdminKeyBootstrap(CryptoProperties cryptoProperties,
                             InstitutionKeyService institutionKeyService,
                             AuthService authService) {
        this.cryptoProperties = cryptoProperties;
        this.institutionKeyService = institutionKeyService;
        this.authService = authService;
    }

    @Override
    public void run(ApplicationArguments args) {
        String hex = normalize(cryptoProperties.getAdminPrivateKey());
        if (hex == null) {
            if (cryptoProperties.getAdminPrivateKey() != null
                    && !cryptoProperties.getAdminPrivateKey().trim().isEmpty()) {
                log.error("chainsip.crypto.admin-private-key 格式不对（应为 64 位十六进制，可带 0x 前缀），已忽略；"
                        + "ADMIN 机构链账户保持数据库现状");
            } else {
                log.info("未配置 chainsip.crypto.admin-private-key，ADMIN 机构链账户保持数据库现状");
            }
            return;
        }

        try {
            // 直接"删旧 + 写新"，天然幂等：每次启动都以配置里的私钥为准
            institutionKeyService.deleteByCode(ADMIN_CODE);
            InstitutionKey key = institutionKeyService.importKey(ADMIN_CODE, "平台管理员",
                    AuthService.ROLE_ADMIN, hex,
                    "由 chainsip.crypto.admin-private-key 注入（通常为控制台/WeBASE 的合约部署账户）");
            log.warn("ADMIN 机构链账户已按配置注入：address={}", key.getAddress());
            log.warn("请确认合约里的 ADMIN 就是这个地址（控制台里：call ChainSIP <核心地址> isAdmin {}）",
                    key.getAddress());
            bindAdminAccount(key);
        } catch (Exception e) {
            // 不让配置问题阻断整个应用启动：只报错，链上鉴权是否可用可通过 /api/chain/status 的 adminOnChainRole 观察
            log.error("按配置注入 ADMIN 机构私钥失败：{}；请检查 chainsip.crypto.admin-private-key 是否为合法的 64 位私钥，"
                    + "以及该地址是否已被登记为其他机构链账户（一个地址只能对应一个机构）", e.getMessage());
        }
    }

    /**
     * 把刚注入的 ADMIN 地址绑定到 {@code admin} 账号（平台托管，无需验签）。
     *
     * <p>本组件早于 {@link com.chainsip.service.DataSeeder}，首次启动（空库）时 {@code admin}
     * 账号<b>还不存在</b>，这里只能记日志；等 DataSeeder 建完账号会调用
     * {@code AuthService.backfillPlatformBindings()} 补绑一次。绑定失败同样不阻断启动。
     */
    private void bindAdminAccount(InstitutionKey key) {
        try {
            Account admin = authService.findByInstitutionCode(ADMIN_CODE);
            if (admin == null) {
                log.info("admin 账号尚未创建（DataSeeder 还没跑），ADMIN 地址 {} 的绑定稍后由 DataSeeder 补做",
                        key.getAddress());
                return;
            }
            authService.bindPlatformAddress(admin.getId(), key.getAddress(),
                    AuthService.AUTO_BIND_REMARK + "（ADMIN 私钥由配置注入）");
        } catch (Exception e) {
            log.warn("把 ADMIN 地址 {} 绑定到 admin 账号失败（不影响启动）：{}", key.getAddress(), e.getMessage());
        }
    }

    /** 归一化私钥：去空白/0x 前缀/大小写，校验 64 位十六进制；不合法返回 null。 */
    private String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().replaceAll("\\s", "");
        if (value.isEmpty()) {
            return null;
        }
        if (value.startsWith("0x") || value.startsWith("0X")) {
            value = value.substring(2);
        }
        value = value.toLowerCase();
        if (!value.matches("[0-9a-f]{64}")) {
            return null;
        }
        return value;
    }
}
