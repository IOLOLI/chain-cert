package com.chainsip.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chainsip.chain.dto.CallResult;
import com.chainsip.chain.dto.SendResult;
import com.chainsip.common.BizException;
import com.chainsip.common.ErrorCode;
import com.chainsip.config.ChainProperties;
import com.chainsip.entity.PensionDetermination;
import com.chainsip.mapper.PensionDeterminationMapper;
import com.chainsip.policy.entity.PolicyContext;
import com.chainsip.policy.service.PolicyContextBuilder;
import com.chainsip.security.AuthContext;
import com.chainsip.security.AuthUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 退休核定：<b>按政策参数算金额 → 落核定单（含快照）→ 发链 → 回写交易哈希</b>。
 *
 * <p>与旧实现（{@link PensionBizService#retire} 直接发交易）的差别：
 * <ol>
 *   <li>金额不再由合约推导，而是后端按"核定当天的政策参数"算好后留档；</li>
 *   <li>政策参数快照 + SHA-256 一起入库，事后政策调整也能复算出同样结果；</li>
 *   <li>先落库 PENDING 再发链，失败置 FAILED——DB 与链不是原子事务，靠状态机补偿。</li>
 * </ol>
 *
 * <p><b>链上仍是权威账本</b>：合约 {@code retireEmployee} 会按它自己的常量（139 个月等）
 * 锁定个人账户月发额，本表的金额是"按参数算出的核定依据"，两者若因参数与合约常量不一致而不同，
 * 以链上为准（一期一致性巡检会报警，二期把金额上链后彻底收敛）。
 */
@Service
public class PensionDeterminationService {

    private static final Logger log = LoggerFactory.getLogger(PensionDeterminationService.class);

    private static final String CORE = ChainProperties.CORE;

    /** 合约 EmployeeStatus。 */
    private static final int STATUS_ACTIVE = 1;
    private static final int STATUS_SUSPENDED = 2;
    private static final int STATUS_RETIRED = 5;

    /** 契约里的默认分页上限。 */
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final PensionDeterminationMapper determinationMapper;
    private final BureauOverviewService overviewService;
    private final ChainTxService chain;
    private final PolicyContextBuilder contextBuilder;
    private final ObjectMapper objectMapper;

    public PensionDeterminationService(PensionDeterminationMapper determinationMapper,
                                       BureauOverviewService overviewService,
                                       ChainTxService chain,
                                       PolicyContextBuilder contextBuilder,
                                       ObjectMapper objectMapper) {
        this.determinationMapper = determinationMapper;
        this.overviewService = overviewService;
        this.chain = chain;
        this.contextBuilder = contextBuilder;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------
    // 核定
    // ------------------------------------------------------------------

    /**
     * 办理退休并生成核定单。
     *
     * @param force 特殊审批：跳过年龄与缴费月数门槛，留档标注 force
     */
    public SendResult determine(String employee, boolean force) {
        String address = ChainParams.address(employee, "职工地址");
        Map<String, Object> account = overviewService.insurance(address);

        int status = toInt(account.get("status"));
        if (status == STATUS_RETIRED) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该职工已办理退休，无需重复办理");
        }
        if (status != STATUS_ACTIVE && status != STATUS_SUSPENDED) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "该职工当前状态为「" + text(account.get("statusName")) + "」，只有正常参保或已停缴的职工才能办理退休");
        }
        if (toLong(account.get("activeTransferId")) != 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该职工有在途转移申请，请先完成或撤销转移再办理退休");
        }

        String city = text(account.get("city"));
        int age = toInt(account.get("age"));
        int paidMonths = toInt(account.get("paidMonths"));
        BigDecimal paymentBase = toDecimal(account.get("paymentBase"));
        BigDecimal accountBalance = toDecimal(account.get("personalBalance"));

        PolicyContext ctx = contextBuilder.build(city, LocalDate.now());
        if (!force) {
            if (age < ctx.getRetireAgeMale()) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "未达到法定退休年龄：当前 " + age + " 岁，需满 " + ctx.getRetireAgeMale()
                                + " 岁（政策参数 retire.age.male）；特殊情形请走「特殊审批退休」");
            }
            if (paidMonths < ctx.getMinPaidMonths()) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "累计缴费不足：当前 " + paidMonths + " 个月，需满 " + ctx.getMinPaidMonths()
                                + " 个月（政策参数 qual.min.months）；特殊情形请走「特殊审批退休」");
            }
        }

        Amounts amounts = compute(ctx, paymentBase, paidMonths, accountBalance, age);
        String snapshot = toJson(ctx);
        String hash = sha256(snapshot);
        String versionRef = contextBuilder.versionRef(city, LocalDate.now());

        supersedePrevious(normalizeAddress(address));

        PensionDetermination row = new PensionDetermination();
        row.setEmployeeAddress(normalizeAddress(address));
        row.setRetireDate(LocalDate.now());
        row.setRetireAge(age);
        row.setPaidMonths(paidMonths);
        row.setPaymentBase(paymentBase);
        row.setAccountBalance(accountBalance);
        row.setBasicMonthly(amounts.basic);
        row.setPersonalMonthly(amounts.personal);
        row.setMonthlyTotal(amounts.total);
        row.setPolicySnapshot(snapshot);
        row.setPolicySnapshotHash(hash);
        row.setPolicyVersionRef(versionRef);
        row.setStatus("PENDING");
        row.setSource("LIVE");
        row.setOperator(currentOperator());
        row.setDeterminedAt(LocalDateTime.now());
        determinationMapper.insert(row);

        log.info("退休核定已落库：employee={} age={} paidMonths={} 基础={} 个人={} 快照哈希={} 版本={} force={}",
                address, age, paidMonths, amounts.basic, amounts.personal, hash.substring(0, 12), versionRef, force);

        try {
            SendResult result = chain.sendAs(AuthService.ROLE_ADMIN, CORE, "retireEmployee",
                    address, Boolean.valueOf(force));
            row.setStatus("APPROVED");
            row.setDeterminationTxHash(result == null ? null : result.getTransactionHash());
            determinationMapper.updateById(row);
            return result;
        } catch (RuntimeException e) {
            row.setStatus("FAILED");
            determinationMapper.updateById(row);
            log.warn("退休交易失败，核定单 {} 已置 FAILED（可在核定单列表看到并重试）：{}",
                    row.getId(), e.getMessage());
            throw e;
        }
    }

    // ------------------------------------------------------------------
    // 查询 / 补建 / 复算
    // ------------------------------------------------------------------

    /** 分页列出核定单（按 id 倒序，最新的在前）。 */
    public Map<String, Object> page(Integer offset, Integer limit) {
        int from = offset == null || offset < 0 ? 0 : offset;
        int size = limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);

        Long total = determinationMapper.selectCount(null);
        List<PensionDetermination> rows = determinationMapper.selectList(new LambdaQueryWrapper<PensionDetermination>()
                .orderByDesc(PensionDetermination::getId)
                .last("limit " + size + " offset " + from));

        List<Map<String, Object>> views = new ArrayList<>();
        for (PensionDetermination row : rows) {
            views.add(summaryView(row));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", views);
        out.put("total", total == null ? 0 : total);
        out.put("offset", from);
        out.put("limit", size);
        out.put("nextOffset", from + views.size());
        return out;
    }

    /** 核定单详情：实体字段 + 快照 JSON 展开视图。 */
    public Map<String, Object> detail(Long id) {
        PensionDetermination row = require(id);
        Map<String, Object> view = summaryView(row);
        view.put("policySnapshot", parseSnapshot(row.getPolicySnapshot()));
        view.put("policySnapshotJson", row.getPolicySnapshot());
        return view;
    }

    /**
     * 某职工的最新核定单；没有时按链上现状补建 LEGACY 核定单（存量退休人员兼容）。
     *
     * <p>补建行不发任何链交易、不改链上状态，只是把"当年没有留档"的历史人员补出一份可复算的核定依据，
     * 金额取链上当前锁定的个人账户月发额与按现行参数算出的基础养老金。
     */
    public Map<String, Object> latestOfEmployee(String employee) {
        String address = ChainParams.address(employee, "职工地址");
        PensionDetermination row = latestRow(address);
        if (row == null) {
            Map<String, Object> account = overviewService.insurance(address);
            if (toInt(account.get("status")) != STATUS_RETIRED) {
                throw new BizException(ErrorCode.NOT_FOUND, "该职工尚无核定单（未办理退休）");
            }
            row = createLegacy(address, account);
        }
        return detail(row.getId());
    }

    /** 对某职工的最新核定单（必要时补建 LEGACY）做复算。 */
    public Map<String, Object> recomputeOfEmployee(String employee) {
        String address = ChainParams.address(employee, "职工地址");
        PensionDetermination row = latestRow(address);
        if (row == null) {
            Map<String, Object> account = overviewService.insurance(address);
            if (toInt(account.get("status")) != STATUS_RETIRED) {
                throw new BizException(ErrorCode.NOT_FOUND, "该职工尚无核定单（未办理退休）");
            }
            row = createLegacy(address, account);
        }
        return recompute(row.getId());
    }

    /** 用存档快照复算，返回「一致 / 偏差明细」。政策怎么调都不影响本次结果。 */
    public Map<String, Object> recompute(Long id) {
        PensionDetermination row = require(id);
        PolicyContext ctx;
        try {
            ctx = objectMapper.readValue(row.getPolicySnapshot(), PolicyContext.class);
        } catch (Exception e) {
            throw new BizException(ErrorCode.BIZ_ERROR, "核定单快照解析失败，无法复算：" + e.getMessage());
        }

        Amounts recomputed = compute(ctx,
                row.getPaymentBase() == null ? BigDecimal.ZERO : row.getPaymentBase(),
                row.getPaidMonths() == null ? 0 : row.getPaidMonths(),
                row.getAccountBalance() == null ? BigDecimal.ZERO : row.getAccountBalance(),
                row.getRetireAge() == null ? ctx.getRetireAgeMale() : row.getRetireAge());

        List<Map<String, Object>> diffs = new ArrayList<>();
        boolean consistent = true;
        consistent &= compare("基础养老金", row.getBasicMonthly(), recomputed.basic, diffs);
        consistent &= compare("个人账户养老金", row.getPersonalMonthly(), recomputed.personal, diffs);
        consistent &= compare("月发合计", row.getMonthlyTotal(), recomputed.total, diffs);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.getId());
        out.put("employeeAddress", row.getEmployeeAddress());
        out.put("policySnapshotHash", row.getPolicySnapshotHash());
        out.put("policyVersionRef", row.getPolicyVersionRef());
        out.put("source", row.getSource());
        out.put("inputs", inputsView(row));
        out.put("stored", amountsView(row.getBasicMonthly(), row.getPersonalMonthly(), row.getMonthlyTotal()));
        out.put("recomputed", amountsView(recomputed.basic, recomputed.personal, recomputed.total));
        out.put("consistent", consistent);
        out.put("differences", diffs);
        return out;
    }

    // ------------------------------------------------------------------
    // 内部：补建 LEGACY
    // ------------------------------------------------------------------

    /**
     * 存量退休人员补建核定单：入参取链上现状，个人账户月额取链上已锁定值（权威），
     * 基础养老金按现行参数公式现算；快照用默认参数上下文（不伪造历史参数）。
     */
    private PensionDetermination createLegacy(String address, Map<String, Object> account) {
        PolicyContext ctx = contextBuilder.build(text(account.get("city")), LocalDate.now());
        int age = toInt(account.get("age"));
        int paidMonths = toInt(account.get("paidMonths"));
        BigDecimal paymentBase = toDecimal(account.get("paymentBase"));
        BigDecimal balance = toDecimal(account.get("personalBalance"));
        BigDecimal personal = toDecimal(account.get("pensionPersonalMonthly"));

        Amounts amounts = compute(ctx, paymentBase, paidMonths, balance, age);
        if (personal.compareTo(BigDecimal.ZERO) > 0) {
            amounts = new Amounts(amounts.basic, personal, amounts.basic.add(personal));
        }

        String snapshot = toJson(ctx);
        PensionDetermination row = new PensionDetermination();
        row.setEmployeeAddress(normalizeAddress(address));
        row.setRetireDate(LocalDate.now());
        row.setRetireAge(age);
        row.setPaidMonths(paidMonths);
        row.setPaymentBase(paymentBase);
        row.setAccountBalance(balance);
        row.setBasicMonthly(amounts.basic);
        row.setPersonalMonthly(amounts.personal);
        row.setMonthlyTotal(amounts.total);
        row.setPolicySnapshot(snapshot);
        row.setPolicySnapshotHash(sha256(snapshot));
        row.setPolicyVersionRef(contextBuilder.versionRef(text(account.get("city")), LocalDate.now()));
        row.setStatus("APPROVED");
        row.setSource("LEGACY");
        row.setOperator("SYSTEM");
        row.setDeterminedAt(LocalDateTime.now());
        determinationMapper.insert(row);
        log.info("为存量退休人员补建 LEGACY 核定单：employee={} id={}", address, row.getId());
        return row;
    }

    // ------------------------------------------------------------------
    // 内部：计算 / 校验 / 视图
    // ------------------------------------------------------------------

    /** 金额三件套（wei 整数口径）。 */
    private static final class Amounts {
        final BigDecimal basic;
        final BigDecimal personal;
        final BigDecimal total;

        Amounts(BigDecimal basic, BigDecimal personal, BigDecimal total) {
            this.basic = basic;
            this.personal = personal;
            this.total = total;
        }
    }

    /**
     * 计算两个金额：
     * <pre>
     * 个人账户养老金 = 个人账户余额 ÷ 计发月数(退休年龄)
     * 基础养老金     = 缴费基数 × 缴费月数 × 年计发比例 ÷ 12   （等价旧公式：基数 × 月数 ÷ 1200）
     * </pre>
     * 全部用 BigDecimal 定点计算、四舍五入到元（wei 的整数单位），禁用 double。
     */
    private Amounts compute(PolicyContext ctx, BigDecimal paymentBase, int paidMonths,
                            BigDecimal accountBalance, int retireAge) {
        int payoutMonths = Math.max(1, ctx.paymentMonthsOf(retireAge));
        BigDecimal personal = accountBalance
                .divide(BigDecimal.valueOf(payoutMonths), 0, RoundingMode.HALF_UP);
        BigDecimal basic = paymentBase
                .multiply(BigDecimal.valueOf(Math.max(0, paidMonths)))
                .multiply(ctx.getBasicPensionYearlyRate())
                .divide(BigDecimal.valueOf(12), 0, RoundingMode.HALF_UP);
        return new Amounts(basic, personal, basic.add(personal));
    }

    private boolean compare(String label, BigDecimal stored, BigDecimal computed, List<Map<String, Object>> diffs) {
        BigDecimal left = stored == null ? BigDecimal.ZERO : stored;
        boolean same = left.compareTo(computed) == 0;
        if (!same) {
            Map<String, Object> diff = new LinkedHashMap<>();
            diff.put("item", label);
            diff.put("stored", left.toPlainString());
            diff.put("recomputed", computed.toPlainString());
            diff.put("delta", computed.subtract(left).toPlainString());
            diffs.add(diff);
        }
        return same;
    }

    /** 把同一职工已有的有效核定单置 SUPERSEDED（重新办理时只保留一份有效的）。 */
    private void supersedePrevious(String address) {
        List<PensionDetermination> rows = determinationMapper.selectList(
                new LambdaQueryWrapper<PensionDetermination>()
                        .eq(PensionDetermination::getEmployeeAddress, address)
                        .in(PensionDetermination::getStatus, "APPROVED", "PENDING"));
        for (PensionDetermination row : rows) {
            row.setStatus("SUPERSEDED");
            determinationMapper.updateById(row);
        }
    }

    private PensionDetermination latestRow(String address) {
        List<PensionDetermination> rows = determinationMapper.selectList(
                new LambdaQueryWrapper<PensionDetermination>()
                        .eq(PensionDetermination::getEmployeeAddress, normalizeAddress(address))
                        .orderByDesc(PensionDetermination::getId)
                        .last("limit 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 批量取每个职工的最新核定单（辖区退休人员列表用，避免逐行查库的 N+1）。
     *
     * <p>返回的 key 是小写地址；同一职工多行时后出现的（id 更大）覆盖前面的。
     */
    public Map<String, PensionDetermination> latestByEmployees(List<String> addresses) {
        Map<String, PensionDetermination> result = new LinkedHashMap<>();
        if (addresses == null || addresses.isEmpty()) {
            return result;
        }
        List<String> normalized = new ArrayList<>();
        for (String address : addresses) {
            String value = normalizeAddress(address);
            if (value != null && !value.isEmpty() && !normalized.contains(value)) {
                normalized.add(value);
            }
        }
        if (normalized.isEmpty()) {
            return result;
        }
        List<PensionDetermination> rows = determinationMapper.selectList(
                new LambdaQueryWrapper<PensionDetermination>()
                        .in(PensionDetermination::getEmployeeAddress, normalized)
                        .orderByAsc(PensionDetermination::getId));
        for (PensionDetermination row : rows) {
            result.put(normalizeAddress(row.getEmployeeAddress()), row);
        }
        return result;
    }

    private PensionDetermination require(Long id) {
        PensionDetermination row = id == null ? null : determinationMapper.selectById(id);
        if (row == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "核定单不存在：" + id);
        }
        return row;
    }

    private Map<String, Object> summaryView(PensionDetermination row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", row.getId());
        view.put("employeeAddress", row.getEmployeeAddress());
        view.put("retireDate", row.getRetireDate() == null ? null : row.getRetireDate().toString());
        view.put("retireAge", row.getRetireAge());
        view.put("paidMonths", row.getPaidMonths());
        view.put("paymentBase", plain(row.getPaymentBase()));
        view.put("accountBalance", plain(row.getAccountBalance()));
        view.put("basicMonthly", plain(row.getBasicMonthly()));
        view.put("personalMonthly", plain(row.getPersonalMonthly()));
        view.put("monthlyTotal", plain(row.getMonthlyTotal()));
        view.put("policySnapshotHash", row.getPolicySnapshotHash());
        view.put("policySnapshotHashShort", row.getPolicySnapshotHash() == null
                ? null : row.getPolicySnapshotHash().substring(0, Math.min(8, row.getPolicySnapshotHash().length())));
        view.put("policyVersionRef", row.getPolicyVersionRef());
        view.put("status", row.getStatus());
        view.put("determinationTxHash", row.getDeterminationTxHash());
        view.put("source", row.getSource());
        view.put("operator", row.getOperator());
        view.put("determinedAt", row.getDeterminedAt() == null ? null : row.getDeterminedAt().toString());
        return view;
    }

    private Map<String, Object> inputsView(PensionDetermination row) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("paymentBase", plain(row.getPaymentBase()));
        inputs.put("paidMonths", row.getPaidMonths());
        inputs.put("accountBalance", plain(row.getAccountBalance()));
        inputs.put("retireAge", row.getRetireAge());
        return inputs;
    }

    private Map<String, Object> amountsView(BigDecimal basic, BigDecimal personal, BigDecimal total) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("basicMonthly", plain(basic));
        view.put("personalMonthly", plain(personal));
        view.put("monthlyTotal", plain(total));
        return view;
    }

    @SuppressWarnings("unchecked")
    private Object parseSnapshot(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return json;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "政策快照序列化失败：" + e.getMessage());
        }
    }

    /** SHA-256 十六进制小写。 */
    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "计算快照哈希失败：" + e.getMessage());
        }
    }

    private String currentOperator() {
        AuthUser user = AuthContext.get();
        return user == null ? "SYSTEM" : user.getUsername();
    }

    private static String plain(BigDecimal value) {
        return value == null ? "0" : value.toPlainString();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** 链上地址统一小写落库：SDK 返回大小写不稳定，比较/关联必须同一口径。 */
    private static String normalizeAddress(String address) {
        return address == null ? null : address.trim().toLowerCase();
    }

    private static int toInt(Object value) {
        return (int) toLong(value);
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
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static BigDecimal toDecimal(Object value) {
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof BigInteger) {
            return new BigDecimal((BigInteger) value);
        }
        if (value instanceof Number) {
            return BigDecimal.valueOf(((Number) value).longValue());
        }
        if (value == null) {
            return BigDecimal.ZERO;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }
}
