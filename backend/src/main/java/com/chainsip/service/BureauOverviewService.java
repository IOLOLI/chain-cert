package com.chainsip.service;

import com.chainsip.chain.dto.CallResult;
import com.chainsip.common.BizException;
import com.chainsip.common.ErrorCode;
import com.chainsip.config.ChainProperties;
import com.chainsip.entity.Account;
import com.chainsip.security.AuthUser;
import com.chainsip.security.AuthContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 社保局的「辖区总览」：本局服务城市内的全部缴费单位、参保职工，以及职工的参保记录与缴纳记录。
 *
 * <p><b>为什么单独一个服务</b>：这几张表虽然都归社保局看，但取数路径与既有接口都不同——
 * 既有 {@code GET /api/bureau/accounts} 只列「本局自己创建的账号」（链下层级视角），
 * 而这里要的是「本局服务城市内的全部机构」（链上归属视角），两者不等价：别的社保局
 * 或管理员创建的、但城市落在本局辖区里的单位，也要能看见。
 *
 * <h2>三个必须守住的设计约束</h2>
 * <ol>
 *   <li><b>一律用 ADMIN 身份读链</b>：合约里 {@code getSponsorList} 是
 *       {@code onlyAdminOrRegulator}、{@code getSponsorAccount} 只允许「单位自己 / ADMIN / REGULATOR」、
 *       {@code getPersonList} 只允许「该单位 / ADMIN / REGULATOR」、{@code getInsuranceAccount} 与
 *       {@code getPaymentRecords} 走 {@code _requireAccountReader}（本人 / 所属单位 / ADMIN / REGULATOR）
 *       ——<b>都不含 BUREAU</b>。社保局用自己的私钥读会被 {@code SponsorNotVisible} /
 *       {@code NotAuthorized} / {@code AccountNotVisible} 回滚。因此这里全部走
 *       {@link ChainTxService#callAs} 用 ADMIN 代读，与 {@link BureauBizService#depositToOwnSponsor}
 *       「后端代签」的做法一致：链下由 {@code @RequireRole(BUREAU)} 与城市归属判断把关，链上身份交给 ADMIN。</li>
 *   <li><b>辖区以链上服务城市为唯一权威</b>：本局能看到哪些城市，来自转移合约
 *       {@code getBureau(本局地址).cities}（{@link BureauCityService}），链下不另存一份归属关系。</li>
 *   <li><b>链未就绪 / 读不到 → 空结果 + HTTP 200，绝不 5xx</b>：与项目其它只读接口的契约一致，
 *       单条记录读失败只降级那一行，不让整次请求失败。</li>
 * </ol>
 *
 * <h2>性能</h2>
 * <p>合约没有「按城市列公司 / 列职工」的 view，只能遍历 {@code getSponsorList} 再按
 * {@code getSponsorAccount().city} 过滤；职工同理（{@code getPersonList} + 逐条
 * {@code getInsuranceAccount}）。因此这里给扫描量设了硬上限
 * （{@value #SPONSOR_PAGE_SIZE}×{@value #MAX_SPONSOR_PAGES} 家单位、
 * 每家最多 {@value #EMPLOYEE_PAGE_SIZE} 名职工、职工最多扫描 {@value #MAX_EMPLOYEE_SCAN} 条），
 * 超出的部分在 {@code truncated} 里如实告知，避免一次请求把节点读穿。
 */
@Service
public class BureauOverviewService {

    private static final Logger log = LoggerFactory.getLogger(BureauOverviewService.class);

    private static final String CORE = ChainProperties.CORE;

    /** 遍历 {@code getSponsorList} 时每页多少个单位。 */
    private static final int SPONSOR_PAGE_SIZE = 100;

    /** 最多翻多少页：合约 nextOffset 异常时不至于死循环。 */
    private static final int MAX_SPONSOR_PAGES = 20;

    /** 每家单位最多取多少名职工（合约没有按城市筛职工的接口，只能逐家取）。 */
    private static final int EMPLOYEE_PAGE_SIZE = 50;

    /** 一次请求最多扫描多少名职工（含读参保账户），防止大辖区把节点读穿。 */
    private static final int MAX_EMPLOYEE_SCAN = 300;

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    /** 辖区总览聚合结果缓存：链上余额/状态变化不需要秒级一致，45 秒足够。 */
    private static final Duration OVERVIEW_TTL = Duration.ofSeconds(45);

    /** 单位/职工名单缓存：一次扫描可能几百次链调用，30 秒内复用。 */
    private static final Duration SCAN_TTL = Duration.ofSeconds(30);

    /** 单个链上账户缓存：总览、职工列表、养老金总览会反复读同一个地址。 */
    private static final Duration ACCOUNT_TTL = Duration.ofSeconds(30);

    /** 读失败的空值只缓存很短时间，既防穿透又不会长时间掩盖节点恢复。 */
    private static final Duration MISS_TTL = Duration.ofSeconds(10);

    /** 缓存里标记"确认读不到"的哨兵字段。 */
    private static final String NULL_MARKER = "__null";

    private final ChainTxService chain;
    private final BureauCityService bureauCityService;
    private final InstitutionKeyService institutionKeyService;
    private final AuthService authService;
    private final RedisCacheService cache;

    public BureauOverviewService(ChainTxService chain, BureauCityService bureauCityService,
                                 InstitutionKeyService institutionKeyService, AuthService authService,
                                 RedisCacheService cache) {
        this.chain = chain;
        this.bureauCityService = bureauCityService;
        this.institutionKeyService = institutionKeyService;
        this.authService = authService;
        this.cache = cache;
    }

    // ------------------------------------------------------------------
    // 辖区范围
    // ------------------------------------------------------------------

    /** 本局的辖区（服务城市）+ 这次读链到底成不成功。 */
    public static final class Scope {

        private final List<String> cities;
        private final boolean readable;
        private final String reason;

        Scope(List<String> cities, boolean readable, String reason) {
            this.cities = cities;
            this.readable = readable;
            this.reason = reason;
        }

        public List<String> getCities() {
            return cities;
        }

        public boolean isReadable() {
            return readable;
        }

        public String getReason() {
            return reason;
        }

        boolean contains(String city) {
            return city != null && cities.contains(city);
        }
    }

    /**
     * 当前登录社保局的辖区范围。
     *
     * <p>读不到（链未就绪 / 本局未在链上注册 / 账号没有机构私钥）时返回 {@code readable=false}，
     * 上层据此返回空结果 —— 这是「辖区」这条边界的唯一来源，链下没有第二份。
     */
    private Scope scope() {
        AuthUser me = AuthContext.require();
        BureauCityService.CityLookup lookup = bureauCityService.lookupByInstitutionCode(me.getInstitutionCode());
        if (!lookup.isReadable()) {
            log.warn("读取社保局（accountId={}）的链上服务城市失败，辖区查询返回空结果：{}",
                    me.getAccountId(), lookup.getFailureReason());
            return new Scope(new ArrayList<String>(), false, lookup.getFailureReason());
        }
        if (lookup.getCities().isEmpty()) {
            log.warn("社保局（accountId={}）在链上没有任何服务城市，辖区查询返回空结果", me.getAccountId());
            return new Scope(new ArrayList<String>(), true, "本社保局尚未在链上授权任何服务城市");
        }
        return new Scope(lookup.getCities(), true, "");
    }

    // ------------------------------------------------------------------
    // GET /api/bureau/city/overview
    // ------------------------------------------------------------------

    /**
     * 辖区汇总：城市、单位数、职工数、参保状态分布、统筹余额与累计已缴。
     *
     * <p>职工数取 {@code getSponsorAccount().employeeCount} 的累加（链上权威，不受本服务的扫描上限影响）；
     * 状态分布则必须逐条读参保账户，因此受 {@value #MAX_EMPLOYEE_SCAN} 限制，
     * 被截断时 {@code statusTruncated=true} —— 宁可少算，也不让这个接口读穿节点。
     */
    public Map<String, Object> overview() {
        Scope scope = scope();
        if (!scope.isReadable() || scope.getCities().isEmpty()) {
            return overviewEmpty(scope);
        }
        return cache.getOrLoad("bureau:overview:" + scopeCacheKey(scope), OVERVIEW_TTL,
                () -> overviewUncached(scope));
    }

    private Map<String, Object> overviewEmpty(Scope scope) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cities", scope.getCities());
        out.put("chainReadable", scope.isReadable());
        out.put("reason", scope.getReason());
        out.put("sponsorCount", 0);
        out.put("employeeCount", 0);
        out.put("poolBalance", "0");
        out.put("paidTotal", "0");
        out.put("deposited", "0");
        out.put("statusDistribution", emptyDistribution());
        out.put("statusTruncated", false);
        return out;
    }

    private Map<String, Object> overviewUncached(Scope scope) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cities", scope.getCities());
        out.put("chainReadable", scope.isReadable());
        List<Map<String, Object>> sponsors = sponsorsInScope(scope);
        BigInteger poolBalance = BigInteger.ZERO;
        BigInteger paidTotal = BigInteger.ZERO;
        long employeeCount = 0;
        for (Map<String, Object> sponsor : sponsors) {
            poolBalance = poolBalance.add(toBigInt(sponsor.get("poolBalance")));
            paidTotal = paidTotal.add(toBigInt(sponsor.get("paidTotal")));
            employeeCount += toLong(sponsor.get("employeeCount"));
        }

        Map<String, Object> distribution = emptyDistribution();
        boolean truncated = false;
        int scanned = 0;
        for (Map<String, Object> sponsor : sponsors) {
            if (scanned >= MAX_EMPLOYEE_SCAN) {
                truncated = true;
                break;
            }
            for (String address : employeeAddresses(text(sponsor.get("address")), EMPLOYEE_PAGE_SIZE)) {
                if (scanned >= MAX_EMPLOYEE_SCAN) {
                    truncated = true;
                    break;
                }
                scanned++;
                Map<String, Object> account = insuranceOrNull(address);
                if (account == null) {
                    continue;
                }
                String statusName = text(account.get("statusName"));
                Object count = distribution.get(statusName);
                distribution.put(statusName, (count instanceof Number ? ((Number) count).longValue() : 0L) + 1);
            }
        }

        out.put("reason", "");
        out.put("sponsorCount", sponsors.size());
        out.put("employeeCount", employeeCount);
        out.put("poolBalance", poolBalance.toString());
        out.put("paidTotal", paidTotal.toString());
        // 辖区累计注资 = 单位统筹余额 + 累计已缴（注资只从统筹池出去缴费，不涉及其它流出）
        out.put("deposited", poolBalance.add(paidTotal).toString());
        out.put("statusDistribution", distribution);
        out.put("statusTruncated", truncated);
        out.put("statusScanned", scanned);
        return out;
    }

    private static Map<String, Object> emptyDistribution() {
        Map<String, Object> distribution = new LinkedHashMap<>();
        // 与合约 EmployeeStatus 一致：0 NONE / 1 ACTIVE / 2 SUSPENDED / 3 TRANSFERRED / 4 REMOVED / 5 RETIRED
        distribution.put("正常参保", 0L);
        distribution.put("已停缴", 0L);
        distribution.put("已转出", 0L);
        distribution.put("已注销", 0L);
        distribution.put("已退休", 0L);
        distribution.put("未知", 0L);
        return distribution;
    }

    // ------------------------------------------------------------------
    // GET /api/bureau/city/sponsors
    // ------------------------------------------------------------------

    /**
     * 辖区内的缴费单位（分页）。
     *
     * <p>取数：遍历链上 {@code getSponsorList} → 逐条 {@code getSponsorAccount} 拿 {@code city}
     * → 只保留城市落在本局辖区内的；再把链下平台账号（用户名 / 显示名 / 启用状态）按
     * 「机构编码 → 链上地址」关联上去，便于经办人员对照。
     *
     * <p>分页发生在<b>命中结果</b>上（先按城市过滤、再分页），因此 {@code total} 是辖区内单位数，
     * 不是链上全部单位数。
     */
    public Map<String, Object> sponsors(Integer offset, Integer limit) {
        Scope scope = scope();
        if (!scope.isReadable() || scope.getCities().isEmpty()) {
            return emptyPage(scope.getReason());
        }
        List<Map<String, Object>> rows = sponsorsInScope(scope);
        return page(rows, offset, limit, "");
    }

    /** 辖区内的缴费单位（不分页，内部复用）。 */
    private List<Map<String, Object>> sponsorsInScope(Scope scope) {
        return cache.getOrLoad("bureau:sponsors:" + scopeCacheKey(scope), SCAN_TTL,
                () -> sponsorsInScopeUncached(scope));
    }

    private List<Map<String, Object>> sponsorsInScopeUncached(Scope scope) {
        Map<String, Map<String, Object>> accountByAddress = platformAccountsByAddress(AuthService.ROLE_SPONSOR);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String address : allSponsorAddresses()) {
            Map<String, Object> account = sponsorAccountOrNull(address);
            if (account == null) {
                continue;
            }
            String city = text(account.get("city"));
            if (!scope.contains(city)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("address", address);
            row.put("name", text(account.get("name")));
            row.put("city", city);
            row.put("registeredAt", account.get("registeredAt"));
            row.put("poolBalance", toBigInt(account.get("poolBalance")).toString());
            row.put("paidTotal", toBigInt(account.get("paidTotal")).toString());
            row.put("employeeCount", toLong(account.get("employeeCount")));
            // 链下平台账号：机构编码 → 链上地址 反查（同一地址最多对应一个启用账号）
            Map<String, Object> platform = accountByAddress.get(normalize(address));
            row.put("institutionCode", platform == null ? "" : text(platform.get("institutionCode")));
            row.put("username", platform == null ? "" : text(platform.get("username")));
            row.put("displayName", platform == null ? "" : text(platform.get("displayName")));
            row.put("accountStatus", platform == null ? "" : text(platform.get("status")));
            rows.add(row);
        }
        return rows;
    }

    /** 链上全部已注册的缴费单位地址（分页遍历，带页数与空转保护）。 */
    private List<String> allSponsorAddresses() {
        List<String> addresses = new ArrayList<>();
        try {
            int offset = 0;
            for (int page = 0; page < MAX_SPONSOR_PAGES; page++) {
                CallResult result = chain.callAs(AuthService.ROLE_ADMIN, CORE, "getSponsorList",
                        ChainParams.uint(offset, "offset"), ChainParams.uint(SPONSOR_PAGE_SIZE, "limit"));
                List<Object> values = result == null ? null : result.getValues();
                List<String> page0 = firstAddressList(values);
                if (page0.isEmpty()) {
                    break;
                }
                addresses.addAll(page0);
                long total = toLong(values != null && values.size() > 1 ? values.get(1) : null);
                int nextOffset = (int) toLong(values != null && values.size() > 2 ? values.get(2) : null);
                if (nextOffset <= offset) {
                    break;
                }
                offset = nextOffset;
                if (total > 0 && offset >= total) {
                    break;
                }
            }
        } catch (Exception e) {
            // 读失败不抛：返回已扫到的部分，由调用方给出空或半量结果
            log.warn("读取链上缴费单位名单失败，辖区单位列表只返回已扫到的 {} 条：{}", addresses.size(), describe(e));
        }
        return addresses;
    }

    /** 单个缴费单位的链上账户；读不到返回 null（不中断整次扫描）。 */
    private Map<String, Object> sponsorAccountOrNull(String address) {
        String key = "chain:sponsor:" + normalize(address);
        Optional<Object> cached = cache.get(key);
        if (cached.isPresent()) {
            return mapOrNull(cached.get());
        }
        try {
            Map<String, Object> account = ChainViewMapper.mapStruct(
                    chain.callAs(AuthService.ROLE_ADMIN, CORE, "getSponsorAccount",
                            ChainParams.address(address, "缴费单位地址")),
                    ChainViewMapper.SPONSOR_ACCOUNT);
            cache.put(key, account, ACCOUNT_TTL);
            return account;
        } catch (RuntimeException e) {
            cache.put(key, nullMarker(), MISS_TTL);
            log.debug("读取缴费单位 {} 的链上账户失败，跳过：{}", address, describe(e));
            return null;
        }
    }

    // ------------------------------------------------------------------
    // GET /api/bureau/city/employees
    // ------------------------------------------------------------------

    /**
     * 辖区内的参保职工（分页）。
     *
     * <p>判据：职工参保账户里的 {@code city} 落在本局辖区内（职工参保地 = 其所属单位所在城市）。
     * {@code sponsor} 参数可选，传了就只看该单位名下（该单位必须属于本辖区，否则 403）。
     *
     * <p>逐条读参保账户是 O(n) 的（合约没有批量 getter），因此扫描量受
     * {@value #MAX_EMPLOYEE_SCAN} 限制，超出部分 {@code truncated=true}。
     */
    public Map<String, Object> employees(Integer offset, Integer limit, String sponsor) {
        Scope scope = scope();
        if (!scope.isReadable() || scope.getCities().isEmpty()) {
            return emptyPage(scope.getReason());
        }

        String sponsorFilter = trimToNull(sponsor);
        String cacheKey = "bureau:employees:" + scopeCacheKey(scope) + ":"
                + (sponsorFilter == null ? "*" : sponsorFilter.toLowerCase());
        Map<String, Object> scan = cache.getOrLoad(cacheKey, SCAN_TTL,
                () -> employeeScan(scope, sponsorFilter));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) scan.get("rows");
        Map<String, Object> page = page(rows, offset, limit, "");
        page.put("truncated", Boolean.TRUE.equals(scan.get("truncated")));
        page.put("scanned", scan.get("scanned"));
        // 一并返回辖区城市：养老金支出等复用本方法的调用方需要它来展示/判断边界
        page.put("cities", scope.getCities());
        return page;
    }

    /** 一次取全辖区职工，缓存后再分页；养老金总览也会复用这份扫描结果。 */
    private Map<String, Object> employeeScan(Scope scope, String sponsorFilter) {
        List<Map<String, Object>> targets;
        if (sponsorFilter == null) {
            targets = sponsorsInScope(scope);
        } else {
            Map<String, Object> target = null;
            for (Map<String, Object> candidate : sponsorsInScope(scope)) {
                if (sponsorFilter.equalsIgnoreCase(text(candidate.get("address")))) {
                    target = candidate;
                    break;
                }
            }
            if (target == null) {
                throw new BizException(ErrorCode.FORBIDDEN,
                        "该缴费单位不属于本社保局（服务城市：" + String.join("、", scope.getCities()) + "）");
            }
            targets = new ArrayList<>();
            targets.add(target);
        }

        Map<String, Map<String, Object>> accountByAddress = platformAccountsByAddress(AuthService.ROLE_EMPLOYEE);
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean truncated = false;
        int scanned = 0;
        for (Map<String, Object> sponsorRow : targets) {
            if (scanned >= MAX_EMPLOYEE_SCAN) {
                truncated = true;
                break;
            }
            String sponsorAddress = text(sponsorRow.get("address"));
            for (String employee : employeeAddresses(sponsorAddress, EMPLOYEE_PAGE_SIZE)) {
                if (scanned >= MAX_EMPLOYEE_SCAN) {
                    truncated = true;
                    break;
                }
                scanned++;
                Map<String, Object> account = insuranceOrNull(employee);
                if (account == null) {
                    continue;
                }
                if (!scope.contains(text(account.get("city")))) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>(account);
                row.put("address", employee);
                row.put("sponsorName", text(sponsorRow.get("name")));
                Map<String, Object> platform = accountByAddress.get(normalize(employee));
                row.put("username", platform == null ? "" : text(platform.get("username")));
                row.put("displayName", platform == null ? "" : text(platform.get("displayName")));
                row.put("accountStatus", platform == null ? "" : text(platform.get("status")));
                rows.add(row);
            }
        }

        Map<String, Object> scan = new LinkedHashMap<>();
        scan.put("rows", rows);
        scan.put("truncated", truncated);
        scan.put("scanned", scanned);
        return scan;
    }

    /** 某单位名下的职工地址（ADMIN 代读）；读失败返回空列表，不中断扫描。 */
    private List<String> employeeAddresses(String sponsorAddress, int limit) {
        if (sponsorAddress == null || sponsorAddress.isEmpty()) {
            return new ArrayList<>();
        }
        String key = "chain:persons:" + normalize(sponsorAddress) + ":" + limit;
        Optional<Object> cached = cache.get(key);
        if (cached.isPresent() && cached.get() instanceof List) {
            @SuppressWarnings("unchecked")
            List<String> hit = (List<String>) cached.get();
            return new ArrayList<>(hit);
        }
        try {
            CallResult result = chain.callAs(AuthService.ROLE_ADMIN, CORE, "getPersonList",
                    ChainParams.address(sponsorAddress, "缴费单位地址"),
                    ChainParams.uint(0, "offset"), ChainParams.uint(limit, "limit"));
            List<String> addresses = firstAddressList(result == null ? null : result.getValues());
            cache.put(key, addresses, SCAN_TTL);
            return addresses;
        } catch (RuntimeException e) {
            cache.put(key, new ArrayList<String>(), MISS_TTL);
            log.debug("读取缴费单位 {} 的职工名单失败，跳过：{}", sponsorAddress, describe(e));
            return new ArrayList<>();
        }
    }

    // ------------------------------------------------------------------
    // GET /api/bureau/city/insurance
    // ------------------------------------------------------------------

    /**
     * 单个职工的<b>参保记录</b>（参保账户全字段 + 中文状态 + 所属单位名称）。
     *
     * <p><b>辖区校验</b>：该职工参保城市必须落在本局辖区内，否则 403 —— 社保局只能看本辖区的参保人，
     * 这是链下这条边界的落点（链上那份判权不含 BUREAU，只能在这里守）。
     */
    public Map<String, Object> insurance(String employee) {
        String address = ChainParams.address(employee, "职工地址");
        Scope scope = requireReadableScope();
        Map<String, Object> account = requireEmployeeInScope(address, scope);
        account.put("address", address);
        account.put("sponsorName", sponsorNameOf(account));
        return account;
    }

    // ------------------------------------------------------------------
    // GET /api/bureau/city/payments
    // ------------------------------------------------------------------

    /**
     * 单个职工的<b>缴纳记录</b>（分页）。同样先做辖区校验，越界直接 403。
     */
    public Map<String, Object> payments(String employee, int offset, int limit) {
        String address = ChainParams.address(employee, "职工地址");
        Scope scope = requireReadableScope();
        requireEmployeeInScope(address, scope);

        CallResult result = chain.callAs(AuthService.ROLE_ADMIN, CORE, "getPaymentRecords",
                ChainParams.address(address, "职工地址"), ChainParams.uint(offset, "offset"),
                ChainParams.uint(limit, "limit"));
        // 与 AccountBizService#getPaymentRecords 同一套解析：返回的是「结构体数组」，逐条拆字段
        Map<String, Object> view = new LinkedHashMap<>();
        List<Map<String, Object>> records = new ArrayList<>();
        List<Object> values = result == null ? null : result.getValues();
        if (values != null && !values.isEmpty() && values.get(0) instanceof List) {
            for (Object item : (List<?>) values.get(0)) {
                Map<String, Object> record = new LinkedHashMap<>();
                if (item instanceof List) {
                    List<?> fields = (List<?>) item;
                    for (int i = 0; i < ChainViewMapper.PAYMENT_RECORD.length && i < fields.size(); i++) {
                        record.put(ChainViewMapper.PAYMENT_RECORD[i], fields.get(i));
                    }
                } else {
                    record.put("value", item);
                }
                records.add(record);
            }
        }
        view.put("employee", address);
        view.put("records", records);
        view.put("total", values != null && values.size() > 1 ? values.get(1) : null);
        view.put("nextOffset", values != null && values.size() > 2 ? values.get(2) : null);
        return view;
    }

    // ------------------------------------------------------------------
    // 内部：辖区校验
    // ------------------------------------------------------------------

    private Scope requireReadableScope() {
        Scope scope = scope();
        if (!scope.isReadable()) {
            throw new BizException(ErrorCode.CHAIN_NOT_READY,
                    "读取本社保局的链上服务城市失败，无法判断辖区：" + scope.getReason());
        }
        if (scope.getCities().isEmpty()) {
            throw new BizException(ErrorCode.FORBIDDEN,
                    "本社保局尚未在链上授权任何服务城市，暂无可查询的辖区");
        }
        return scope;
    }

    /** 读参保账户并校验辖区；不在辖区内直接 403。 */
    private Map<String, Object> requireEmployeeInScope(String address, Scope scope) {
        Map<String, Object> account = insuranceOrNull(address);
        if (account == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "未查到该职工的参保账户：" + address);
        }
        String city = text(account.get("city"));
        if (!scope.contains(city)) {
            throw new BizException(ErrorCode.FORBIDDEN,
                    "该职工的参保城市（" + (city.isEmpty() ? "未知" : city) + "）不属于本社保局（服务城市："
                            + String.join("、", scope.getCities()) + "）");
        }
        return account;
    }

    /** 单个职工的参保账户视图（补中文状态）；读不到返回 null。 */
    private Map<String, Object> insuranceOrNull(String employee) {
        String normalized = normalize(employee);
        if (normalized == null || normalized.isEmpty()) {
            return null;
        }
        String key = "chain:insurance:" + normalized;
        Optional<Object> cached = cache.get(key);
        if (cached.isPresent()) {
            return mapOrNull(cached.get());
        }
        try {
            Map<String, Object> account = ChainViewMapper.mapStruct(
                    chain.callAs(AuthService.ROLE_ADMIN, CORE, "getInsuranceAccount",
                            ChainParams.address(employee, "职工地址")),
                    ChainViewMapper.INSURANCE_ACCOUNT);
            Object status = account.get("status");
            account.put("statusName", status == null ? "未知" : ChainViewMapper.employeeStatusNameCn(status));
            cache.put(key, account, ACCOUNT_TTL);
            return account;
        } catch (RuntimeException e) {
            cache.put(key, nullMarker(), MISS_TTL);
            log.debug("读取职工 {} 的参保账户失败，跳过：{}", employee, describe(e));
            return null;
        }
    }

    /** 参保账户里的所属单位名称；读不到给空串（与 AccountBizService#sponsorNameOf 同一口径）。 */
    private String sponsorNameOf(Map<String, Object> account) {
        String address = text(account.get("sponsor")).trim();
        if (!address.startsWith("0x") || address.length() != 42) {
            return "";
        }
        Map<String, Object> sponsor = sponsorAccountOrNull(address);
        return sponsor == null ? "" : text(sponsor.get("name"));
    }

    // ------------------------------------------------------------------
    // 内部：链下平台账号关联
    // ------------------------------------------------------------------

    /**
     * 某角色下「链上地址(小写) → 平台账号摘要」的映射。
     *
     * <p>用途：把链上名单和链下平台账号对上，让经办人既能看到机构地址，也能看到登录账号与启用状态。
     * 同一地址被多个账号占用时保留第一个（历史脏数据下不报错，只不重复展示）。
     */
    private Map<String, Map<String, Object>> platformAccountsByAddress(String role) {
        Map<String, String> addressByCode = institutionKeyService.addressByCode();
        Map<String, Map<String, Object>> byAddress = new LinkedHashMap<>();
        for (Account account : authService.listAccountEntities(role)) {
            String code = account.getInstitutionCode() == null ? null : account.getInstitutionCode().trim();
            if (code == null || code.isEmpty()) {
                continue;
            }
            String address = addressByCode.get(code);
            String key = normalize(address);
            if (key == null || key.isEmpty() || byAddress.containsKey(key)) {
                continue;
            }
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("institutionCode", code);
            summary.put("username", account.getUsername() == null ? "" : account.getUsername());
            summary.put("displayName", account.getDisplayName() == null ? "" : account.getDisplayName());
            summary.put("status", account.getStatus() == null ? "" : account.getStatus());
            byAddress.put(key, summary);
        }
        return byAddress;
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    /** 在命中结果上分页（先过滤、后分页），返回 {@code rows/total/offset/limit/nextOffset}。 */
    private Map<String, Object> page(List<Map<String, Object>> rows, Integer offset, Integer limit, String reason) {
        int from = offset == null || offset < 0 ? 0 : offset;
        int size = limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        int end = Math.min(from + size, rows.size());
        List<Map<String, Object>> slice = from >= rows.size() ? new ArrayList<>() : new ArrayList<>(rows.subList(from, end));
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("rows", slice);
        view.put("total", rows.size());
        view.put("offset", from);
        view.put("limit", size);
        view.put("nextOffset", end);
        view.put("reason", reason == null ? "" : reason);
        return view;
    }

    private Map<String, Object> emptyPage(String reason) {
        Map<String, Object> view = page(new ArrayList<>(), 0, DEFAULT_LIMIT, reason);
        view.put("truncated", false);
        view.put("scanned", 0);
        return view;
    }

    /** 合约返回的 {@code address[]}（ABI 解码后是 List）→ 字符串列表。 */
    private static List<String> firstAddressList(List<Object> values) {
        List<String> addresses = new ArrayList<>();
        if (values == null || values.isEmpty() || !(values.get(0) instanceof List)) {
            return addresses;
        }
        for (Object item : (List<?>) values.get(0)) {
            if (item == null) {
                continue;
            }
            String address = String.valueOf(item).trim();
            if (!address.isEmpty()) {
                addresses.add(address);
            }
        }
        return addresses;
    }

    private static String normalize(String address) {
        return address == null ? null : address.trim().toLowerCase();
    }

    /** 缓存 key 里带上账号与辖区，避免不同社保局之间串数据。 */
    private static String scopeCacheKey(Scope scope) {
        AuthUser me = AuthContext.get();
        String actor = me == null || me.getAccountId() == null ? "anonymous" : String.valueOf(me.getAccountId());
        return actor + ":" + String.join(",", scope.getCities());
    }

    private static Map<String, Object> nullMarker() {
        Map<String, Object> marker = new LinkedHashMap<>();
        marker.put(NULL_MARKER, true);
        return marker;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOrNull(Object value) {
        if (!(value instanceof Map)) {
            return null;
        }
        Map<?, ?> map = (Map<?, ?>) value;
        if (Boolean.TRUE.equals(map.get(NULL_MARKER))) {
            return null;
        }
        return (Map<String, Object>) value;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long toLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    /** 合约的 uint256 可能被解成 BigInteger / BigDecimal / 字符串，统一成 BigInteger 便于累加。 */
    private static BigInteger toBigInt(Object value) {
        if (value instanceof BigInteger) {
            return (BigInteger) value;
        }
        if (value instanceof BigDecimal) {
            return ((BigDecimal) value).toBigInteger();
        }
        if (value instanceof Number) {
            return BigInteger.valueOf(((Number) value).longValue());
        }
        if (value == null) {
            return BigInteger.ZERO;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return BigInteger.ZERO;
        }
        try {
            return new BigInteger(text);
        } catch (NumberFormatException e) {
            try {
                return new BigDecimal(text).toBigInteger();
            } catch (NumberFormatException ignored) {
                return BigInteger.ZERO;
            }
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String describe(Throwable e) {
        String message = e.getMessage();
        if (message != null && !message.trim().isEmpty()) {
            return message.trim();
        }
        return e.getClass().getSimpleName();
    }
}
