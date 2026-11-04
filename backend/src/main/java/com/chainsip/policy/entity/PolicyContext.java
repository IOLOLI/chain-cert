package com.chainsip.policy.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 政策参数上下文：<b>核定时点的不可变快照</b>。
 *
 * <p>由 {@code PolicyContextBuilder} 按「地区 + 时点」从参数表解析生成，随核定单一起存档
 * （{@code cs_pension_determination.policy_snapshot}）。之后政策怎么调，都不影响这份快照，
 * 因此历史核定结果永远可复算、可对账。
 *
 * <p>一期只移植 chain-cert 用到的 9 个字段（居民险 / 遗属 / 延迟退休曲线不搬）；
 * 默认值严格等于合约常量，保证参数表为空时老流程照常跑。
 */
@Getter
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class PolicyContext {

    /** 职工个人缴费比例（contrib.employee.rate，合约 Policy.PERSONAL_RATE = 8%） */
    @Builder.Default
    private BigDecimal employeeContribRate = new BigDecimal("0.08");

    /** 单位缴费比例（contrib.employer.rate，合约 Policy.OVERALL_RATE = 19%） */
    @Builder.Default
    private BigDecimal employerContribRate = new BigDecimal("0.19");

    /** 缴费基数下限（元/月，contrib.base.floor.value，合约 MIN_INSURANCE_BASE = 3200） */
    @Builder.Default
    private BigDecimal baseFloorValue = new BigDecimal("3200");

    /** 缴费基数上限（元/月，contrib.base.ceiling.value，合约 MAX_INSURANCE_BASE = 24000） */
    @Builder.Default
    private BigDecimal baseCeilingValue = new BigDecimal("24000");

    /** 最低累计缴费月数（qual.min.months，合约 MIN_PAID_MONTHS = 180） */
    @Builder.Default
    private int minPaidMonths = 180;

    /** 男职工法定退休年龄（retire.age.male，合约 RETIRE_AGE = 60） */
    @Builder.Default
    private int retireAgeMale = 60;

    /** 基础养老金年计发比例（benefit.basic.rate.yearly，合约里隐含为 1%） */
    @Builder.Default
    private BigDecimal basicPensionYearlyRate = new BigDecimal("0.01");

    /** 个人账户计发月数表（benefit.payment.months.table，JSON，合约 PENSION_PAYOUT_MONTHS = 139） */
    @Builder.Default
    private Map<Integer, Integer> paymentMonthsTable = defaultPaymentMonths();

    /** 养老金发放周期（天，benefit.pension.cycle.days，合约 PAYMENT_CYCLE = 30 天） */
    @Builder.Default
    private int pensionCycleDays = 30;

    /** 渐进式延迟退休政策起始日（retire.delay.start，2025-01-01 起实施） */
    @Builder.Default
    private LocalDate retireDelayStart = LocalDate.of(2025, 1, 1);

    /** 延迟后男职工法定退休年龄（retire.delay.target.male，60 → 63） */
    @Builder.Default
    private int retireDelayTargetMale = 63;

    /** 男职工延迟步长：每 N 个月延迟 1 个月（retire.delay.pace.months.male，默认 4） */
    @Builder.Default
    private int retireDelayPaceMonthsMale = 4;

    /**
     * 按退休年龄取计发月数；表里没有该年龄时回落 139（完整表见国发〔2005〕38 号附表）。
     */
    public int paymentMonthsOf(int retireAge) {
        Integer months = paymentMonthsTable == null ? null : paymentMonthsTable.get(retireAge);
        return months == null ? 139 : months;
    }

    /** 默认计发月数表：国发〔2005〕38 号附表，45~70 岁全表（链上合约只按 60 岁 139 个月执行）。 */
    private static Map<Integer, Integer> defaultPaymentMonths() {
        Map<Integer, Integer> table = new LinkedHashMap<>();
        int[] ages = {45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59, 60,
                61, 62, 63, 64, 65, 66, 67, 68, 69, 70};
        int[] months = {216, 212, 208, 204, 199, 195, 190, 185, 180, 175, 170, 164, 158, 152, 145, 139,
                132, 125, 117, 109, 101, 93, 84, 75, 65, 56};
        for (int i = 0; i < ages.length; i++) {
            table.put(ages[i], months[i]);
        }
        return table;
    }

    // ------------------------------------------------------------------
    // 渐进式延迟退休（2025-01-01 起）
    // ------------------------------------------------------------------

    /**
     * 按「退休时点」推演法定退休年龄（月）。
     *
     * <p>政策口径：男职工自 2025-01-01 起，每 4 个月延迟 1 个月，最终到 63 周岁；
     * 女工人/女干部同理（步长不同，二期补）。严格的政策是"出生年月 → 退休年龄"映射，
     * 但链上只存整数年龄、没有出生日期，这里用"退休时点"近似推演，
     * 仅用于规则展示与差龄提示——<b>能否退休仍以合约的 60 周岁常量为最终裁决</b>。
     */
    public int legalRetireAgeMonths(LocalDate date) {
        LocalDate day = date == null ? LocalDate.now() : date;
        int baseMonths = retireAgeMale * 12;
        int targetMonths = Math.max(baseMonths, retireDelayTargetMale * 12);
        if (retireDelayStart == null || day.isBefore(retireDelayStart)) {
            return baseMonths;
        }
        int pace = Math.max(1, retireDelayPaceMonthsMale);
        long elapsedMonths = java.time.temporal.ChronoUnit.MONTHS.between(retireDelayStart, day);
        int added = (int) (elapsedMonths / pace);
        return Math.min(targetMonths, baseMonths + added);
    }

    /** 法定退休年龄的展示文本，如「60 岁 5 个月」。 */
    public String legalRetireAgeText(LocalDate date) {
        int total = legalRetireAgeMonths(date);
        int years = total / 12;
        int months = total % 12;
        return months == 0 ? years + " 周岁" : years + " 岁 " + months + " 个月";
    }

    /** 延迟退休是否已启动（用于规则页提示）。 */
    public boolean retireDelayStarted(LocalDate date) {
        LocalDate day = date == null ? LocalDate.now() : date;
        return retireDelayStart != null && !day.isBefore(retireDelayStart);
    }
}
