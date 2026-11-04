package com.chainsip.policy.service;

import com.chainsip.chain.dto.CallResult;
import com.chainsip.chain.dto.SendResult;
import com.chainsip.common.BizException;
import com.chainsip.common.ErrorCode;
import com.chainsip.config.ChainProperties;
import com.chainsip.policy.entity.PolicyContext;
import com.chainsip.service.ChainTxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 政策参数的「链上版本」发布与对账。
 *
 * <p><b>为什么需要它</b>：合约里原来把 8% / 19% / 3200 / 24000 / 180 / 60 / 139 / 30 天写成 constant，
 * 链下改参数后链上照样按旧常量扣款，形成"双口径漂移"。现在合约增加了
 * {@code publishPolicy(packed, payloadHash)}：参数按位打包成一个 uint256 写入合约 storage，
 * 链上业务（缴费、退休、发放）全部改成实时读取，链下每次参数生效都必须把同一份参数发布上链。
 *
 * <p><b>对账口径</b>：{@link #chainStatus()} 把「链下当前生效参数」与「链上 policyPacked」逐项比对，
 * 不一致时 {@code /api/chain/status} 的 policyConsistency 报红；后台每 10 分钟自动补发一次。
 *
 * <p>位布局必须与 contracts/ChainSIP.sol 的 policyPacked 注释保持一致：
 * <pre>
 *   [0..15]    个人费率 bps      [16..31]   统筹费率 bps
 *   [32..47]   基础养老金年率 bps [48..63]   计发月数
 *   [64..79]   退休年龄          [80..95]   最低缴费月数
 *   [96..127]  周期天数          [128..175] 基数下限
 *   [176..223] 基数上限
 * </pre>
 */
@Service
public class PolicyChainService {

    private static final Logger log = LoggerFactory.getLogger(PolicyChainService.class);

    private static final String CORE = ChainProperties.CORE;
    /** 发布政策参数需要 ADMIN 私钥签名（合约里的 publishPolicy 是 onlyAdmin）。 */
    private static final String ADMIN = "ADMIN";

    private static final BigInteger MASK16 = BigInteger.valueOf(0xFFFFL);
    private static final BigInteger MASK48 = new BigInteger("FFFFFFFFFFFF", 16);

    private final ChainTxService chain;
    private final PolicyContextBuilder contextBuilder;

    public PolicyChainService(ChainTxService chain, PolicyContextBuilder contextBuilder) {
        this.chain = chain;
        this.contextBuilder = contextBuilder;
    }

    // ------------------------------------------------------------------
    // 打包 / 解包
    // ------------------------------------------------------------------

    /** 当前生效的政策参数（按全国口径 + 今天）。 */
    public PolicyContext currentContext() {
        return contextBuilder.build(PolicyQueryService.NATIONAL, LocalDate.now());
    }

    public BigInteger pack(PolicyContext ctx) {
        long personal = bps(ctx.getEmployeeContribRate());
        long overall = bps(ctx.getEmployerContribRate());
        long basic = bps(ctx.getBasicPensionYearlyRate());
        long payout = ctx.paymentMonthsOf(ctx.getRetireAgeMale());
        long retireAge = ctx.getRetireAgeMale();
        long minMonths = ctx.getMinPaidMonths();
        long cycleDays = ctx.getPensionCycleDays();
        long floor = ctx.getBaseFloorValue().setScale(0, RoundingMode.HALF_UP).longValue();
        long ceiling = ctx.getBaseCeilingValue().setScale(0, RoundingMode.HALF_UP).longValue();
        return BigInteger.valueOf(personal)
                .or(BigInteger.valueOf(overall).shiftLeft(16))
                .or(BigInteger.valueOf(basic).shiftLeft(32))
                .or(BigInteger.valueOf(payout).shiftLeft(48))
                .or(BigInteger.valueOf(retireAge).shiftLeft(64))
                .or(BigInteger.valueOf(minMonths).shiftLeft(80))
                .or(BigInteger.valueOf(cycleDays).shiftLeft(96))
                .or(BigInteger.valueOf(floor).shiftLeft(128))
                .or(BigInteger.valueOf(ceiling).shiftLeft(176));
    }

    /** 链上 packed → 逐项字符串（与链下用同一套 code，便于逐项比对）。 */
    public Map<String, String> unpack(BigInteger packed) {
        long personal = packed.and(MASK16).longValue();
        long overall = packed.shiftRight(16).and(MASK16).longValue();
        long basic = packed.shiftRight(32).and(MASK16).longValue();
        long payout = packed.shiftRight(48).and(MASK16).longValue();
        long retireAge = packed.shiftRight(64).and(MASK16).longValue();
        long minMonths = packed.shiftRight(80).and(MASK16).longValue();
        long cycleDays = packed.shiftRight(96).and(BigInteger.valueOf(0xFFFFFFFFL)).longValue();
        long floor = packed.shiftRight(128).and(MASK48).longValue();
        long ceiling = packed.shiftRight(176).and(MASK48).longValue();

        Map<String, String> values = new LinkedHashMap<>();
        values.put(PolicyContextBuilder.CODE_EMPLOYEE_RATE, rateText(personal));
        values.put(PolicyContextBuilder.CODE_EMPLOYER_RATE, rateText(overall));
        values.put(PolicyContextBuilder.CODE_BASE_FLOOR, String.valueOf(floor));
        values.put(PolicyContextBuilder.CODE_BASE_CEILING, String.valueOf(ceiling));
        values.put(PolicyContextBuilder.CODE_MIN_PAID_MONTHS, String.valueOf(minMonths));
        values.put(PolicyContextBuilder.CODE_RETIRE_AGE_MALE, String.valueOf(retireAge));
        values.put(PolicyContextBuilder.CODE_BASIC_YEARLY_RATE, rateText(basic));
        values.put(PolicyContextBuilder.CODE_PAYMENT_MONTHS,
                "{\"" + retireAge + "\":" + payout + "}");
        values.put(PolicyContextBuilder.CODE_PENSION_CYCLE_DAYS, String.valueOf(cycleDays));
        return values;
    }

    /** 链下当前生效参数 → 同一套字符串口径。 */
    public Map<String, String> contextValues(PolicyContext ctx) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(PolicyContextBuilder.CODE_EMPLOYEE_RATE, trimRate(ctx.getEmployeeContribRate()));
        values.put(PolicyContextBuilder.CODE_EMPLOYER_RATE, trimRate(ctx.getEmployerContribRate()));
        values.put(PolicyContextBuilder.CODE_BASE_FLOOR,
                ctx.getBaseFloorValue().setScale(0, RoundingMode.HALF_UP).toPlainString());
        values.put(PolicyContextBuilder.CODE_BASE_CEILING,
                ctx.getBaseCeilingValue().setScale(0, RoundingMode.HALF_UP).toPlainString());
        values.put(PolicyContextBuilder.CODE_MIN_PAID_MONTHS, String.valueOf(ctx.getMinPaidMonths()));
        values.put(PolicyContextBuilder.CODE_RETIRE_AGE_MALE, String.valueOf(ctx.getRetireAgeMale()));
        values.put(PolicyContextBuilder.CODE_BASIC_YEARLY_RATE, trimRate(ctx.getBasicPensionYearlyRate()));
        int age = ctx.getRetireAgeMale();
        values.put(PolicyContextBuilder.CODE_PAYMENT_MONTHS, "{\"" + age + "\":" + ctx.paymentMonthsOf(age) + "}");
        values.put(PolicyContextBuilder.CODE_PENSION_CYCLE_DAYS, String.valueOf(ctx.getPensionCycleDays()));
        return values;
    }

    // ------------------------------------------------------------------
    // 链上读取 / 发布
    // ------------------------------------------------------------------

    public boolean isChainReady() {
        try {
            return chain.isReady();
        } catch (Exception e) {
            return false;
        }
    }

    /** 链上 policyPacked；链未就绪或读不到时返回 null。 */
    public BigInteger readChainPacked() {
        try {
            CallResult result = chain.callAs(ADMIN, CORE, "policyPacked");
            return asBigInteger(result == null ? null : result.first());
        } catch (Exception e) {
            log.debug("读取链上 policyPacked 失败：{}", e.getMessage());
            return null;
        }
    }

    /** 链上 policyVersion；读不到时返回 null。 */
    public Integer readChainVersion() {
        try {
            CallResult result = chain.callAs(ADMIN, CORE, "policyVersion");
            BigInteger value = asBigInteger(result == null ? null : result.first());
            return value == null ? null : value.intValue();
        } catch (Exception e) {
            log.debug("读取链上 policyVersion 失败：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 链下与链上逐项比对（{@code /api/chain/status} 的 policyConsistency 就用它）。
     * 链未就绪时 {@code consistent=false}，并给出明确原因。
     */
    public Map<String, Object> chainStatus() {
        PolicyContext ctx = currentContext();
        Map<String, String> dbValues = contextValues(ctx);
        BigInteger dbPacked = pack(ctx);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("region", PolicyQueryService.NATIONAL);
        out.put("dbPacked", dbPacked.toString());

        boolean ready = isChainReady();
        out.put("chainReady", ready);
        if (!ready) {
            out.put("chainVersion", null);
            out.put("chainPacked", null);
            out.put("consistent", false);
            out.put("items", new ArrayList<Map<String, Object>>());
            out.put("note", "链网关未就绪，无法读取链上政策版本");
            return out;
        }

        BigInteger chainPacked = readChainPacked();
        Integer chainVersion = readChainVersion();
        out.put("chainVersion", chainVersion);
        out.put("chainPacked", chainPacked == null ? null : chainPacked.toString());
        if (chainPacked == null) {
            out.put("consistent", false);
            out.put("items", new ArrayList<Map<String, Object>>());
            out.put("note", "链上合约未返回 policyPacked：确认已部署新版 ChainSIP");
            return out;
        }

        Map<String, String> chainValues = unpack(chainPacked);
        List<Map<String, Object>> items = new ArrayList<>();
        boolean consistent = true;
        for (Map.Entry<String, String> entry : dbValues.entrySet()) {
            String code = entry.getKey();
            String dbValue = entry.getValue();
            String chainValue = chainValues.get(code);
            boolean same = dbValue.equals(chainValue);
            consistent &= same;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("paramCode", code);
            item.put("effectiveValue", dbValue);
            // 沿用原字段名，前端不必调整；语义从"合约常量"变为"链上当前版本值"
            item.put("contractConstant", chainValue);
            item.put("consistent", same);
            items.add(item);
        }
        out.put("consistent", consistent);
        out.put("items", items);
        out.put("note", consistent
                ? "链上政策版本 v" + (chainVersion == null ? "?" : chainVersion) + " 与链下生效参数一致"
                : "链上执行参数与链下生效参数不一致：点「发布到链上」补发，或确认后端是否有权发布（ADMIN）");
        return out;
    }

    /**
     * 把链下当前生效参数发布上链。
     *
     * @param force false 时链上已一致则不发交易（幂等，供定时对账调用）；true 时强制重发
     */
    public synchronized Map<String, Object> publish(boolean force) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!isChainReady()) {
            out.put("published", false);
            out.put("message", "链网关未就绪，政策参数暂存链下");
            return out;
        }
        PolicyContext ctx = currentContext();
        BigInteger desired = pack(ctx);
        BigInteger current = readChainPacked();
        Integer version = readChainVersion();

        if (!force && desired.equals(current)) {
            out.put("published", false);
            out.put("chainVersion", version);
            out.put("message", "链上参数已是最新生效版本");
            return out;
        }

        try {
            SendResult result = chain.sendAs(ADMIN, CORE, "publishPolicy", desired, payloadHash(ctx));
            out.put("published", true);
            out.put("txHash", result.getTransactionHash());
            out.put("blockNumber", result.getBlockNumber());
            out.put("packed", desired.toString());
            out.put("message", "政策参数已发布上链，链上业务立即按新参数执行");
            log.info("政策参数已发布上链：packed={}，tx={}", desired, result.getTransactionHash());
        } catch (BizException e) {
            out.put("published", false);
            out.put("message", "发布失败：" + e.getMessage());
            log.warn("政策参数发布上链失败：{}", e.getMessage());
        }
        return out;
    }

    /** 定时对账：链上落后就自动补发（链未就绪 / 旧合约时静默跳过）。 */
    @Scheduled(fixedDelay = 600_000L, initialDelay = 120_000L)
    public void reconcile() {
        try {
            if (!isChainReady()) {
                return;
            }
            Map<String, Object> status = chainStatus();
            if (!Boolean.TRUE.equals(status.get("consistent"))
                    && Boolean.TRUE.equals(status.get("chainReady"))) {
                log.info("检测到链上政策参数与链下不一致，尝试自动补发");
                publish(false);
            }
        } catch (Exception e) {
            log.debug("政策参数定时对账跳过：{}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 试算（替代原合约里的 quotePayment）
    // ------------------------------------------------------------------

    /**
     * 按链下政策参数试算某月薪的缴费明细（基数夹取 + 8%/19% 拆分，向下取整与合约一致）。
     * 前端「缴费管理 → 试算」改调这里，合约里不再保留 quotePayment（核心合约字节码逼近上限）。
     */
    public Map<String, Object> quote(Object salaryRaw) {
        BigDecimal salary = parseDecimal(salaryRaw);
        if (salary == null || salary.signum() <= 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "月工资必须是大于 0 的数字");
        }
        PolicyContext ctx = currentContext();
        BigDecimal base = salary.max(ctx.getBaseFloorValue()).min(ctx.getBaseCeilingValue());
        BigDecimal personal = base.multiply(ctx.getEmployeeContribRate()).setScale(0, RoundingMode.DOWN);
        BigDecimal overall = base.multiply(ctx.getEmployerContribRate()).setScale(0, RoundingMode.DOWN);
        BigDecimal total = personal.add(overall);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("values", Arrays.asList(base, personal, overall, total));
        out.put("salary", salary);
        out.put("effectiveBase", base);
        out.put("personalAmount", personal);
        out.put("overallAmount", overall);
        out.put("totalAmount", total);
        out.put("employeeContribRate", trimRate(ctx.getEmployeeContribRate()));
        out.put("employerContribRate", trimRate(ctx.getEmployerContribRate()));
        out.put("note", "按链下生效政策参数试算；实际扣款由合约按已发布的链上参数执行");
        return out;
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    /** SHA-256(规范化参数串) → 256 位正整数，作为发布交易的 payloadHash（链下台账与链上事件对账用）。 */
    public BigInteger payloadHash(PolicyContext ctx) {
        StringBuilder canonical = new StringBuilder();
        for (Map.Entry<String, String> entry : contextValues(ctx).entrySet()) {
            canonical.append(entry.getKey()).append('=').append(entry.getValue()).append(';');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return new BigInteger(1, hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static long bps(BigDecimal rate) {
        if (rate == null) {
            return 0;
        }
        return rate.multiply(BigDecimal.valueOf(10000)).setScale(0, RoundingMode.HALF_UP).longValue();
    }

    private static String rateText(long bps) {
        return BigDecimal.valueOf(bps).movePointLeft(4).stripTrailingZeros().toPlainString();
    }

    private static String trimRate(BigDecimal rate) {
        return rate == null ? "0" : rate.stripTrailingZeros().toPlainString();
    }

    private static BigDecimal parseDecimal(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigInteger asBigInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigInteger) {
            return (BigInteger) value;
        }
        if (value instanceof Number) {
            return BigInteger.valueOf(((Number) value).longValue());
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return new BigInteger(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
