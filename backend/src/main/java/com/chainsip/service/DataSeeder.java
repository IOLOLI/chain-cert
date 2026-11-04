package com.chainsip.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chainsip.config.AdminKeyBootstrap;
import com.chainsip.entity.Account;
import com.chainsip.mapper.AccountMapper;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 空库引导：只在数据库为空时创建平台<b>唯一的管理员账号</b>（admin）。
 *
 * <p><b>不再预置任何演示机构密钥与演示账号</b>。机构链账户按业务层级创建：
 * <ul>
 *   <li>管理员创建社保局账号、社保局创建缴费单位账号时，后端会为它们<b>自动生成机构链账户</b>
 *       （私钥用 AES-256-GCM 加密落库，接口不返回明文）；</li>
 *   <li>职工账号也会自动生成一把属于自己的链上密钥（编码 {@code EMP_<6位随机大写十六进制>}），
 *       地址在开号时就有了，职工不需要绑定钱包、也不需要手输地址；</li>
 *   <li>ADMIN 自己的机构链账户来自配置项 {@code chainsip.crypto.admin-private-key}
 *       （见 {@link AdminKeyBootstrap}）——通常填"用控制台/WeBASE 部署合约的那个账户"的私钥，
 *       这样链上第一个 ADMIN 与链下后端用的是同一个地址，不需要 addAdmin 交接。</li>
 * </ul>
 *
 * <p>账号创建完后本组件还会做一次<b>统一补绑</b>（{@code AuthService.backfillPlatformBindings()}）：
 * 把每一把已有机构链账户的地址写进 {@code cs_wallet} 并绑定到使用该编码的账号——平台托管的地址
 * 不需要用户验签，因此"钱包绑定"不再是必经步骤。
 *
 * <p>账号密码以 PBKDF2 派生值存进 MySQL 的 {@code cs_account} 表（<b>不存明文、也不用 H2</b>）；
 * 首次登录后可用 {@code POST /api/admin/accounts/{id}/password} 改密码。
 */
@Component
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    /** 初始管理员账号与密码（仅空库时创建一次；可用环境变量覆盖，登录后应立即修改）。 */
    @Value("${chainsip.bootstrap.admin-username:admin}")
    private String bootstrapAdmin;
    @Value("${chainsip.bootstrap.admin-password:ChangeMe_123456}")
    private String bootstrapAdminPassword;

    private final AccountMapper accountMapper;
    private final AuthService authService;

    public DataSeeder(AccountMapper accountMapper, AuthService authService) {
        this.accountMapper = accountMapper;
        this.authService = authService;
    }

    @Override
    public void run(ApplicationArguments args) {
        Long accounts = accountMapper.selectCount(new LambdaQueryWrapper<Account>());
        if (accounts != null && accounts > 0) {
            log.info("已存在 {} 个账号，跳过管理员账号初始化", accounts);
        } else {
            createBootstrapAdmin();
        }

        // 统一补绑：把所有已有机构链账户的地址绑到使用该编码的账号上（幂等）。
        // 为什么放在这里而不是只放在创建路径上：AdminKeyBootstrap 早于本组件执行，
        // 首次启动（空库）时 admin 账号还不存在，那时它只能记日志；建完账号后在这里补一次。
        // 顺带把本功能上线前创建的老账号也补上，"钱包绑定"从此不再是必经步骤。
        try {
            authService.backfillPlatformBindings();
        } catch (Exception e) {
            log.warn("启动补绑账号与链上地址失败（不影响启动与登录）：{}", e.getMessage());
        }
    }

    private void createBootstrapAdmin() {
        authService.createAccount(bootstrapAdmin, bootstrapAdminPassword, AuthService.ROLE_ADMIN,
                "平台管理员", AuthService.ROLE_ADMIN);

        log.warn("========== 已创建平台管理员账号（仅空库时创建一次） ==========");
        log.warn("账号：{} / {}   （密码以 PBKDF2 派生值存放于 MySQL 的 cs_account 表，不存明文）",
                bootstrapAdmin, bootstrapAdminPassword);
        log.warn("改密码：POST /api/admin/accounts/{{id}}/password");
        log.warn("ADMIN 的机构链账户：请用配置 chainsip.crypto.admin-private-key（或环境变量 "
                + "CHAINSIP_ADMIN_PRIVATE_KEY）注入 —— 通常填「用控制台/WeBASE 部署合约的那个账户」的私钥，"
                + "链上第一个 ADMIN 就是它，链上链下即为同一个 ADMIN；未配置时 ADMIN 将无法发起交易。");
        log.warn("其余账号不预置：管理员创建社保局 → 社保局创建缴费单位 → 缴费单位创建职工"
                + "（前两级会自动生成各自的机构链账户，需由管理员在链上注册其地址）。");
        log.warn("==============================================================");
    }
}
