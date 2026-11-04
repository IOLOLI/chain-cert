package com.chainsip.service;

import com.chainsip.chain.dto.CallResult;
import com.chainsip.common.BizException;
import com.chainsip.common.ContractErrorMessages;
import com.chainsip.common.ErrorCode;
import com.chainsip.config.ChainProperties;
import com.chainsip.dto.AccountCreateResult;
import com.chainsip.dto.AccountView;
import com.chainsip.dto.Requests;
import com.chainsip.entity.Account;
import com.chainsip.entity.InstitutionKey;
import com.chainsip.security.AuthContext;
import com.chainsip.security.AuthUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 账号开通的层级模型：<b>管理员 → 社保局 → 缴费单位 → 职工</b>。
 *
 * <p>之前只有一个 {@code POST /api/admin/accounts}，谁开通谁全靠 ADMIN。现在按业务层级放开：
 * <ul>
 *   <li>ADMIN → {ADMIN, BUREAU, SPONSOR, REGULATOR, EMPLOYEE}</li>
 *   <li>BUREAU → {SPONSOR}（缴费单位的城市<b>自动继承</b>该社保局自己注册的服务城市）</li>
 *   <li>SPONSOR → {EMPLOYEE}</li>
 * </ul>
 *
 * <p><b>链上注册一律由 ADMIN 署名</b>：合约里 {@code addSponsorAccount} / {@code addBureaus}
 * / {@code addRegulatorAccount} 都带 {@code onlyAdmin} 修饰，社保局自己发交易也会被回滚。
 * 所以本服务负责"链下开账号 + 生成机构链账户（私钥）"，注册交易则固定用 ADMIN 私钥代签；
 * 返回体里带上 {@code chainAddress}、{@code onChainRegistered} 与 {@code hint}。
 *
 * <p><b>职工也有自己的链上地址</b>：{@code createEmployeeBySponsor} 会为职工自动生成一把
 * {@code EMP_<6位随机大写十六进制>} 的链上密钥，返回体里同样带上 {@code institutionCode} 与
 * {@code chainAddress}。职工因此无需绑定钱包、也无需手输地址：单位用该地址 {@code addEmployee}
 * 建档后，职工本人就能用它查账户、发起转移。
 *
 * <p><b>两处链上写操作，署名都是 ADMIN</b>：<ol>
 *   <li>{@code createByAdmin} 里当前登录账号就是 ADMIN，用它自己的私钥注册；</li>
 *   <li>社保局创建缴费单位后，由后端 {@link AdminBizService#addSponsorAccountAsAdmin}
 *       用 ADMIN 私钥<b>代签</b> {@code addSponsorAccount}，做到"创建即上链"
 *       （社保局不是 ADMIN，自己发必然被合约回滚）。</li>
 * </ol>
 * 两处都遵循同一条铁律：<b>链上失败绝不让开号失败</b>——失败只写
 * {@code onChainRegistered=false} + {@code onChainMessage}（含真实错误），该机构随后出现在
 * {@code GET /api/admin/institutions/pending}，由管理员补录。
 *
 * <p><b>缴费单位的城市不再手填</b>：开号前先只读 {@code getBureau(社保局地址).cities}，按
 * {@link #resolveBureauCity} 的规则得出唯一城市（单城市自动带出、多城市要求指定、没有则明确报错）；
 * 链读不通时只在"请求也没给 city"的情况下报错，绝不因为链的问题白白阻断开号。
 */
@Service
public class AccountHierarchyService {

    private static final Logger log = LoggerFactory.getLogger(AccountHierarchyService.class);

    /** 允许的"谁创建谁"关系：创建者角色 → 可创建的目标角色集合。 */
    private static final Map<String, Set<String>> ALLOWED_TARGETS;

    /**
     * <b>机构</b>角色（需要机构链账户、且编码由调用方按需指定或复用）。
     *
     * <p>职工不在这里：职工也会自动生成一把链上密钥，但编码固定为 {@code EMP_<6位随机大写十六进制>}，
     * 且永不接受调用方指定编码（见 {@code doCreate} 的 EMPLOYEE 分支）。
     */
    private static final Set<String> INSTITUTION_ROLES;

    /** 全部已知角色。 */
    private static final Set<String> ALL_ROLES;

    /** 需要链上注册的角色 → 判定"已注册"时链上应有的角色枚举序号（见合约 Roles.Role）。 */
    private static final Map<String, Integer> ON_CHAIN_ROLE_INDEX;

    /** 自动注册时缺少必要字段的提示语（前端按这句展示"去补录"）。 */
    private static final String MISSING_FIELDS_MESSAGE = "缺少名称/城市，请到「待上链注册」补";

    /** 社保局创建缴费单位时缺名称/城市的提示语（创建即上链路径，措辞与「机构管理」菜单对齐）。 */
    private static final String SPONSOR_MISSING_FIELDS_MESSAGE = "缺少单位名称/城市，请到「机构管理 → 待上链注册」补";

    /** 缴费单位链上注册未完成时，给用户的下一步提示。 */
    private static final String SPONSOR_PENDING_HINT =
            "可到「机构管理 → 待上链注册」补注册（等价于 addSponsorAccount）；补注册后该单位才能为职工建档并缴费";

    /** 无需链上注册的角色的提示语。 */
    private static final String NO_REGISTRATION_NEEDED = "无需链上注册";

    /** 链上角色枚举里 SPONSOR 的序号（合约 Roles.Role：0 NONE / 1 ADMIN / 2 BUREAU / 3 SPONSOR / 4 REGULATOR）。 */
    private static final int ON_CHAIN_SPONSOR_INDEX = 3;

    static {
        Map<String, Set<String>> allowed = new LinkedHashMap<>();
        allowed.put(AuthService.ROLE_ADMIN, new LinkedHashSet<>(Arrays.asList(
                AuthService.ROLE_ADMIN, AuthService.ROLE_BUREAU, AuthService.ROLE_SPONSOR,
                AuthService.ROLE_REGULATOR, AuthService.ROLE_EMPLOYEE)));
        allowed.put(AuthService.ROLE_BUREAU,
                new LinkedHashSet<>(Collections.singletonList(AuthService.ROLE_SPONSOR)));
        allowed.put(AuthService.ROLE_SPONSOR,
                new LinkedHashSet<>(Collections.singletonList(AuthService.ROLE_EMPLOYEE)));
        ALLOWED_TARGETS = Collections.unmodifiableMap(allowed);

        INSTITUTION_ROLES = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
                AuthService.ROLE_ADMIN, AuthService.ROLE_BUREAU,
                AuthService.ROLE_SPONSOR, AuthService.ROLE_REGULATOR)));

        ALL_ROLES = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
                AuthService.ROLE_ADMIN, AuthService.ROLE_BUREAU, AuthService.ROLE_SPONSOR,
                AuthService.ROLE_REGULATOR, AuthService.ROLE_EMPLOYEE)));

        Map<String, Integer> index = new LinkedHashMap<>();
        index.put(AuthService.ROLE_BUREAU, 2);
        index.put(AuthService.ROLE_SPONSOR, 3);
        index.put(AuthService.ROLE_REGULATOR, 4);
        ON_CHAIN_ROLE_INDEX = Collections.unmodifiableMap(index);
    }

    private final AuthService authService;
    private final InstitutionKeyService institutionKeyService;
    private final ChainTxService chainTxService;
    private final AdminBizService adminBizService;
    private final BureauCityService bureauCityService;

    public AccountHierarchyService(AuthService authService,
                                   InstitutionKeyService institutionKeyService,
                                   ChainTxService chainTxService,
                                   AdminBizService adminBizService,
                                   BureauCityService bureauCityService) {
        this.authService = authService;
        this.institutionKeyService = institutionKeyService;
        this.chainTxService = chainTxService;
        this.adminBizService = adminBizService;
        this.bureauCityService = bureauCityService;
    }

    // ------------------------------------------------------------------
    // 对外：三个层级的开通动作
    // ------------------------------------------------------------------

    /**
     * ADMIN 开通账号：可创建任意角色。
     *
     * <p>除了按角色自动准备机构链账户，现在还会<b>自动尝试链上注册</b>（合并原来"开账号"与
     * "管理员手工调 addBureaus / addSponsorAccount / addRegulatorAccount"两步）：
     * <ul>
     *   <li>BUREAU → 转移合约 {@code addBureaus}，成功后再 {@code changeBureausAuthorized(true)}；</li>
     *   <li>SPONSOR → 核心合约 {@code addSponsorAccount}；</li>
     *   <li>REGULATOR → 核心合约 {@code addRegulatorAccount}（agency 优先取 city，否则取 name）；</li>
     *   <li>ADMIN / EMPLOYEE → 不需要链上注册。</li>
     * </ul>
     *
     * <p><b>链上失败绝不让开号失败</b>：算法是"先建号建私钥，再尽力注册"，注册过程整体 try/catch，
     * 失败只写 {@code onChainRegistered=false} + {@code onChainMessage}（含真实错误），
     * 该机构随后会出现在 {@code GET /api/admin/institutions/pending}，由管理员补录。
     */
    @Transactional
    public AccountCreateResult createByAdmin(Requests.AccountCreate request) {
        String role = normalizeRole(request.getRole());
        assertCanCreate(AuthService.ROLE_ADMIN, role);

        // 名称：优先 name，回退 displayName（与 doCreate 里机构密钥命名的口径一致）
        String name = firstNonBlank(request.getName(), request.getDisplayName(), request.getUsername());
        String city = trimToNull(request.getCity());

        // 社保局与城市是一对一（转移合约里的 cityBureau 映射）。开号前先查一次占用，
        // 把冲突挡在"建账号 / 建机构私钥"之前：否则账号会先建好，链上注册才因
        // CityAlreadyBound 回滚，留下一条只能去「待上链注册」收拾的半截机构。
        // 读链失败（readable=false）时不拦，仍按老路径降级放行，由写交易阶段报真实原因。
        if (AuthService.ROLE_BUREAU.equals(role) && city != null) {
            BureauCityService.CityOccupant occupant = bureauCityService.occupantOfCity(city);
            if (occupant.isOccupied()) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "城市「" + city + "」已被社保局「" + occupant.displayName() + "」绑定（"
                                + occupant.getBureau() + "）：社保局与城市是一对一关系，请换一个尚未被占用的城市");
            }
        }

        Created created = doCreate(request.getUsername(), request.getPassword(), role, request.getDisplayName(),
                request.getInstitutionCode(), name, city, "admin");

        AccountCreateResult result = toResult(created, name, city);
        result.setOnChainRegistered(Boolean.FALSE);

        if (!ON_CHAIN_ROLE_INDEX.containsKey(role)) {
            // ADMIN / EMPLOYEE：合约里没有（也不需要）对应的注册入口
            result.setOnChainRegistered(null);
            result.setOnChainMessage(NO_REGISTRATION_NEEDED);
            return result;
        }
        if (created.key == null || trimToNull(created.key.getAddress()) == null) {
            result.setOnChainMessage(MISSING_FIELDS_MESSAGE);
            return result;
        }
        if (name == null || requiresCity(role) && city == null) {
            log.warn("账号 {}（{}）缺少链上注册必要字段：name={}, city={}，跳过自动注册",
                    request.getUsername(), role, name, city);
            result.setOnChainMessage(MISSING_FIELDS_MESSAGE);
            return result;
        }

        String address = created.key.getAddress();
        boolean bureauRegistered = false;
        try {
            if (AuthService.ROLE_BUREAU.equals(role)) {
                adminBizService.addBureaus(address, name, city);
                bureauRegistered = true;
            } else if (AuthService.ROLE_SPONSOR.equals(role)) {
                adminBizService.addSponsorAccount(address, name, city);
            } else {
                // REGULATOR：agency 优先用 city，没给就用 name
                adminBizService.addRegulatorAccount(address, name, city == null ? name : city);
            }
        } catch (Exception e) {
            String reason = describe(e);
            log.warn("账号 {}（{}）自动链上注册失败（账号已创建，不影响登录）：{}",
                    request.getUsername(), role, reason);
            result.setOnChainMessage(bureauRegistered
                    ? "链上注册已提交但授权失败：" + ContractErrorMessages.sanitize(reason) + "（请到「待上链注册」补授权）"
                    : "链上注册失败：" + ContractErrorMessages.sanitize(reason));
            return result;
        }

        if (AuthService.ROLE_BUREAU.equals(role)) {
            try {
                adminBizService.changeBureausAuthorized(address, true);
            } catch (Exception e) {
                String reason = describe(e);
                log.warn("社保局 {} 已注册上链但授权失败（账号已创建）：{}", address, reason);
                result.setOnChainMessage("已注册上链，但授权失败："
                        + ContractErrorMessages.sanitize(reason) + "（请到「待上链注册」补授权）");
                return result;
            }
        }

        result.setOnChainRegistered(Boolean.TRUE);
        result.setOnChainMessage("已自动完成链上注册"
                + (AuthService.ROLE_BUREAU.equals(role) ? "并授权" : "")
                + "：address=" + address);
        log.info("账号 {}（{}）已自动完成链上注册：address={}", request.getUsername(), role, address);
        return result;
    }

    /**
     * BUREAU 创建 SPONSOR：<b>缴费单位的城市不再由调用方填写</b>，而是自动继承该社保局在链上的服务城市，
     * 并由后端用 ADMIN 私钥代签完成链上注册。
     *
     * <p>城市解析规则见 {@link #resolveBureauCity}（只有 1 个服务城市就用它；多个则要求显式指定且必须命中；
     * 一个都没有或读不到链时给出明确提示，绝不凭空造城市）。请求体里的 {@code city} 因此只是"多个服务城市时
     * 的必填选项"，单城市社保局即使传了别的城市也会被链上的唯一服务城市覆盖。
     */
    @Transactional
    public AccountCreateResult createSponsorByBureau(Requests.BureauAccountCreate request) {
        AuthUser creator = AuthContext.require();
        assertCanCreate(creator.getRole(), AuthService.ROLE_SPONSOR);

        // 城市来源：链上该社保局自己的服务城市（请求里的 city 只在"多城市"时作为必选项参与校验）
        String city = resolveBureauCity(creator, trimToNull(request.getCity()));

        String name = trimToNull(request.getName());
        Created created = doCreate(request.getUsername(), request.getPassword(), AuthService.ROLE_SPONSOR,
                request.getDisplayName(), request.getInstitutionCode(), name, city, creator.getUsername());
        AccountCreateResult result = toResult(created, name, city);
        // 创建即上链：账号 + 机构密钥已经落库，随后由后端用 ADMIN 私钥代签 addSponsorAccount。
        // 失败绝不让建号失败（见方法内注释）。
        autoRegisterSponsorAsAdmin(result, created.key, name, city, request.getUsername());
        return result;
    }

    /**
     * 解析缴费单位应落到的城市：<b>以链上该社保局的服务城市为准</b>，请求里的 {@code city} 只是多城市时的选择项。
     *
     * <p>规则：
     * <ol>
     *   <li>链上<b>恰好 1 个</b>服务城市 → 忽略请求里的 city，直接用这一个（社保局不需要、也不应该手填）；</li>
     *   <li>链上<b>多个</b>服务城市 → 请求必须带 city 且必须属于该社保局的服务城市，
     *       否则 400 / 403 并列出可选城市；没带则 400 提示要指定哪个；</li>
     *   <li>链上<b>一个都没有</b>（该社保局没上链 / 没有服务城市）→ 不带 city 时 400，
     *       提示"本社保局尚未在链上注册/授权服务城市，请联系平台管理员"；
     *       带了 city 时保持既有行为（服务城市为空不阻断），沿用请求值；</li>
     *   <li>链<b>读不通</b>（未就绪 / 节点不可达 / 合约未配置）→ 带了 city 就沿用请求值并记 warn，
     *       不因为读链失败就阻断开号（链上注册失败会自然体现在 {@code onChainRegistered=false}）；
     *       没带 city 才 400 并给出原因与"请显式指定 city"的指引。</li>
     * </ol>
     */
    private String resolveBureauCity(AuthUser creator, String requestedCity) {
        String institutionCode = creator.getInstitutionCode();
        BureauCityService.CityLookup lookup = bureauCityService.lookupByInstitutionCode(institutionCode);
        String who = "本社保局（institutionCode=" + (institutionCode == null ? "" : institutionCode) + "）";

        if (!lookup.isReadable()) {
            if (requestedCity != null) {
                log.warn("读取 {} 的链上服务城市失败，沿用请求指定的 city={}（不因读链失败阻断开号）：{}",
                        who, requestedCity, lookup.getFailureReason());
                return requestedCity;
            }
            if (isNotRegistered(lookup.getFailureReason())) {
                // 合约 getBureau 对未注册地址回滚 UnknownBureau：这是"社保局还没上链"的确证，不是链故障
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "本社保局尚未在链上注册/授权服务城市，请联系平台管理员");
            }
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "读取" + who + "的链上服务城市失败，无法自动确定缴费单位所在城市："
                            + ContractErrorMessages.sanitize(lookup.getFailureReason())
                            + "；请在请求里显式指定 city 后重试");
        }

        List<String> cities = lookup.getCities();
        if (cities.isEmpty()) {
            if (requestedCity != null) {
                // 与改造前一致：链上服务城市为空时不阻断，沿用请求里的城市
                log.warn("{} 在链上尚未注册/授权服务城市，沿用请求指定的 city={}", who, requestedCity);
                return requestedCity;
            }
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "本社保局尚未在链上注册/授权服务城市，请联系平台管理员");
        }

        if (cities.size() == 1) {
            String only = cities.get(0);
            if (requestedCity != null && !only.equals(requestedCity)) {
                log.info("{} 的链上服务城市只有 {}，忽略请求里的 city={}，按链上服务城市创建缴费单位",
                        who, only, requestedCity);
            }
            return only;
        }

        // 多个服务城市：必须显式指定，且必须命中
        if (requestedCity == null) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "本社保局服务多个城市，请指定 city：" + String.join("、", cities));
        }
        if (!cities.contains(requestedCity)) {
            throw new BizException(ErrorCode.FORBIDDEN,
                    "社保局只能为自身服务城市创建缴费单位：本社保局服务城市 " + cities
                            + "（可选：" + String.join("、", cities) + "）；请求城市 " + requestedCity);
        }
        return requestedCity;
    }

    /** SPONSOR 创建 EMPLOYEE：职工同样自动生成一把属于自己的链上密钥（编码 EMP_ 开头）。 */
    @Transactional
    public AccountCreateResult createEmployeeBySponsor(Requests.SponsorAccountCreate request) {
        AuthUser creator = AuthContext.require();
        assertCanCreate(creator.getRole(), AuthService.ROLE_EMPLOYEE);
        // 职工也不需要（也不接受）调用方指定 institutionCode：请求体里根本没有这个字段
        // （Requests.SponsorAccountCreate），编码一律由 doCreate 自动生成 EMP_<6位随机大写十六进制>。
        // 职工拿到自己的链上地址后：单位用该地址 addEmployee 建档，职工本人即可用它查账户 / 发起转移，
        // 全程不需要绑定钱包、也不需要手输地址。
        Created created = doCreate(request.getUsername(), request.getPassword(), AuthService.ROLE_EMPLOYEE,
                request.getDisplayName(), null, null, null, creator.getUsername());
        AccountCreateResult result = toResult(created, null, null);
        result.setOnChainRegistered(null);
        result.setOnChainMessage(NO_REGISTRATION_NEEDED);
        return result;
    }

    // ------------------------------------------------------------------
    // 对外：按角色列账号（GET /api/bureau/accounts、GET /api/sponsor/accounts）
    // ------------------------------------------------------------------

    /**
     * 列出指定角色的<b>全部</b>账号（仅 ADMIN 视角使用，例如 {@code GET /api/admin/accounts}）。
     *
     * <p>{@code offset} / {@code limit} 都可省略（省略即"默认全部"）。
     * <b>面向机构账号的列表不要用这个方法</b>：社保局 / 缴费单位必须走
     * {@link #listSponsorsInMyCities} / {@link #listMyEmployeeAccounts} 按登录者范围收口。
     */
    public List<AccountView> listRoleAccounts(String role, Integer offset, Integer limit) {
        return page(authService.listAccounts(role), offset, limit);
    }

    // ------------------------------------------------------------------
    // 社保局创建缴费单位：后端用 ADMIN 私钥代签，做到"创建即上链"
    // ------------------------------------------------------------------

    /**
     * 缴费单位账号建好后，由后端用 <b>ADMIN 私钥代签</b> {@code addSponsorAccount}。
     *
     * <p><b>为什么必须代签、且必须是 ADMIN</b>：合约里 {@code addSponsorAccount} 带 {@code onlyAdmin}
     * 修饰，发起创建的社保局自己发这笔交易必然被回滚（{@code NotAuthorized}）。链下"只有社保局能创建
     * 缴费单位账号"已由 HTTP 层 {@code @RequireRole(BUREAU)} + {@link #assertCanCreate} 保证，
     * 链上署名交给平台 ADMIN 即可 —— 不需要改合约，也不需要重新部署。
     *
     * <p>四种终态（前端的 {@code onChainRegistered} 三态展示与之对应）：
     * <ol>
     *   <li><b>链上已是 SPONSOR</b> → 幂等返回，<b>不发交易</b>，{@code onChainRegistered=true}；</li>
     *   <li><b>缺单位名称/城市</b> → 完全不碰链，{@code onChainRegistered=false}，提示去补录；</li>
     *   <li><b>链上注册成功</b> → {@code onChainRegistered=true}，message 里带 address；</li>
     *   <li><b>链上失败</b>（回滚 / 读不到链 / 交易处理器异常）→ 账号与机构密钥<b>照常保留</b>，
     *       {@code onChainRegistered=false}，message 里带真实原因（若是回执失败还会带 txHash）。</li>
     * </ol>
     *
     * <p>全程 try/catch：这里的任何异常都只写日志与 {@code onChainMessage}，绝不向上抛——
     * 否则外层 {@code @Transactional} 会把刚建好的账号与机构密钥一起回滚。
     *
     * @param key 新建账号自己的机构链账户（可能为 null）
     */
    private void autoRegisterSponsorAsAdmin(AccountCreateResult result, InstitutionKey key,
                                            String name, String city, String username) {
        result.setOnChainRegistered(Boolean.FALSE);

        String address = key == null ? null : trimToNull(key.getAddress());
        if (address == null) {
            result.setOnChainMessage(SPONSOR_MISSING_FIELDS_MESSAGE);
            result.setHint(SPONSOR_PENDING_HINT);
            return;
        }
        if (name == null || city == null) {
            // 缺 name/city 时不调链：addSponsorAccount 对空字符串会回滚，不如直接落到「待上链注册」补录
            log.warn("缴费单位账号 {} 缺少链上注册必要字段（name={}, city={}），跳过自动注册，address={}",
                    username, name, city, address);
            result.setOnChainMessage(SPONSOR_MISSING_FIELDS_MESSAGE);
            result.setHint(SPONSOR_PENDING_HINT);
            return;
        }

        // 幂等：先读链上角色，已经是 SPONSOR 就不重复发交易（否则会 SponsorAlreadyExists / AccountHasRole 回滚）
        try {
            if (onChainRoleIndexOf(address) == ON_CHAIN_SPONSOR_INDEX) {
                log.info("缴费单位账号 {} 的地址 {} 在链上已是 SPONSOR，跳过重复注册", username, address);
                result.setOnChainRegistered(Boolean.TRUE);
                result.setOnChainMessage("已注册，无需重复操作");
                result.setHint("该地址在链上已经是缴费单位，无需重复注册");
                return;
            }
        } catch (Exception e) {
            // 读不出去（链未就绪 / 合约未配置）也继续往下试：真正的错误由写交易阶段报出，信息更准
            log.warn("读取 {} 的链上角色失败，继续尝试注册上链：{}", address, describe(e));
        }

        try {
            adminBizService.addSponsorAccountAsAdmin(address, name, city);
            result.setOnChainRegistered(Boolean.TRUE);
            result.setOnChainMessage("已自动完成链上注册（由 ADMIN 代签）：address=" + address);
            result.setHint("该缴费单位已可在链上为职工建档并缴费，无需管理员再操作");
            log.info("缴费单位账号 {} 已由 ADMIN 代签自动完成链上注册：address={}", username, address);
        } catch (Exception e) {
            String reason = describe(e);
            result.setOnChainMessage("链上注册失败：" + ContractErrorMessages.sanitize(reason));
            result.setHint(SPONSOR_PENDING_HINT);
            log.warn("缴费单位账号 {}（address={}）自动链上注册失败（账号已创建，不影响登录）：{}",
                    username, address, reason);
        }
    }

    /** 核心合约 {@code roleOf(address)} → 角色序号；解析不出数字时返回 -1。 */
    private int onChainRoleIndexOf(String address) {
        CallResult result = chainTxService.call(ChainProperties.CORE, "roleOf",
                ChainParams.address(address, "缴费单位地址"));
        Object value = result == null ? null : result.first();
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                // 落到下面的 -1
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // 关系校验
    // ------------------------------------------------------------------

    /** 目标角色是否合法（5 种已知角色之一），非法直接 400。 */
    public static String normalizeRole(String role) {
        String normalized = role == null ? null : role.trim().toUpperCase();
        if (normalized == null || !ALL_ROLES.contains(normalized)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "未知角色：" + role + "（可选 ADMIN / BUREAU / SPONSOR / REGULATOR / EMPLOYEE）");
        }
        return normalized;
    }

    /** 校验"creatorRole 能不能创建 targetRole"，越权抛 403 并给出中文原因。 */
    public static void assertCanCreate(String creatorRole, String targetRole) {
        String target = normalizeRole(targetRole);
        String creator = creatorRole == null ? "" : creatorRole.trim().toUpperCase();
        Set<String> allowed = ALLOWED_TARGETS.get(creator);
        if (allowed == null) {
            throw new BizException(ErrorCode.FORBIDDEN,
                    "当前角色 " + creator + " 不能创建 " + target + " 账号");
        }
        if (allowed.contains(target)) {
            return;
        }
        if (AuthService.ROLE_BUREAU.equals(creator)) {
            throw new BizException(ErrorCode.FORBIDDEN, "社保局只能创建缴费单位账号");
        }
        if (AuthService.ROLE_SPONSOR.equals(creator)) {
            throw new BizException(ErrorCode.FORBIDDEN, "缴费单位只能创建职工账号");
        }
        throw new BizException(ErrorCode.FORBIDDEN,
                "当前角色 " + creator + " 不能创建 " + target + " 账号");
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private Created doCreate(String username, String rawPassword, String role, String displayName,
                             String requestedInstitutionCode, String institutionName, String city,
                             String createdBy) {
        // 用户名唯一：先查一次给出明确的中文 message，数据库唯一索引是最后一道防线
        if (authService.findByUsername(username) != null) {
            throw new BizException(ErrorCode.ACCOUNT_EXISTS, "用户名已存在：" + username);
        }

        InstitutionKey key = null;
        String institutionCode = null;
        if (INSTITUTION_ROLES.contains(role)) {
            institutionCode = resolveInstitutionCode(role, requestedInstitutionCode);
            String name = firstNonBlank(institutionName, displayName, username);
            key = ensureInstitutionKey(institutionCode, name, role, city, createdBy, requestedInstitutionCode);
        } else if (AuthService.ROLE_EMPLOYEE.equals(role)) {
            // 职工也要一把属于自己的链上密钥：地址在开号时自动生成，职工不需要绑定钱包、也不需要手输地址。
            // 编码固定为 EMP_<6位随机大写十六进制>；调用方指定的 institutionCode 一律忽略——
            // 复用某个机构的编码会让职工账号拿到该机构的链上权限，属于权限串号。
            if (trimToNull(requestedInstitutionCode) != null) {
                log.warn("创建职工账号 {} 时忽略了 institutionCode={}：职工链上地址一律自动生成（EMP_ 开头）",
                        username, requestedInstitutionCode);
            }
            institutionCode = generateCode(role);
            key = ensureInstitutionKey(institutionCode, firstNonBlank(displayName, username),
                    role, null, createdBy, null);
        } else if (trimToNull(requestedInstitutionCode) != null) {
            log.warn("创建 {} 账号 {} 时忽略了 institutionCode={}：该角色不需要机构链账户",
                    role, username, requestedInstitutionCode);
        }

        Account account = authService.createAccount(username, rawPassword, role, displayName,
                institutionCode, currentAccountIdOrNull());

        // 账号建好、机构/职工链上地址也生成好之后，立即把该地址写进 cs_wallet（verified=true）。
        // 这样"钱包绑定"不再是必经步骤：登录后 primaryAddress 直接就是这把平台托管的地址。
        // 绑定失败绝不让开号失败（与"链上失败不影响开号"同一原则）。
        if (key != null && trimToNull(key.getAddress()) != null) {
            try {
                authService.bindPlatformAddress(account.getId(), key.getAddress(),
                        AuthService.AUTO_BIND_REMARK + "（" + createdBy + " 创建，机构编码 " + institutionCode + "）");
            } catch (Exception e) {
                log.warn("账号 {}（{}）自动绑定地址 {} 失败（账号已创建，不影响登录）：{}",
                        username, role, key.getAddress(), describe(e));
            }
        }
        return new Created(account, key);
    }

    // ------------------------------------------------------------------
    // 按登录者范围收口的账号列表（GET /api/sponsor/accounts、GET /api/bureau/accounts）
    // ------------------------------------------------------------------

    /** 链上名单翻页时每次向 {@code getPersonList} 要多少条。 */
    private static final int CHAIN_PAGE_SIZE = 200;

    /** 最多翻多少页：{@code nextOffset} 异常时不至于死循环。 */
    private static final int CHAIN_MAX_PAGES = 50;

    /**
     * <b>本单位（当前登录缴费单位）的职工账号</b>。
     *
     * <p>归属判定是<b>并集</b>，满足任一条件即算本单位：
     * <ol>
     *   <li><b>链上归属</b>：该账号的链上地址出现在
     *       {@code getPersonList(本单位链上地址)} 里（本单位读自己的职工名单是合约允许的）；</li>
     *   <li><b>创建归属</b>：该账号的 {@code created_by} == 当前登录账号 id
     *       （层级开号时写入，见 {@link #doCreate}）。</li>
     * </ol>
     *
     * <p><b>失败一律"收窄"而不是"放宽"</b>：没有 institutionCode、解析不出本单位链上地址、
     * 或读链失败时，链上归属这一条<b>贡献空集合</b>（绝不是"查不到就返回全部账号"）；
     * 此时若连创建归属也没有命中，结果就是空数组 + HTTP 200。
     */
    public List<AccountView> listMyEmployeeAccounts(Integer offset, Integer limit) {
        AuthUser me = AuthContext.require();
        Map<String, String> addressByCode = institutionKeyService.addressByCode();
        Set<String> onChain = chainEmployeeAddresses(me.getInstitutionCode());

        List<Account> kept = new ArrayList<>();
        for (Account account : authService.listAccountEntities(AuthService.ROLE_EMPLOYEE)) {
            if (belongsToMe(account, me.getAccountId(), onChain, addressByCode)) {
                kept.add(account);
            }
        }
        log.debug("本单位职工账号：accountId={}, 链上归属地址数={}, 命中={}",
                me.getAccountId(), onChain.size(), kept.size());
        return page(authService.toViews(kept), offset, limit);
    }

    /**
     * <b>本局服务城市内的缴费单位账号</b>（当前登录社保局）。
     *
     * <p>判定：先取本局链上服务城市（{@link BureauCityService}，与 {@code GET /api/bureau/cities} 同链路），
     * 再逐个读单位的链上 {@code city}（必须用 <b>ADMIN 身份</b>读：合约 {@code getSponsorAccount}
     * 只允许「单位自己 / ADMIN / REGULATOR」，社保局读会被 {@code SponsorNotVisible} 回滚），
     * 只保留 {@code city ∈ 服务城市} 的单位。
     *
     * <p><b>宁缺勿滥</b>：读不到城市的单位（未注册 / 链不通 / 地址缺失）一律<b>不返回</b>；
     * 本局服务城市读不到或为空同样返回空数组 + HTTP 200。
     */
    public List<AccountView> listSponsorsInMyCities(Integer offset, Integer limit) {
        AuthUser me = AuthContext.require();
        BureauCityService.CityLookup lookup = bureauCityService.lookupByInstitutionCode(me.getInstitutionCode());
        if (!lookup.isReadable()) {
            log.warn("读取本社保局（accountId={}）的链上服务城市失败，缴费单位列表按空数组返回：{}",
                    me.getAccountId(), lookup.getFailureReason());
            return new ArrayList<>();
        }
        Set<String> cities = new LinkedHashSet<>(lookup.getCities());
        if (cities.isEmpty()) {
            log.warn("本社保局（accountId={}）在链上没有任何服务城市，缴费单位列表按空数组返回", me.getAccountId());
            return new ArrayList<>();
        }

        Map<String, String> addressByCode = institutionKeyService.addressByCode();
        List<Account> kept = new ArrayList<>();
        for (Account account : authService.listAccountEntities(AuthService.ROLE_SPONSOR)) {
            String address = chainAddressOf(account.getInstitutionCode(), addressByCode);
            if (address == null) {
                continue;
            }
            String city = sponsorCityOf(address);
            if (city != null && cities.contains(city)) {
                kept.add(account);
            }
        }
        log.debug("本局缴费单位账号：accountId={}, 服务城市={}, 命中={}",
                me.getAccountId(), cities, kept.size());
        List<AccountView> views = page(authService.toViews(kept), offset, limit);
        // 列表要显示"链上角色"：只对当前页的机构查 roleOf（条数受 limit 限制，不随总机构数线性增长）。
        // 查不到时保持 null，前端显示「—」；只有链上明确返回 NONE 才是「未注册」。
        for (AccountView view : views) {
            String address = view.getChainAddress();
            view.setOnChainRole(address == null || address.trim().isEmpty()
                    ? null : authService.onChainRole(address));
        }
        return views;
    }

    /** 链上归属判定 + 创建归属判定的并集。 */
    private boolean belongsToMe(Account account, Long myAccountId, Set<String> onChain,
                                Map<String, String> addressByCode) {
        if (myAccountId != null && myAccountId.equals(account.getCreatedBy())) {
            return true;
        }
        String address = chainAddressOf(account.getInstitutionCode(), addressByCode);
        return address != null && onChain.contains(address);
    }

    /** {@code institutionCode → 链上地址}（小写）；没有编码或未登记地址时返回 null。 */
    private String chainAddressOf(String institutionCode, Map<String, String> addressByCode) {
        String code = trimToNull(institutionCode);
        if (code == null) {
            return null;
        }
        String address = addressByCode.get(code);
        return trimToNull(address) == null ? null : address.trim().toLowerCase();
    }

    /**
     * 本单位链上的职工地址集合（小写）。
     *
     * <p>地址解析失败 / 读链失败一律返回<b>已取到的部分或空集合</b>（只记日志），
     * 调用方因此只会"少看到几个职工"，绝不会"多看到别人家的职工"。
     */
    private Set<String> chainEmployeeAddresses(String institutionCode) {
        Set<String> addresses = new LinkedHashSet<>();
        String code = trimToNull(institutionCode);
        if (code == null) {
            log.debug("当前账号没有机构编码，链上归属按空集合处理");
            return addresses;
        }
        String myAddress;
        try {
            InstitutionKey key = institutionKeyService.findByCode(code);
            myAddress = key == null ? null : trimToNull(key.getAddress());
        } catch (RuntimeException e) {
            log.warn("解析本单位链上地址失败，链上归属按空集合处理：{}", describe(e));
            return addresses;
        }
        if (myAddress == null) {
            log.debug("本单位（{}）没有链上地址，链上归属按空集合处理", code);
            return addresses;
        }
        try {
            int offset = 0;
            for (int page = 0; page < CHAIN_MAX_PAGES; page++) {
                CallResult result = chainTxService.call(ChainProperties.CORE, "getPersonList",
                        ChainParams.address(myAddress, "本单位地址"),
                        ChainParams.uint(offset, "offset"), ChainParams.uint(CHAIN_PAGE_SIZE, "limit"));
                List<Object> values = result == null ? null : result.getValues();
                List<?> current = values != null && !values.isEmpty() && values.get(0) instanceof List
                        ? (List<?>) values.get(0) : Collections.emptyList();
                for (Object item : current) {
                    String address = item == null ? null : String.valueOf(item).trim().toLowerCase();
                    if (address != null && !address.isEmpty()) {
                        addresses.add(address);
                    }
                }
                if (current.isEmpty()) {
                    break;
                }
                int nextOffset = asInt(values != null && values.size() > 2 ? values.get(2) : null,
                        offset + current.size());
                if (nextOffset <= offset) {
                    break;
                }
                offset = nextOffset;
            }
        } catch (RuntimeException e) {
            log.warn("读取本单位链上职工名单失败，链上归属按已取到的 {} 条处理：{}", addresses.size(), describe(e));
        }
        return addresses;
    }

    /** 读缴费单位的链上城市；用 ADMIN 身份读（BUREAU 读会被 SponsorNotVisible 回滚）；失败返回 null。 */
    private String sponsorCityOf(String sponsorAddress) {
        try {
            Map<String, Object> account = ChainViewMapper.mapStruct(
                    chainTxService.callAs(AuthService.ROLE_ADMIN, ChainProperties.CORE,
                            "getSponsorAccount", sponsorAddress),
                    ChainViewMapper.SPONSOR_ACCOUNT);
            Object city = account.get("city");
            return city == null ? null : trimToNull(String.valueOf(city));
        } catch (RuntimeException e) {
            log.debug("读取缴费单位 {} 的链上城市失败（该单位不出现在本局列表里）：{}", sponsorAddress, describe(e));
            return null;
        }
    }

    /** 把数组切片（{@code offset} / {@code limit} 都可省略，省略即"默认全部"）。 */
    private List<AccountView> page(List<AccountView> all, Integer offset, Integer limit) {
        if (offset == null && limit == null) {
            return all;
        }
        int from = offset == null || offset < 0 ? 0 : offset;
        if (from >= all.size()) {
            return new ArrayList<>();
        }
        int to = limit == null || limit <= 0 ? all.size() : Math.min(all.size(), from + limit);
        return new ArrayList<>(all.subList(from, to));
    }

    /** 当前登录账号 id；没有登录态时返回 null。 */
    private Long currentAccountIdOrNull() {
        AuthUser me = AuthContext.get();
        return me == null ? null : me.getAccountId();
    }

    private String resolveInstitutionCode(String role, String requested) {
        String value = trimToNull(requested);
        if (value != null) {
            // 契约：给了就复用（已存在则直接用；不存在则由 ensureInstitutionKey 用这个 code 生成一把新私钥）
            return value;
        }
        return generateCode(role);
    }

    /** 生成 {@code SPONSOR_<6位随机大写十六进制>} / {@code BUREAU_<...>} / {@code EMP_<...>}。 */
    private String generateCode(String role) {
        return institutionKeyService.nextCode(codePrefix(role));
    }

    /**
     * 角色 → 机构编码前缀。用 {@code EMP} 而不是 {@code EMPLOYEE}：与 {@code SPONSOR_} / {@code BUREAU_}
     * 同风格，且编码更短（职工数量远多于机构）。
     */
    private static String codePrefix(String role) {
        return AuthService.ROLE_EMPLOYEE.equals(role) ? InstitutionKeyService.PREFIX_EMPLOYEE : role;
    }

    /**
     * 取回或新建机构链账户。
     *
     * <p>给了已存在的 {@code institutionCode} 就直接复用（不重复生成私钥，否则链上地址会变，
     * 已注册的机构会对不上）；否则 {@link InstitutionKeyService#generate} 随机生成私钥，
     * 以 AES-256-GCM 密文落库。
     */
    private InstitutionKey ensureInstitutionKey(String code, String name, String role, String city,
                                                String createdBy, String requestedCode) {
        InstitutionKey existing = institutionKeyService.findByCode(code);
        if (existing != null) {
            log.info("复用已存在的机构链账户：code={}, address={}（未生成新私钥）", code, existing.getAddress());
            return existing;
        }
        StringBuilder remark = new StringBuilder("由 ").append(createdBy).append(" 创建");
        if (trimToNull(city) != null) {
            remark.append("，服务城市：").append(city.trim());
        }
        if (trimToNull(requestedCode) != null) {
            remark.append("，指定编码");
        }
        log.info("为新开的 {} 账号生成机构链账户：code={}", role, code);
        return institutionKeyService.generate(code, name, role, remark.toString());
    }

    private AccountCreateResult toResult(Created created, String name, String city) {
        AccountCreateResult result = new AccountCreateResult();
        result.setAccount(authService.toView(created.account, false));
        result.setInstitutionCode(created.key == null ? "" : created.key.getCode());
        result.setChainAddress(created.key == null ? "" : created.key.getAddress());
        result.setHint(hintFor(created.account.getRole()));
        result.setName(name == null ? "" : name);
        result.setCity(city == null ? "" : city);
        return result;
    }

    /** 该角色的链上注册是否必须提供城市（社保局与缴费单位必填，监管不需要）。 */
    private static boolean requiresCity(String role) {
        return AuthService.ROLE_BUREAU.equals(role) || AuthService.ROLE_SPONSOR.equals(role);
    }

    /** 取异常里对管理员最有用的那句：业务异常用它自己的 message，其余用 getMessage，最后兜底类名。 */
    private static String describe(Throwable e) {
        if (e instanceof BizException) {
            String message = e.getMessage();
            if (message != null && !message.trim().isEmpty()) {
                return message.trim();
            }
        }
        String message = e.getMessage();
        if (message != null && !message.trim().isEmpty()) {
            return message.trim();
        }
        return e.getClass().getSimpleName();
    }

    private String hintFor(String role) {
        switch (role == null ? "" : role) {
            case AuthService.ROLE_SPONSOR:
                return "请通知平台管理员在链上注册该地址：POST /api/admin/sponsors"
                        + "（body: {sponsor: chainAddress, name, city}）";
            case AuthService.ROLE_BUREAU:
                return "请通知平台管理员在链上注册该地址：POST /api/admin/bureaus"
                        + "（body: {bureau: chainAddress, name, city}），再用 POST /api/admin/bureaus/authorize 启用授权";
            case AuthService.ROLE_REGULATOR:
                return "请通知平台管理员在链上注册该地址：POST /api/admin/regulators"
                        + "（body: {regulator: chainAddress, name, agency}）";
            case AuthService.ROLE_ADMIN:
                return "该地址需在链上授予 ADMIN 角色（由现有 ADMIN 调合约 addAdmin，后端无对应 REST 接口）";
            default:
                // EMPLOYEE：职工链上地址已在开号时自动生成，不需要任何链上注册动作。
                return "职工链上地址已自动生成；职工无需绑定钱包（想用自己的 MetaMask 地址可在「钱包绑定」里可选绑定）";
        }
    }

    /** 读链失败原因里带 {@code UnknownBureau} 时，说明该社保局确实还没在转移合约上注册（而不是链故障）。 */
    private static boolean isNotRegistered(String failureReason) {
        return failureReason != null && failureReason.contains("UnknownBureau");
    }

    /** 合约返回的 {@code nextOffset} 归一化成 int；解析不出时用 fallback。 */
    private static int asInt(Object value, int fallback) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                // 落到 fallback
            }
        }
        return fallback;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            String trimmed = trimToNull(value);
            if (trimmed != null) {
                return trimmed;
            }
        }
        return "";
    }

    /** 内部传递：新账号 + 它自己的链上密钥（EMPLOYEE 也有一把，不再为 null）。 */
    private static final class Created {
        private final Account account;
        private final InstitutionKey key;

        private Created(Account account, InstitutionKey key) {
            this.account = account;
            this.key = key;
        }
    }
}
