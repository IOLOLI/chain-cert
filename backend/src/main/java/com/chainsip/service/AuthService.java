package com.chainsip.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chainsip.chain.ChainGateway;
import com.chainsip.chain.dto.CallResult;
import com.chainsip.common.BizException;
import com.chainsip.common.ErrorCode;
import com.chainsip.config.ChainProperties;
import com.chainsip.config.JwtProperties;
import com.chainsip.crypto.PasswordHasher;
import com.chainsip.dto.AccountView;
import com.chainsip.dto.LoginRequest;
import com.chainsip.dto.LoginResponse;
import com.chainsip.entity.Account;
import com.chainsip.entity.InstitutionKey;
import com.chainsip.entity.LoginLog;
import com.chainsip.entity.Wallet;
import com.chainsip.mapper.AccountMapper;
import com.chainsip.mapper.LoginLogMapper;
import com.chainsip.mapper.WalletMapper;
import com.chainsip.security.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 认证与账号服务。
 *
 * <p>核心原则（详见 README）：<b>认证在链下，授权在链上</b>。
 * 账号、密码、地址绑定、会话全部留在本服务；合约只认交易签名里的地址。
 * 因此链下角色写错不会变成越权——交易会被合约按 msg.sender 拒绝。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_SPONSOR = "SPONSOR";
    public static final String ROLE_BUREAU = "BUREAU";
    public static final String ROLE_REGULATOR = "REGULATOR";
    public static final String ROLE_EMPLOYEE = "EMPLOYEE";

    private static final String[] ROLE_NAMES = {"NONE", "ADMIN", "BUREAU", "SPONSOR", "REGULATOR"};

    /**
     * 平台托管地址的自动绑定来源备注。
     *
     * <p>链上地址（机构链账户 / 职工 {@code EMP_} 密钥）的私钥由平台在创建账号时生成并托管，
     * 用户本来就不需要签名证明"这个地址是我的"，因此创建账号时直接写入 {@code cs_wallet}
     * （{@code verified=true}）。平台已不提供手动绑定，这条自动绑定是唯一的绑定来源。
     */
    public static final String AUTO_BIND_REMARK = "创建账号时自动生成并绑定";

    private final AccountMapper accountMapper;
    private final WalletMapper walletMapper;
    private final LoginLogMapper loginLogMapper;
    private final PasswordHasher passwordHasher;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final ChainGateway chainGateway;
    private final ChainProperties chainProperties;
    private final InstitutionKeyService institutionKeyService;

    public AuthService(AccountMapper accountMapper, WalletMapper walletMapper, LoginLogMapper loginLogMapper,
                       PasswordHasher passwordHasher, JwtService jwtService, JwtProperties jwtProperties,
                       ChainGateway chainGateway, ChainProperties chainProperties,
                       InstitutionKeyService institutionKeyService) {
        this.accountMapper = accountMapper;
        this.walletMapper = walletMapper;
        this.loginLogMapper = loginLogMapper;
        this.passwordHasher = passwordHasher;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
        this.chainGateway = chainGateway;
        this.chainProperties = chainProperties;
        this.institutionKeyService = institutionKeyService;
    }

    // ------------------------------------------------------------------
    // 账号 / 登录
    // ------------------------------------------------------------------

    /**
     * 建账号（ADMIN 开通机构账号时也用这个方法）。
     *
     * <p>没有"自助注册"：账号一律由上级按层级创建（ADMIN → BUREAU/SPONSOR/REGULATOR、
     * BUREAU → SPONSOR、SPONSOR → EMPLOYEE）。
     */
    @Transactional
    public Account createAccount(String username, String rawPassword, String role,
                                 String displayName, String institutionCode) {
        return createAccount(username, rawPassword, role, displayName, institutionCode, null);
    }

    /**
     * 建账号（带"创建者账号 id"）。
     *
     * <p>{@code createdBy} 写进 {@code cs_account.created_by}：层级开号时由
     * {@code AccountHierarchyService.doCreate} 传入当前操作者的账号 id；系统初始化（{@code DataSeeder}
     * 建 bootstrap admin）没有创建者，传 {@code null}。该列用于机构账号列表按登录者范围收口。
     */
    @Transactional
    public Account createAccount(String username, String rawPassword, String role,
                                 String displayName, String institutionCode, Long createdBy) {
        if (findByUsername(username) != null) {
            throw new BizException(ErrorCode.ACCOUNT_EXISTS, "用户名已存在：" + username);
        }
        Account account = new Account();
        account.setUsername(username);
        account.setPasswordHash(passwordHasher.hash(rawPassword));
        account.setDisplayName(displayName == null || displayName.isEmpty() ? username : displayName);
        account.setRole(role);
        account.setInstitutionCode(institutionCode);
        account.setStatus("ENABLED");
        account.setCreatedBy(createdBy);
        account.setCreatedAt(LocalDateTime.now());
        account.setUpdatedAt(LocalDateTime.now());
        accountMapper.insert(account);
        log.info("创建账号：{}（角色 {}，创建者账号 id={}）", username, role, createdBy);
        return account;
    }

    /** 按角色取账号实体（链下范围收口由调用方做，例如"只留本单位创建的"）。 */
    public List<Account> listAccountEntities(String role) {
        return accountMapper.selectList(new LambdaQueryWrapper<Account>()
                .eq(Account::getRole, role)
                .orderByAsc(Account::getId));
    }

    /**
     * 批量把账号实体转成视图（一次读机构密钥表，避免逐条查库的 N+1）。
     *
     * <p>{@code withOnChainRole=false}：列表接口刻意不查链，链未就绪也能打开，
     * 但 {@code chainAddress} 仍会带上（它来自链下机构密钥表）。
     */
    public List<AccountView> toViews(List<Account> accounts) {
        Map<String, String> addressByCode = institutionKeyService.addressByCode();
        List<AccountView> views = new ArrayList<>();
        for (Account account : accounts) {
            views.add(toView(account, false, addressByCode));
        }
        return views;
    }

    public LoginResponse login(LoginRequest request, String clientIp, String userAgent) {
        Account account = findByUsername(request.getUsername());
        if (account == null) {
            writeLoginLog(null, request.getUsername(), null, clientIp, userAgent, false, "账号不存在");
            throw new BizException(ErrorCode.PASSWORD_ERROR, "用户名或密码错误");
        }
        if (!passwordHasher.matches(request.getPassword(), account.getPasswordHash())) {
            writeLoginLog(account.getId(), account.getUsername(), null, clientIp, userAgent, false, "密码错误");
            throw new BizException(ErrorCode.PASSWORD_ERROR, "用户名或密码错误");
        }
        if (!"ENABLED".equalsIgnoreCase(account.getStatus())) {
            writeLoginLog(account.getId(), account.getUsername(), null, clientIp, userAgent, false, "账号已禁用");
            throw new BizException(ErrorCode.ACCOUNT_DISABLED);
        }

        writeLoginLog(account.getId(), account.getUsername(), null, clientIp, userAgent, true, "登录成功");

        LoginResponse response = new LoginResponse();
        response.setToken(jwtService.issue(account));
        response.setExpiresInSeconds(jwtProperties.getExpireMinutes() * 60);
        response.setAccount(toView(account, true));
        response.setWallets(response.getAccount().getWallets());
        return response;
    }

    // ------------------------------------------------------------------
    // 地址（一律由平台自动绑定）
    // ------------------------------------------------------------------

    public List<Wallet> walletsOf(Long accountId) {
        return walletMapper.selectList(new LambdaQueryWrapper<Wallet>()
                .eq(Wallet::getAccountId, accountId)
                .orderByAsc(Wallet::getId));
    }

    // ------------------------------------------------------------------
    // 平台托管地址的自动绑定（无需验签）
    // ------------------------------------------------------------------

    /**
     * 把<b>平台自己生成并持有私钥</b>的链上地址自动绑定到账号。
     *
     * <p><b>为什么不验签</b>：传进来的地址是后端在创建账号时用
     * {@link InstitutionKeyService#generate} 现生成的，私钥以密文落在
     * {@code cs_institution_key}，平台自己就持有它——用户无法、也无需再签一次名。
     * 平台也没有"用户自带地址"这条路径，所有链上地址都来自这里。
     *
     * <p>幂等与冲突：
     * <ul>
     *   <li>同一账号下该地址已存在 → 直接返回旧记录，不重复插入；</li>
     *   <li>该地址已被<b>别的</b>账号绑定 → 只记 warn 并返回 null（不去改写别人的绑定，
     *       因为 {@code cs_wallet} 上有 {@code unique(address, group_id)} 约束）；</li>
     *   <li>地址为空 → 只记 warn 并返回 null（账号照常创建）。</li>
     * </ul>
     *
     * @param remark 绑定来源备注，写进 {@code cs_wallet.remark}（如"创建账号时自动生成并绑定"）
     * @return 绑定记录；跳过绑定时返回 null
     */
    @Transactional
    public Wallet bindPlatformAddress(Long accountId, String address, String remark) {
        if (accountId == null) {
            log.warn("跳过平台地址自动绑定：账号 id 为空（address={}）", address);
            return null;
        }
        String normalized = normalizeAddress(address);
        if (normalized == null) {
            log.warn("跳过平台地址自动绑定：账号 {} 没有可绑定的链上地址（机构密钥缺失？）", accountId);
            return null;
        }

        // 幂等：同一账号下该地址已绑过就不再插入（启动补绑 / 重复调用都会走到这里）
        List<Wallet> sameAccount = walletMapper.selectList(new LambdaQueryWrapper<Wallet>()
                .eq(Wallet::getAccountId, accountId)
                .eq(Wallet::getAddress, normalized));
        if (!sameAccount.isEmpty()) {
            log.info("账号 {} 的链上地址 {} 已绑定，跳过重复绑定", accountId, normalized);
            return sameAccount.get(0);
        }

        // 该地址已被其他账号占用：不动别人的绑定，只留痕
        List<Wallet> otherAccount = walletMapper.selectList(new LambdaQueryWrapper<Wallet>()
                .eq(Wallet::getAddress, normalized));
        if (!otherAccount.isEmpty()) {
            log.warn("链上地址 {} 已绑定到账号 {}，账号 {} 不再重复绑定",
                    normalized, otherAccount.get(0).getAccountId(), accountId);
            return null;
        }

        Wallet wallet = new Wallet();
        wallet.setAccountId(accountId);
        wallet.setAddress(normalized);
        // 2.x 的群组号是整数（BcosSDK.getClient(groupId)），列用 String 存，便于将来放群组别名
        wallet.setGroupId(String.valueOf(chainProperties.getGroupId()));
        wallet.setVerified(true);
        // 该账号第一个绑定的地址即主地址
        wallet.setPrimaryWallet(walletsOf(accountId).isEmpty());
        wallet.setBoundAt(LocalDateTime.now());
        wallet.setCreatedAt(LocalDateTime.now());
        wallet.setRemark(remark);
        walletMapper.insert(wallet);
        log.info("账号 {} 自动绑定平台生成的链上地址 {}（verified=true, primaryWallet={}, 来源：{}）",
                accountId, normalized, wallet.getPrimaryWallet(), remark);
        return wallet;
    }

    /**
     * 启动时补绑：把 {@code cs_institution_key} 里已有的机构链账户地址补绑到使用该编码的账号。
     *
     * <p>覆盖两种"账号创建时来不及绑"的历史情况：
     * <ol>
     *   <li>本功能上线前创建的老账号（{@code cs_wallet} 里没有它们自动生成的地址）；</li>
     *   <li>{@link com.chainsip.config.AdminKeyBootstrap} 早于 {@link DataSeeder} 执行，
     *       首次启动时 {@code admin} 账号还不存在，只能等 DataSeeder 建号后在这里补绑。</li>
     * </ol>
     * 全程幂等（{@link #bindPlatformAddress} 自己判重），任何一条失败只记日志，不阻断启动。
     *
     * @return 本次新写入的绑定条数
     */
    @Transactional
    public int backfillPlatformBindings() {
        int bound = 0;
        for (InstitutionKey key : institutionKeyService.listAll()) {
            String address = normalizeAddress(key.getAddress());
            if (key.getCode() == null || address == null) {
                continue;
            }
            Account account = findByInstitutionCode(key.getCode());
            if (account == null) {
                log.debug("机构编码 {} 暂无对应账号，跳过启动补绑", key.getCode());
                continue;
            }
            boolean alreadyBound = false;
            for (Wallet wallet : walletsOf(account.getId())) {
                if (address.equals(wallet.getAddress())) {
                    alreadyBound = true;
                    break;
                }
            }
            try {
                Wallet wallet = bindPlatformAddress(account.getId(), address,
                        AUTO_BIND_REMARK + "（启动补绑，机构编码 " + key.getCode() + "）");
                if (wallet != null && !alreadyBound) {
                    bound++;
                }
            } catch (Exception e) {
                log.warn("补绑机构编码 {} 的地址 {} 失败（不影响启动）：{}",
                        key.getCode(), address, e.getMessage());
            }
        }
        if (bound > 0) {
            log.info("启动补绑完成：本次新增 {} 条账号 ↔ 地址绑定记录", bound);
        }
        return bound;
    }

    /** 地址归一化（去空白 + 小写，落库口径一致）；空值返回 null。 */
    private static String normalizeAddress(String address) {
        if (address == null) {
            return null;
        }
        String normalized = address.trim().toLowerCase();
        return normalized.isEmpty() ? null : normalized;
    }

    /** 按机构编码取账号（一个编码理论上只对应一个账号；历史脏数据取最早的那个）。 */
    public Account findByInstitutionCode(String institutionCode) {
        if (institutionCode == null || institutionCode.trim().isEmpty()) {
            return null;
        }
        List<Account> accounts = accountMapper.selectList(new LambdaQueryWrapper<Account>()
                .eq(Account::getInstitutionCode, institutionCode.trim())
                .orderByAsc(Account::getId));
        return accounts.isEmpty() ? null : accounts.get(0);
    }

    /** 取该账号已验签的主地址（发交易时如不需要机构私钥，可用它做身份标识）。 */
    public String primaryVerifiedAddress(Long accountId) {
        for (Wallet wallet : walletsOf(accountId)) {
            if (Boolean.TRUE.equals(wallet.getVerified())) {
                return wallet.getAddress();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 账号维护
    // ------------------------------------------------------------------

    public Account requireAccount(Long accountId) {
        Account account = accountMapper.selectById(accountId);
        if (account == null) {
            throw new BizException(ErrorCode.ACCOUNT_NOT_FOUND);
        }
        return account;
    }

    public Account findByUsername(String username) {
        return accountMapper.selectOne(new LambdaQueryWrapper<Account>().eq(Account::getUsername, username));
    }

    public List<AccountView> listAccounts() {
        List<AccountView> views = new ArrayList<>();
        Map<String, String> addressByCode = institutionKeyService.addressByCode();
        for (Account account : accountMapper.selectList(
                new LambdaQueryWrapper<Account>().orderByAsc(Account::getId))) {
            views.add(toView(account, false, addressByCode));
        }
        return views;
    }

    /**
     * 账号列表 + 每行的链上角色（管理页「机构与账号」用）。
     *
     * <p>与 {@link #toView(Account, boolean)} 的 {@code withOnChainRole=true} 不同：这里只按
     * <b>账号自己的机构链地址</b>查一次 {@code roleOf}，不逐钱包展开。列表页要回答的是
     * "这个账号在链上有没有角色"，不是"它绑定的每个地址各自是什么角色"。
     *
     * <p>逐行的容错由 {@link #onChainRole(String)} 保证：链未就绪、单行读链失败都只让该行
     * {@code onChainRole=null}，列表照常返回。前端把 null 渲染成「—」并单独说明原因，
     * 不再把"没查"写成"链未就绪"。
     */
    public List<AccountView> listAccountsWithChainRole() {
        List<AccountView> views = listAccounts();
        for (AccountView view : views) {
            String address = view.getChainAddress();
            view.setOnChainRole(address == null || address.trim().isEmpty() ? null : onChainRole(address));
        }
        return views;
    }

    /**
     * 按角色列账号（层级视图用：社保局列自己辖区内的缴费单位、缴费单位列自己的职工）。
     *
     * <p>{@code withOnChainRole=false} 是刻意的：列表接口不查链，避免链未就绪时列表打不开。
     * 但 {@code chainAddress} 仍会带上（它来自链下机构密钥表，不查链），前端列表可直接显示地址。
     */
    public List<AccountView> listAccounts(String role) {
        List<AccountView> views = new ArrayList<>();
        Map<String, String> addressByCode = institutionKeyService.addressByCode();
        for (Account account : accountMapper.selectList(new LambdaQueryWrapper<Account>()
                .eq(Account::getRole, role)
                .orderByAsc(Account::getId))) {
            views.add(toView(account, false, addressByCode));
        }
        return views;
    }

    @Transactional
    public void updateStatus(Long accountId, String status) {
        Account account = requireAccount(accountId);
        account.setStatus(status);
        account.setUpdatedAt(LocalDateTime.now());
        accountMapper.updateById(account);
    }

    @Transactional
    public void resetPassword(Long accountId, String newPassword) {
        Account account = requireAccount(accountId);
        account.setPasswordHash(passwordHasher.hash(newPassword));
        account.setUpdatedAt(LocalDateTime.now());
        accountMapper.updateById(account);
    }

    /**
     * 登录用户<b>自助</b>修改密码：先验原密码，再换新密码。
     *
     * <p>与管理员重置（{@link #resetPassword}）的区别：这里必须提供并校验<b>原密码</b>，
     * 而且只允许改自己的账号（{@code accountId} 由 JWT 里的登录态提供，请求体里没有账号标识）。
     *
     * <p>密码派生复用同一套 {@link PasswordHasher}（PBKDF2-HMAC-SHA256 + 随机盐，盐与迭代次数
     * 都写在 {@code password_hash} 这一列里），因此这里只更新该列与 {@code updated_at}。
     * 日志只记账号名，<b>不打印任何密码</b>。
     */
    @Transactional
    public void changePassword(Long accountId, String oldPassword, String newPassword) {
        Account account = requireAccount(accountId);
        if (!passwordHasher.matches(oldPassword, account.getPasswordHash())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "原密码不正确");
        }
        if (newPassword == null || newPassword.length() < 6) {
            throw new BizException(ErrorCode.BAD_REQUEST, "新密码至少 6 位");
        }
        if (newPassword.equals(oldPassword)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "新密码不能与原密码相同");
        }
        account.setPasswordHash(passwordHasher.hash(newPassword));
        account.setUpdatedAt(LocalDateTime.now());
        accountMapper.updateById(account);
        log.info("账号 {} 自助修改密码成功", account.getUsername());
    }

    /**
     * 登录用户<b>自助</b>修改账号信息：改用户名 / 显示名，必须先用<b>当前密码</b>验证。
     *
     * <p>只改自己：{@code accountId} 来自 JWT 登录态，请求体里没有账号标识。
     * 两个新字段留空即表示不改；都留空直接拒绝（"没有需要修改的内容"）。
     *
     * <p>用户名要同时守住两件事：格式（3-32 位字母/数字/下划线）与 {@code cs_account} 上的
     * {@code uk_cs_account_username} 唯一约束 —— 先按用户名查重给出明确提示，写在库里时再兜一次
     * 唯一键冲突（"先查后写"之间仍可能被别人抢注，靠约束兜底）。
     *
     * <p>日志只记账号 id 与改了哪些字段，<b>不打印密码</b>。
     *
     * @return 更新后的账号（未发生实际改动时原样返回，不做无意义的写库）
     */
    @Transactional
    public Account changeProfile(Long accountId, String newUsername, String newDisplayName, String password) {
        Account account = requireAccount(accountId);
        if (!passwordHasher.matches(password, account.getPasswordHash())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "当前密码不正确");
        }

        String username = trimToNull(newUsername);
        String displayName = trimToNull(newDisplayName);
        if (username == null && displayName == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "没有需要修改的内容");
        }

        StringBuilder changed = new StringBuilder();
        if (username != null && !username.equals(account.getUsername())) {
            if (!username.matches("[A-Za-z0-9_]{3,32}")) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "用户名只能包含字母、数字和下划线，长度 3-32 位");
            }
            if (findByUsername(username) != null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "该用户名已被占用");
            }
            account.setUsername(username);
            changed.append("username");
        }
        if (displayName != null && !displayName.equals(account.getDisplayName())) {
            account.setDisplayName(displayName);
            changed.append(changed.length() == 0 ? "displayName" : ",displayName");
        }
        if (changed.length() == 0) {
            // 传了值但与现状完全一致：不算错，也不写库（避免无意义地刷新 updated_at）
            log.info("账号 {} 自助修改账号信息：无实际变化，跳过写库", accountId);
            return account;
        }

        account.setUpdatedAt(LocalDateTime.now());
        try {
            accountMapper.updateById(account);
        } catch (DataIntegrityViolationException e) {
            log.warn("修改账号 {} 的用户名触发唯一约束冲突：{}", accountId, e.getMessage());
            throw new BizException(ErrorCode.BAD_REQUEST, "该用户名已被占用");
        }
        log.info("账号 {} 自助修改账号信息：{}", accountId, changed);
        return account;
    }

    /** 去空白；空白串按"没传"处理。 */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Transactional
    public void changeRole(Long accountId, String role, String institutionCode) {
        Account account = requireAccount(accountId);
        account.setRole(role);
        account.setInstitutionCode(institutionCode);
        account.setUpdatedAt(LocalDateTime.now());
        accountMapper.updateById(account);
    }

    // ------------------------------------------------------------------
    // 视图组装
    // ------------------------------------------------------------------

    public AccountView toView(Account account, boolean withOnChainRole) {
        return toView(account, withOnChainRole, null);
    }

    /**
     * 组装账号视图。
     *
     * @param addressByCode 可选的"机构编码 → 链上地址"预载表（列表接口传一次，避免 N+1）；
     *                      传 null 时按账号的 institutionCode 单条查库
     */
    private AccountView toView(Account account, boolean withOnChainRole, Map<String, String> addressByCode) {
        AccountView view = new AccountView();
        view.setId(account.getId());
        view.setUsername(account.getUsername());
        view.setDisplayName(account.getDisplayName());
        view.setRole(account.getRole());
        view.setInstitutionCode(account.getInstitutionCode());
        view.setStatus(account.getStatus());
        view.setCreatedAt(account.getCreatedAt());
        // 账号自己的链上地址：机构账号 = 机构密钥地址，职工账号 = 创建时自动生成的 EMP_ 密钥地址。
        // 解析不到一律给空串（绝不抛异常），老数据 / 未登记的编码不会让 /auth/me 500。
        view.setChainAddress(chainAddressOf(account.getInstitutionCode(), addressByCode));

        List<AccountView.WalletView> wallets = new ArrayList<>();
        String primaryAddress = "";
        for (Wallet wallet : walletsOf(account.getId())) {
            AccountView.WalletView walletView = new AccountView.WalletView();
            walletView.setAddress(wallet.getAddress());
            walletView.setVerified(wallet.getVerified());
            walletView.setPrimaryWallet(wallet.getPrimaryWallet());
            walletView.setBoundAt(wallet.getBoundAt());
            if (withOnChainRole) {
                walletView.setOnChainRole(onChainRole(wallet.getAddress()));
            }
            wallets.add(walletView);
            // 与 primaryVerifiedAddress 同口径：第一条已验签的绑定地址
            if (primaryAddress.isEmpty() && Boolean.TRUE.equals(wallet.getVerified())
                    && wallet.getAddress() != null) {
                primaryAddress = wallet.getAddress();
            }
        }
        view.setWallets(wallets);
        view.setPrimaryAddress(primaryAddress);

        if (withOnChainRole) {
            // "账号的链上角色"必须和"账号的链上地址"同一个口径，否则会出现
            // 地址是机构密钥地址、角色却是另一个地址（甚至 NONE）的情况。
            // 原来的写法是：机构账号直接把 address 置空 —— 于是所有机构账号（含 ADMIN）的
            // onChainRole 恒为 null，顶栏永远显示"链上角色：NONE"。
            String address = account.getInstitutionCode() == null
                    ? primaryVerifiedAddress(account.getId())
                    : chainAddressOf(account.getInstitutionCode(), addressByCode);
            view.setOnChainRole(address == null || address.trim().isEmpty() ? null : onChainRole(address));
        }
        return view;
    }

    /**
     * 取某机构编码对应的链上地址；编码为空 / 未登记 / 地址为空时返回空串。
     *
     * <p>这是"账号自己的链上地址"的唯一出口：{@code GET /api/auth/me}、登录响应与账号列表都用它，
     * 保证三处口径完全一致。
     */
    private String chainAddressOf(String institutionCode, Map<String, String> addressByCode) {
        if (institutionCode == null || institutionCode.trim().isEmpty()) {
            return "";
        }
        String code = institutionCode.trim();
        try {
            if (addressByCode != null) {
                String address = addressByCode.get(code);
                return address == null ? "" : address;
            }
            InstitutionKey key = institutionKeyService.findByCode(code);
            return key == null || key.getAddress() == null ? "" : key.getAddress();
        } catch (Exception e) {
            // 查库失败也不让 /me 挂掉：地址缺省为空串
            log.warn("解析账号机构链地址失败：institutionCode={}，原因={}", code, e.getMessage());
            return "";
        }
    }

    /** 查链上角色；链未就绪时返回 null（不影响登录）。 */
    public String onChainRole(String address) {
        if (address == null || !chainGateway.isReady()) {
            return null;
        }
        try {
            CallResult result = chainGateway.call(ChainProperties.CORE, "roleOf",
                    java.util.Collections.singletonList(address));
            Object value = result.first();
            if (value instanceof Number) {
                int index = ((Number) value).intValue();
                return index >= 0 && index < ROLE_NAMES.length ? ROLE_NAMES[index] : "UNKNOWN";
            }
            return String.valueOf(value);
        } catch (BizException e) {
            log.debug("查询链上角色失败：{}", e.getMessage());
            return null;
        }
    }

    private void writeLoginLog(Long accountId, String username, String address,
                               String clientIp, String userAgent, boolean success, String message) {
        try {
            LoginLog entity = new LoginLog();
            entity.setAccountId(accountId);
            entity.setUsername(username);
            entity.setAddress(address);
            entity.setClientIp(clientIp);
            entity.setUserAgent(userAgent == null ? null : userAgent.substring(0, Math.min(300, userAgent.length())));
            entity.setSuccess(success);
            entity.setMessage(message);
            entity.setCreatedAt(LocalDateTime.now());
            loginLogMapper.insert(entity);
        } catch (Exception e) {
            log.warn("写登录日志失败：{}", e.getMessage());
        }
    }
}
