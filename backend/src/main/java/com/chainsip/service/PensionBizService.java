package com.chainsip.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.chainsip.chain.dto.SendResult;
import com.chainsip.common.BizException;
import com.chainsip.common.ErrorCode;
import com.chainsip.config.ChainProperties;
import com.chainsip.entity.ChainEvent;
import com.chainsip.entity.PensionDetermination;
import com.chainsip.mapper.ChainEventMapper;
import com.chainsip.policy.entity.PolicyContext;
import com.chainsip.policy.service.PolicyContextBuilder;
import com.chainsip.policy.service.PolicyQueryService;
import com.chainsip.security.AuthContext;
import com.chainsip.security.AuthUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 社保局的「养老金支出」：办理退休、按月发放、发放流水与支出统计。
 *
 * <p>这是「缴纳（收入侧）」的对称面。之前系统只有 {@code makePayment} 一条资金流入链，
 * 职工个人账户与城市统筹基金池只进不出；本服务补上支出侧：
 * <ol>
 *   <li><b>退休</b>（{@code retireEmployee}）：达到 60 岁且累计缴费满 180 个月的职工，由社保局经办
 *       转为 RETIRED，并锁定"每月个人账户发放额"（退休时个人账户余额 ÷ 139）；</li>
 *   <li><b>发放</b>（{@code claimPension}）：每 30 天发一次，由两部分组成——
 *       基础养老金从<b>城市统筹基金池</b>出，个人账户养老金从<b>职工个人账户</b>出；</li>
 *   <li><b>流水</b>：链上事件 {@code PensionClaimed} 索引到 {@code cs_chain_event}，
 *       链上不存可遍历数组（核心合约已逼近 24576 B 上限）。</li>
 * </ol>
 *
 * <h2>三条必须守住的边界</h2>
 * <ol>
 *   <li><b>链上身份一律 ADMIN 代签</b>：{@code retireEmployee} 是 {@code onlyAdmin}，
 *       社保局自己的私钥发必然回滚。辖区边界在链下守（与 {@link BureauOverviewService} 同一口径）。</li>
 *   <li><b>辖区以链上服务城市为唯一权威</b>：发放前先读该职工的参保城市，必须落在本局服务城市内，
 *       否则 403 且不发交易。</li>
 *   <li><b>门槛与金额改由政策参数驱动</b>：退休年龄、最低缴费月数、计发月数、基础养老金年计发比例、
 *       发放周期都从 {@link PolicyContextBuilder} 解析（参数表 → 字典默认 → 合约常量兜底），
 *       这里只做"提前告知"的预校验；真正的裁决仍在链上。</li>
 * </ol>
 */
@Service
public class PensionBizService {

    private static final Logger log = LoggerFactory.getLogger(PensionBizService.class);

    private static final String CORE = ChainProperties.CORE;

    /** 辖区职工扫描：每次 100 条，最多 3 页（与 BureauOverviewService 的 300 条上限对齐）。 */
    private static final int EMPLOYEE_PAGE = 100;
    private static final int MAX_PAGES = 3;

    /** 合约 EmployeeStatus 编号。 */
    private static final int STATUS_ACTIVE = 1;
    private static final int STATUS_SUSPENDED = 2;
    private static final int STATUS_RETIRED = 5;

    private final ChainTxService chain;
    private final BureauOverviewService overviewService;
    private final BureauCityService bureauCityService;
    private final InstitutionKeyService institutionKeyService;
    private final ChainEventMapper eventMapper;
    private final PolicyContextBuilder contextBuilder;
    private final PensionDeterminationService determinationService;

    public PensionBizService(ChainTxService chain, BureauOverviewService overviewService,
                             BureauCityService bureauCityService, InstitutionKeyService institutionKeyService,
                             ChainEventMapper eventMapper, PolicyContextBuilder contextBuilder,
                             PensionDeterminationService determinationService) {
        this.chain = chain;
        this.overviewService = overviewService;
        this.bureauCityService = bureauCityService;
        this.institutionKeyService = institutionKeyService;
        this.eventMapper = eventMapper;
        this.contextBuilder = contextBuilder;
        this.determinationService = determinationService;
    }

    // ------------------------------------------------------------------
    // 政策口径
    // ------------------------------------------------------------------

    /**
     * 退休与发放规则（页面顶部说明用）：数值来自政策参数表，页面不再写死文案。
     *
     * <p>返回里带 {@code parameters} 数组（每个参数的值、版本号、生效日、政策文号），
     * 前端直接渲染成"当前口径 + 版本"表；{@code policyVersionRef} 是本次规则的版本摘要。
     */
    public Map<String, Object> rules() {
        PolicyContext ctx = currentContext();
        int payoutMonths = ctx.paymentMonthsOf(ctx.getRetireAgeMale());
        String ratePercent = ctx.getBasicPensionYearlyRate()
                .multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("retireAge", ctx.getRetireAgeMale());
        out.put("minPaidMonths", ctx.getMinPaidMonths());
        out.put("payoutMonths", payoutMonths);
        out.put("cycleDays", ctx.getPensionCycleDays());
        out.put("basicRate", ctx.getBasicPensionYearlyRate().toPlainString());
        out.put("basicRatePercent", ratePercent);
        out.put("baseFloorValue", ctx.getBaseFloorValue().toPlainString());
        out.put("baseCeilingValue", ctx.getBaseCeilingValue().toPlainString());
        out.put("employeeContribRate", ctx.getEmployeeContribRate().toPlainString());
        out.put("employerContribRate", ctx.getEmployerContribRate().toPlainString());
        out.put("baseFormula", "基础养老金 = 缴费基数 × 缴费月数 × 年计发比例 " + ratePercent
                + "% ÷ 12（从参保城市的统筹基金池支付）");
        out.put("personalFormula", "个人账户养老金 = 退休时个人账户余额 ÷ 计发月数（"
                + ctx.getRetireAgeMale() + " 周岁为 " + payoutMonths + " 个月，从职工个人账户支付）");
        out.put("parameters", contextBuilder.describeEffective(currentRegion(), LocalDate.now()));
        out.put("policyVersionRef", contextBuilder.versionRef(currentRegion(), LocalDate.now()));
        // 渐进式延迟退休：链上仍按合约 60 周岁裁决，这里给经办人员一个"按政策应到多少岁"的参考
        LocalDate today = LocalDate.now();
        out.put("legalRetireAgeText", ctx.legalRetireAgeText(today));
        out.put("legalRetireAgeMonths", ctx.legalRetireAgeMonths(today));
        out.put("retireDelayStarted", ctx.retireDelayStarted(today));
        out.put("retireDelayNote", ctx.retireDelayStarted(today)
                ? "按 2025-01-01 起的渐进式延迟方案（每 4 个月延迟 1 个月、目标 63 周岁），"
                + "当前法定退休年龄约为 " + ctx.legalRetireAgeText(today)
                + "；链上合约仍以 60 周岁为硬门槛，延迟退休二期上链。"
                : "延迟退休政策尚未启动，按原法定年龄执行。");
        out.put("note", "特殊情形（病退 / 提前退休）可由社保局走「特殊审批退休」，豁免年龄与缴费月数门槛，"
                + "豁免事实随链上事件 EmployeeRetired 留痕。");
        return out;
    }

    // ------------------------------------------------------------------
    // GET /api/bureau/pension/overview
    // ------------------------------------------------------------------

    /**
     * 支出总览：辖区退休人数、本期待发人数与金额、可办退休人数、累计发放。
     *
     * <p>链上只暴露单个职工的余额与状态，没有"按城市统计"的聚合接口，因此这里遍历辖区职工
     * （最多 300 条）自行聚合；超出上限时 {@code truncated=true}，宁可少算也不读穿节点。
     */
    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        ScopeEmployees scoped = scopedEmployees();
        out.put("cities", scoped.cities);
        out.put("chainReadable", scoped.reason.isEmpty());
        out.put("reason", scoped.reason);
        out.put("employeeCount", scoped.rows.size());
        out.put("truncated", scoped.truncated);

        long retireeCount = 0;
        long pendingCount = 0;
        long eligibleCount = 0;
        BigInteger monthlyPayable = BigInteger.ZERO;
        BigInteger pendingAmount = BigInteger.ZERO;
        long now = nowSeconds();
        PolicyContext ctx = contextFor(scoped.cities);

        List<String> retireeAddresses = new ArrayList<>();
        for (Map<String, Object> row : scoped.rows) {
            int status = (int) toLong(row.get("status"));
            if (status == STATUS_RETIRED) {
                retireeCount++;
                retireeAddresses.add(text(row.get("address")));
                BigInteger monthly = monthlyOf(row, ctx);
                monthlyPayable = monthlyPayable.add(monthly);
                if (canClaim(row, ctx, now)) {
                    pendingCount++;
                    pendingAmount = pendingAmount.add(monthly);
                }
            } else if (isRetirable(row, ctx)) {
                eligibleCount++;
            }
        }

        Map<String, Object> claimed = claimedStats(retireeAddresses);
        out.put("retireeCount", retireeCount);
        out.put("pendingCount", pendingCount);
        out.put("pendingAmount", pendingAmount.toString());
        out.put("monthlyPayable", monthlyPayable.toString());
        out.put("eligibleCount", eligibleCount);
        out.put("claimedCount", claimed.get("count"));
        out.put("claimedAmount", claimed.get("amount"));
        out.put("claimedCovered", claimed.get("covered"));
        return out;
    }

    // ------------------------------------------------------------------
    // GET /api/bureau/pension/eligible
    // ------------------------------------------------------------------

    /**
     * 待办退休：辖区内所有未退休职工，带「还差多少」与是否达标。
     *
     * <p>为什么不只返回达标的：新部署的链上数据往往还没攒够 180 个月，只发达标的人会让页面看起来"空"，
     * 经办人员也无法判断谁快到了。因此这里返回全部在保/停缴职工，用 {@code qualified} 区分，
     * 未达标的给出 {@code ageGap} 岁、{@code monthsGap} 个月的差距，仍可走「特殊审批退休」。
     */
    public Map<String, Object> eligible(Integer offset, Integer limit) {
        List<Map<String, Object>> rows = new ArrayList<>();
        ScopeEmployees scoped = scopedEmployees();
        PolicyContext ctx = contextFor(scoped.cities);
        for (Map<String, Object> row : scoped.rows) {
            int status = (int) toLong(row.get("status"));
            if (status != STATUS_ACTIVE && status != STATUS_SUSPENDED) {
                continue;
            }
            if (toLong(row.get("activeTransferId")) != 0) {
                continue;
            }
            Map<String, Object> view = pensionView(row, ctx);
            int age = (int) toLong(row.get("age"));
            int paidMonths = (int) toLong(row.get("paidMonths"));
            int ageGap = Math.max(0, ctx.getRetireAgeMale() - age);
            int monthsGap = Math.max(0, ctx.getMinPaidMonths() - paidMonths);
            view.put("ageGap", ageGap);
            view.put("monthsGap", monthsGap);
            view.put("qualified", ageGap == 0 && monthsGap == 0);
            rows.add(view);
        }
        Map<String, Object> page = page(rows, offset, limit);
        page.put("truncated", scoped.truncated);
        page.put("reason", scoped.reason);
        return page;
    }

    // ------------------------------------------------------------------
    // GET /api/bureau/pension/retirees
    // ------------------------------------------------------------------

    /**
     * 已退休人员（本辖区）：每月应发金额、已领月数、下次可领时间、个人账户剩余。
     *
     * <p>列表上直接给出 {@code canClaim}，前端据此点亮/置灰「发放」按钮——
     * 真正的 30 天周期护栏仍在合约里，这里只是提前告知。
     */
    public Map<String, Object> retirees(Integer offset, Integer limit) {
        List<Map<String, Object>> rows = new ArrayList<>();
        ScopeEmployees scoped = scopedEmployees();
        long now = nowSeconds();
        PolicyContext ctx = contextFor(scoped.cities);
        List<String> retireeAddresses = new ArrayList<>();
        for (Map<String, Object> row : scoped.rows) {
            if ((int) toLong(row.get("status")) != STATUS_RETIRED) {
                continue;
            }
            retireeAddresses.add(text(row.get("address")));
            Map<String, Object> view = pensionView(row, ctx);
            long lastPensionAt = toLong(row.get("lastPensionAt"));
            long cycleSeconds = cycleSeconds(ctx);
            view.put("lastPensionAt", lastPensionAt);
            view.put("nextClaimAt", lastPensionAt == 0 ? 0 : lastPensionAt + cycleSeconds);
            view.put("canClaim", canClaim(row, ctx, now));
            view.put("cycleDays", ctx.getPensionCycleDays());
            rows.add(view);
        }
        // 附带核定单信息：前端在「已退休」表里显示核定单号 / 快照哈希前 8 位 / 复算入口
        Map<String, PensionDetermination> determinations = determinationService.latestByEmployees(retireeAddresses);
        for (Map<String, Object> view : rows) {
            PensionDetermination determination = determinations.get(text(view.get("address")).toLowerCase());
            if (determination == null) {
                view.put("determinationId", null);
                view.put("determinationStatus", "MISSING");
                continue;
            }
            view.put("determinationId", determination.getId());
            view.put("determinationStatus", determination.getStatus());
            view.put("determinationSource", determination.getSource());
            view.put("snapshotHashShort", determination.getPolicySnapshotHash() == null ? null
                    : determination.getPolicySnapshotHash().substring(0,
                    Math.min(8, determination.getPolicySnapshotHash().length())));
        }
        Map<String, Object> page = page(rows, offset, limit);
        page.put("truncated", scoped.truncated);
        page.put("reason", scoped.reason);
        return page;
    }

    // ------------------------------------------------------------------
    // POST /api/bureau/pension/retire
    // ------------------------------------------------------------------

    /**
     * 办理退休（{@code retireEmployee}，ADMIN 代签）。
     *
     * <p>自本期起，退休办理统一走 {@link PensionDeterminationService#determine}：
     * 系统先按「核定当天的政策参数」算出基础养老金与个人账户养老金、生成带快照的核定单，
     * 再发链并把交易哈希回写到核定单上。这样"为什么是这个数"有据可查、事后可复算。
     *
     * @param force 特殊审批：跳过年龄与缴费月数门槛（病退 / 提前退休），链上留痕
     */
    public SendResult retire(String employee, boolean force) {
        log.info("办理退休（走核定单）：employee={} force={}", employee, force);
        return determinationService.determine(employee, force);
    }

    // ------------------------------------------------------------------
    // POST /api/bureau/pension/claim
    // ------------------------------------------------------------------

    /**
     * 发放一个月养老金（{@code claimPension}，ADMIN 代签）。
     *
     * <p>资金去向：基础养老金扣参保城市统筹基金池，个人账户养老金扣职工个人账户；
     * 统筹池不足时合约回滚 {@code CityPoolInsufficient}，前端显示"统筹基金池余额不足"。
     */
    public SendResult claim(String employee) {
        String address = ChainParams.address(employee, "职工地址");
        Map<String, Object> account = overviewService.insurance(address);

        int status = (int) toLong(account.get("status"));
        if (status != STATUS_RETIRED) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该职工尚未办理退休，不能领取养老金");
        }
        PolicyContext ctx = currentContext();
        long cycleSeconds = cycleSeconds(ctx);
        long lastPensionAt = toLong(account.get("lastPensionAt"));
        long now = nowSeconds();
        if (lastPensionAt != 0 && now < lastPensionAt + cycleSeconds) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "本期养老金已发放，请在 " + (lastPensionAt + cycleSeconds - now) / 86400 + " 天后再发");
        }

        log.info("发放养老金：employee={} monthly={}", address, monthlyOf(account, ctx));
        return chain.sendAs(AuthService.ROLE_ADMIN, CORE, "claimPension", address);
    }

    // ------------------------------------------------------------------
    // POST /api/employee/pension/claim（职工自助领取）
    // ------------------------------------------------------------------

    /**
     * 职工本人领取本月养老金。
     *
     * <p>与社保局代发（{@link #claim}）走同一个链上函数，区别只在签名身份：
     * 这里是职工自己的私钥（合约允许 {@code msg.sender == employee}），社保局那条走 ADMIN 代签。
     * 因此不需要辖区校验——读的是自己的账户，合约的 {@code _requireAccountReader} 天然放行。
     */
    public SendResult claimMine() {
        String code = chain.currentInstitutionCode();
        String address = institutionKeyService.resolveChainAccount(code).getAddress();

        Map<String, Object> view = ChainViewMapper.mapStruct(
                chain.call(CORE, "getInsuranceAccount", ChainParams.address(address, "职工地址")),
                ChainViewMapper.INSURANCE_ACCOUNT);

        if (toLong(view.get("status")) != STATUS_RETIRED) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "你尚未办理退休（当前状态：" + ChainViewMapper.employeeStatusNameCn(view.get("status"))
                            + "），不能领取养老金");
        }
        long last = toLong(view.get("lastPensionAt"));
        long now = nowSeconds();
        long cycleSeconds = cycleSeconds(currentContext());
        if (last != 0 && now < last + cycleSeconds) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "本期养老金已发放，请在 " + (last + cycleSeconds - now) / 86400 + " 天后再领");
        }

        log.info("职工自助领取养老金：employee={}", address);
        return chain.send(code, CORE, "claimPension", address);
    }

    // ------------------------------------------------------------------
    // GET /api/bureau/pension/records
    // ------------------------------------------------------------------

    /**
     * 某职工的养老金发放流水（链上事件索引）。
     *
     * <p>链上不存发放数组（合约大小限制），流水来自 {@code PensionClaimed} 事件的索引表。
     * 因此这里能查到的是"经本平台发起的发放"，链上直接发交易且未走本后端的不在其中——
     * 与 {@code PaymentMade} 的处理方式完全一致。金额的权威值仍以链上余额变动为准。
     */
    public Map<String, Object> records(String employee, int offset, int limit) {
        String address = ChainParams.address(employee, "职工地址");
        // 辖区校验（同时确保该职工存在）；越界直接 403
        overviewService.insurance(address);

        int from = Math.max(0, offset);
        int size = limit <= 0 ? 20 : Math.min(limit, 100);

        Long total = eventMapper.selectCount(recordWrapper(address));

        QueryWrapper<ChainEvent> pageWrapper = recordWrapper(address);
        pageWrapper.last("limit " + size + " offset " + from);
        List<ChainEvent> events = eventMapper.selectList(pageWrapper);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (ChainEvent event : events) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("txHash", event.getTxHash());
            row.put("blockNumber", event.getBlockNumber());
            row.put("timestamp", event.getCreatedAt() == null ? null
                    : event.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toEpochSecond());
            row.put("totalAmount", event.getAmount() == null ? "0" : event.getAmount().toPlainString());
            row.put("personalAmount", event.getPersonalAmount() == null ? "0" : event.getPersonalAmount().toPlainString());
            row.put("overallAmount", event.getOverallAmount() == null ? "0" : event.getOverallAmount().toPlainString());
            rows.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("employee", address);
        out.put("records", rows);
        out.put("total", total == null ? 0 : total);
        out.put("offset", from);
        out.put("limit", size);
        out.put("nextOffset", from + rows.size());
        return out;
    }

    // ------------------------------------------------------------------
    // 内部：辖区职工
    // ------------------------------------------------------------------

    /** 辖区职工扫描结果（与前端分页无关，这里是"一次取全"用于聚合）。 */
    private static final class ScopeEmployees {
        final List<Map<String, Object>> rows;
        final boolean truncated;
        final String reason;
        final List<String> cities;

        ScopeEmployees(List<Map<String, Object>> rows, boolean truncated, String reason, List<String> cities) {
            this.rows = rows;
            this.truncated = truncated;
            this.reason = reason;
            this.cities = cities;
        }
    }

    /**
     * 取本辖区内的全部职工（复用辖区总览的扫描逻辑，最多 3 页 / 300 条）。
     *
     * <p>注意每次 {@code employees()} 都会重新遍历单位与职工，翻页等于重扫；
     * 因此这里最多 3 页，且演示/中小规模辖区通常 1 页就够。
     */
    @SuppressWarnings("unchecked")
    private ScopeEmployees scopedEmployees() {
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean truncated = false;
        String reason = "";
        int offset = 0;
        List<String> cities = new ArrayList<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            Map<String, Object> data = overviewService.employees(offset, EMPLOYEE_PAGE, null);
            reason = text(data.get("reason"));
            Object cityList = data.get("cities");
            if (cities.isEmpty() && cityList instanceof List) {
                for (Object city : (List<?>) cityList) {
                    cities.add(String.valueOf(city));
                }
            }
            Object list = data.get("rows");
            if (list instanceof List) {
                for (Object item : (List<?>) list) {
                    if (item instanceof Map) {
                        rows.add((Map<String, Object>) item);
                    }
                }
            }
            truncated = Boolean.TRUE.equals(data.get("truncated"));
            long total = toLong(data.get("total"));
            offset += EMPLOYEE_PAGE;
            if (!reason.isEmpty() || offset >= total || truncated) {
                break;
            }
        }
        if (cities.isEmpty()) {
            cities = citiesOfCurrentBureau();
        }
        return new ScopeEmployees(rows, truncated, reason, cities);
    }

    /** 辖区城市兜底：直接读链上本局服务城市（职工列表为空时页面仍要显示辖区）。 */
    private List<String> citiesOfCurrentBureau() {
        try {
            AuthUser me = AuthContext.require();
            BureauCityService.CityLookup lookup =
                    bureauCityService.lookupByInstitutionCode(me.getInstitutionCode());
            return lookup.isReadable() ? new ArrayList<>(lookup.getCities()) : new ArrayList<String>();
        } catch (RuntimeException e) {
            log.debug("读取本局服务城市失败：{}", e.getMessage());
            return new ArrayList<>();
        }
    }

    // ------------------------------------------------------------------
    // 内部：政策参数上下文
    // ------------------------------------------------------------------

    /**
     * 本次请求使用的参数上下文：辖区第一个服务城市（读不到时按全国口径）。
     *
     * <p>一期参数主要按 NATIONAL 维护，城市级参数是预留能力；取第一个城市是为了让
     * "城市级覆盖"一旦启用就自动生效，而不用改调用点。
     */
    private PolicyContext contextFor(List<String> cities) {
        String region = cities == null || cities.isEmpty()
                ? PolicyQueryService.NATIONAL : cities.get(0);
        return contextBuilder.build(region, LocalDate.now());
    }

    /** 当前登录社保局视角的参数上下文（rules / claim 用，没有辖区列表时读一次服务城市）。 */
    private PolicyContext currentContext() {
        return contextFor(citiesOfCurrentBureau());
    }

    /** 当前登录社保局的主服务城市（用于参数版本摘要）。 */
    private String currentRegion() {
        List<String> cities = citiesOfCurrentBureau();
        return cities.isEmpty() ? PolicyQueryService.NATIONAL : cities.get(0);
    }

    /** 发放周期（秒）：参数驱动，合约里的 30 天常量仍会做最终校验。 */
    private static long cycleSeconds(PolicyContext ctx) {
        return Math.max(1, ctx.getPensionCycleDays()) * 24L * 60 * 60;
    }

    // ------------------------------------------------------------------
    // 内部：金额与判定
    // ------------------------------------------------------------------

    /** 是否满足"可办理退休"的链上状态前提（真正裁决在合约）。 */
    private boolean isRetirable(Map<String, Object> row, PolicyContext ctx) {
        int status = (int) toLong(row.get("status"));
        if (status != STATUS_ACTIVE && status != STATUS_SUSPENDED) {
            return false;
        }
        if (toLong(row.get("activeTransferId")) != 0) {
            return false;
        }
        return toLong(row.get("age")) >= ctx.getRetireAgeMale()
                && toLong(row.get("paidMonths")) >= ctx.getMinPaidMonths();
    }

    /** 本期是否可发放（距上次发放满参数周期；从未发过则随时可发）。 */
    private boolean canClaim(Map<String, Object> row, PolicyContext ctx, long now) {
        long last = toLong(row.get("lastPensionAt"));
        return last == 0 || now >= last + cycleSeconds(ctx);
    }

    /** 每月应发合计 = 基础养老金（统筹）+ 个人账户养老金（个人）。 */
    private BigInteger monthlyOf(Map<String, Object> row, PolicyContext ctx) {
        return baseMonthlyOf(row, ctx).add(toBigInt(row.get("pensionPersonalMonthly")));
    }

    /**
     * 基础养老金 = 缴费基数 × 缴费月数 × 年计发比例 ÷ 12。
     *
     * <p>旧实现写死 {@code /1200}（隐含 1%×12）；现在比例来自参数
     * {@code benefit.basic.rate.yearly}，参数表缺省时仍回落 1%，结果与旧公式一致。
     */
    private BigInteger baseMonthlyOf(Map<String, Object> row, PolicyContext ctx) {
        BigDecimal base = new BigDecimal(toBigInt(row.get("paymentBase")));
        BigDecimal months = BigDecimal.valueOf(toLong(row.get("paidMonths")));
        return base.multiply(months).multiply(ctx.getBasicPensionYearlyRate())
                .divide(BigDecimal.valueOf(12), 0, java.math.RoundingMode.HALF_UP)
                .toBigInteger();
    }

    /** 列表行：保留链上原始字段，再补上支出侧的计算结果。 */
    private Map<String, Object> pensionView(Map<String, Object> row, PolicyContext ctx) {
        Map<String, Object> view = new LinkedHashMap<>(row);
        view.put("address", text(row.get("address")).isEmpty() ? text(row.get("employee")) : text(row.get("address")));
        view.put("baseMonthly", baseMonthlyOf(row, ctx).toString());
        view.put("personalMonthly", toBigInt(row.get("pensionPersonalMonthly")).toString());
        view.put("monthlyTotal", monthlyOf(row, ctx).toString());
        return view;
    }

    // ------------------------------------------------------------------
    // 内部：发放流水统计（cs_chain_event）
    // ------------------------------------------------------------------

    /**
     * 某职工的养老金发放事件查询条件。
     *
     * <p>SDK 返回的地址大小写不稳定，库里可能既有 {@code 0xAbC…} 也有 {@code 0xabc…}，
     * 因此用 {@code lower(employee_address)} 比较；{@code QueryWrapper} 不能 clone，
     * 需要"查总数"和"查分页"各建一个。
     */
    private QueryWrapper<ChainEvent> recordWrapper(String address) {
        QueryWrapper<ChainEvent> wrapper = new QueryWrapper<>();
        wrapper.eq("event_name", "PensionClaimed")
                .apply("lower(employee_address) = {0}", address.toLowerCase())
                .orderByDesc("id");
        return wrapper;
    }

    /**
     * 辖区内的累计发放（按事件表统计）。
     *
     * <p>{@code covered=false} 表示辖区退休人员超过 100 人、只统计了前 100 人——
     * 如实告知，避免把"部分统计"当成全量。
     */
    private Map<String, Object> claimedStats(List<String> addresses) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", 0L);
        out.put("amount", "0");
        out.put("covered", true);
        if (addresses.isEmpty()) {
            return out;
        }
        boolean covered = true;
        List<String> targets = addresses;
        if (targets.size() > 100) {
            targets = addresses.subList(0, 100);
            covered = false;
        }
        try {
            QueryWrapper<ChainEvent> wrapper = new QueryWrapper<>();
            wrapper.select("COUNT(*) AS cnt", "COALESCE(SUM(amount), 0) AS total")
                    .eq("event_name", "PensionClaimed")
                    .in("employee_address", targets);
            List<Map<String, Object>> rows = eventMapper.selectMaps(wrapper);
            if (rows != null && !rows.isEmpty()) {
                Map<String, Object> row = rows.get(0);
                out.put("count", toLong(row.get("cnt")));
                out.put("amount", toBigInt(row.get("total")).toString());
            }
        } catch (RuntimeException e) {
            log.warn("统计养老金发放流水失败（不影响页面其它数据）：{}", e.getMessage());
        }
        out.put("covered", covered);
        return out;
    }

    // ------------------------------------------------------------------
    // 内部：小工具
    // ------------------------------------------------------------------

    private Map<String, Object> page(List<Map<String, Object>> rows, Integer offset, Integer limit) {
        int from = offset == null || offset < 0 ? 0 : offset;
        int size = limit == null || limit <= 0 ? 20 : Math.min(limit, 100);
        int end = Math.min(from + size, rows.size());
        List<Map<String, Object>> slice = from >= rows.size()
                ? new ArrayList<>()
                : new ArrayList<>(rows.subList(from, end));
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("rows", slice);
        view.put("total", rows.size());
        view.put("offset", from);
        view.put("limit", size);
        view.put("nextOffset", end);
        return view;
    }

    private static long nowSeconds() {
        return System.currentTimeMillis() / 1000;
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
}
