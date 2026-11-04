package com.chainsip.policy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chainsip.policy.entity.PolicyDefinition;
import com.chainsip.policy.entity.PolicyParameter;
import com.chainsip.policy.mapper.PolicyDefinitionMapper;
import com.chainsip.policy.mapper.PolicyParameterMapper;
import com.chainsip.policy.service.PolicyContextBuilder;
import com.chainsip.policy.service.PolicyQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 一期种子数据（幂等）：参数字典 + 现行版本 + 预置的未来版本 + 历史种子纠偏。
 *
 * <p><b>幂等原则</b>：只补"缺什么"，不覆盖管理员改过的值——判定按 {@code param_code} /
 * {@code (param_code, region_code, version_no)}。只有两类"历史种子本身有误"的数据会被纠偏：
 * <ol>
 *   <li>计发月数表原来只有 {@code {"60":139}}，与国发〔2005〕38 号附表不符 →
 *       追加一版完整表（旧版按版本化规则截止，历史不丢）；</li>
 *   <li>最低缴费年限的预置 DRAFT 原来是 216@2030 / 240@2035，与"2030 年起每年提高 6 个月、
 *       10 年提高到 20 年"不符 → 重排为 2030~2039 每年一版（186→240）。这些版本从未生效过，
 *       属于"纠正预置草稿"，不涉及历史口径。</li>
 * </ol>
 *
 * <p><b>种子值 = 合约常量</b>：现有合约把 8% / 19% / 3200 / 24000 / 180 个月 / 60 岁 / 139 个月 / 30 天
 * 写死，参数表初始值必须逐项一致，否则一期会上演"链下算出 9%、链上扣 8%"的双口径漂移。
 * 注意：国家现行单位费率为 16%，但本演示合约仍是 19%，参数跟合约走（一致性巡检会因此通过）；
 * 改费率需等二期把金额上链、重新部署合约。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class PolicyDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PolicyDataInitializer.class);

    private static final String NATIONAL = PolicyQueryService.NATIONAL;

    /** 现行版本统一从 2025-01-01 起算（与设计文档一致）。 */
    private static final LocalDate CURRENT_FROM = LocalDate.of(2025, 1, 1);

    /** 国发〔2005〕38 号附表：45~70 岁计发月数表（紧凑 JSON，约 260 字符）。 */
    static final String PAYMENT_MONTHS_JSON =
            "{\"45\":216,\"46\":212,\"47\":208,\"48\":204,\"49\":199,\"50\":195,\"51\":190,"
                    + "\"52\":185,\"53\":180,\"54\":175,\"55\":170,\"56\":164,\"57\":158,"
                    + "\"58\":152,\"59\":145,\"60\":139,\"61\":132,\"62\":125,\"63\":117,"
                    + "\"64\":109,\"65\":101,\"66\":93,\"67\":84,\"68\":75,\"69\":65,\"70\":56}";

    /** 字典种子：编码 / 名称 / 类型 / 默认值 / 下限 / 上限 / 分类 / 文号。 */
    private static final String[][] DEFINITIONS = {
            // 缴费
            {PolicyContextBuilder.CODE_EMPLOYEE_RATE, "职工个人缴费比例", "RATE", "0.08", "0", "1", "缴费",
                    "国发〔2005〕38号"},
            {PolicyContextBuilder.CODE_EMPLOYER_RATE, "单位缴费比例", "RATE", "0.19", "0", "1", "缴费",
                    "演示合约按 19%；国家现行 16%，二期金额上链后调整"},
            {PolicyContextBuilder.CODE_BASE_FLOOR, "缴费基数下限（元/月）", "NUMBER", "3200", "0", "50000",
                    "缴费", "对应全口径平均工资 60% 口径"},
            {PolicyContextBuilder.CODE_BASE_CEILING, "缴费基数上限（元/月）", "NUMBER", "24000", "1000", "100000",
                    "缴费", "对应全口径平均工资 300% 口径"},
            // 资格
            {PolicyContextBuilder.CODE_MIN_PAID_MONTHS, "最低累计缴费月数", "INT", "180", "60", "480", "资格",
                    "社会保险法第十六条；2030 起每年+6 个月，10 年提高到 20 年"},
            // 退休
            {PolicyContextBuilder.CODE_RETIRE_AGE_MALE, "男职工法定退休年龄（原）", "INT", "60", "45", "70", "退休", ""},
            {PolicyContextBuilder.CODE_DELAY_START, "渐进式延迟退休起始日", "DATE", "2025-01-01", null, null, "退休",
                    "全国人大常委会关于实施渐进式延迟法定退休年龄的决定"},
            {PolicyContextBuilder.CODE_DELAY_TARGET_MALE, "延迟后男职工退休年龄", "INT", "63", "45", "70", "退休",
                    "每 4 个月延迟 1 个月，15 年到位"},
            {PolicyContextBuilder.CODE_DELAY_PACE_MALE, "男职工延迟步长（月/月）", "INT", "4", "1", "12", "退休", ""},
            // 待遇
            {PolicyContextBuilder.CODE_BASIC_YEARLY_RATE, "基础养老金年计发比例", "RATE", "0.01", "0", "0.05", "待遇",
                    "国发〔2005〕38号"},
            {PolicyContextBuilder.CODE_PAYMENT_MONTHS, "个人账户计发月数表", "JSON", PAYMENT_MONTHS_JSON, null, null,
                    "待遇", "国发〔2005〕38号附表（45~70 岁）"},
            {PolicyContextBuilder.CODE_PENSION_CYCLE_DAYS, "养老金发放周期（天）", "INT", "30", "15", "60", "待遇", ""}
    };

    /** 2030 年起最低缴费年限：每年提高 6 个月，2039 年达到 240 个月（20 年）。 */
    private static final int[] MIN_MONTHS_SCHEDULE = {186, 192, 198, 204, 210, 216, 222, 228, 234, 240};

    private final PolicyDefinitionMapper definitionMapper;
    private final PolicyParameterMapper parameterMapper;
    private final PolicyQueryService queryService;

    public PolicyDataInitializer(PolicyDefinitionMapper definitionMapper,
                                 PolicyParameterMapper parameterMapper,
                                 PolicyQueryService queryService) {
        this.definitionMapper = definitionMapper;
        this.parameterMapper = parameterMapper;
        this.queryService = queryService;
    }

    @Override
    public void run(ApplicationArguments args) {
        int newDefinitions = seedDefinitions();
        int newVersions = seedVersions();
        int upgradedMonths = upgradePaymentMonthsTable();
        int upgradedSchedule = upgradeMinMonthsSchedule();
        queryService.evict();
        if (newDefinitions + newVersions + upgradedMonths + upgradedSchedule > 0) {
            log.info("政策参数种子已补齐：新增字典 {} 条、新增版本 {} 条、计发月数表升级 {} 处、缴费年限时间轴重排 {} 条",
                    newDefinitions, newVersions, upgradedMonths, upgradedSchedule);
        } else {
            log.debug("政策参数种子已齐备（{} 个参数）", DEFINITIONS.length);
        }
    }

    // ------------------------------------------------------------------
    // 字典与现行版本
    // ------------------------------------------------------------------

    private int seedDefinitions() {
        int created = 0;
        for (String[] spec : DEFINITIONS) {
            String code = spec[0];
            PolicyDefinition existing = definitionMapper.selectOne(new LambdaQueryWrapper<PolicyDefinition>()
                    .eq(PolicyDefinition::getParamCode, code));
            if (existing != null) {
                continue;
            }
            PolicyDefinition definition = new PolicyDefinition();
            definition.setParamCode(code);
            definition.setParamName(spec[1]);
            definition.setParamType(spec[2]);
            definition.setDefaultValue(spec[3]);
            definition.setValueMin(blankToNull(spec[4]));
            definition.setValueMax(blankToNull(spec[5]));
            definition.setRegionScope(NATIONAL);
            definition.setCategory(spec[6]);
            definition.setStatus("ENABLED");
            definition.setPolicyDoc(blankStringToNull(spec[7]));
            definition.setRemark("一期种子：默认值与合约常量一致");
            definition.setCreatedAt(LocalDateTime.now());
            definition.setUpdatedAt(LocalDateTime.now());
            definitionMapper.insert(definition);
            created++;
        }
        return created;
    }

    private int seedVersions() {
        int created = 0;
        for (String[] spec : DEFINITIONS) {
            String code = spec[0];
            if (countVersions(code) > 0) {
                continue;
            }
            // 最低缴费年限从 180 起、截止到 2029-12-31，2030 起由下面重排的年度版本接续
            LocalDate validTo = PolicyContextBuilder.CODE_MIN_PAID_MONTHS.equals(code)
                    ? LocalDate.of(2029, 12, 31) : null;
            created += insertVersion(code, spec[3], CURRENT_FROM, validTo, 1, "ACTIVE");
        }
        return created;
    }

    // ------------------------------------------------------------------
    // 历史种子纠偏（幂等）
    // ------------------------------------------------------------------

    /**
     * 计发月数表升级：字典默认值与当前生效版本都应是 45~70 全表。
     *
     * <p>生效版本不同时追加一版"今天生效"的新版本，旧版按规则截止——这正是参数版本化该有的样子。
     */
    private int upgradePaymentMonthsTable() {
        int changed = 0;
        String code = PolicyContextBuilder.CODE_PAYMENT_MONTHS;

        PolicyDefinition definition = definitionMapper.selectOne(new LambdaQueryWrapper<PolicyDefinition>()
                .eq(PolicyDefinition::getParamCode, code));
        if (definition != null && !PAYMENT_MONTHS_JSON.equals(definition.getDefaultValue())) {
            definition.setDefaultValue(PAYMENT_MONTHS_JSON);
            definition.setUpdatedAt(LocalDateTime.now());
            definitionMapper.updateById(definition);
            changed++;
            log.info("计发月数表字典默认值已升级为 45~70 岁全表");
        }

        PolicyParameter current = effectiveNow(code);
        if (current != null && !PAYMENT_MONTHS_JSON.equals(current.getParamValue())) {
            LocalDate today = LocalDate.now();
            if (current.getValidFrom() != null && today.isBefore(current.getValidFrom())) {
                return changed;
            }
            current.setValidTo(today.minusDays(1));
            current.setStatus("TERMINATED");
            parameterMapper.updateById(current);
            int version = maxVersion(code) + 1;
            insertVersion(code, PAYMENT_MONTHS_JSON, today, null, version, "ACTIVE");
            changed++;
            log.info("计发月数表已追加完整版 v{}（{} 生效），旧版截止到 {}", version, today, today.minusDays(1));
        }
        return changed;
    }

    /**
     * 最低缴费年限时间轴重排：2030-01-01 起每年 +6 个月，直到 2039 年的 240 个月（20 年）。
     *
     * <p>只动 DRAFT（从未生效过的预置草稿）：旧种子的 216@2030 / 240@2035 与文档口径不符，
     * 直接删掉重排；已生效的历史版本一律不动。
     */
    private int upgradeMinMonthsSchedule() {
        String code = PolicyContextBuilder.CODE_MIN_PAID_MONTHS;
        List<PolicyParameter> drafts = parameterMapper.selectList(new LambdaQueryWrapper<PolicyParameter>()
                .eq(PolicyParameter::getParamCode, code)
                .eq(PolicyParameter::getRegionCode, NATIONAL)
                .eq(PolicyParameter::getStatus, "DRAFT")
                .orderByAsc(PolicyParameter::getValidFrom));

        if (drafts.size() == MIN_MONTHS_SCHEDULE.length && matchesSchedule(drafts)) {
            return 0;
        }

        for (PolicyParameter draft : drafts) {
            parameterMapper.deleteById(draft.getId());
        }

        // 现行版本必须存在且截止到 2029-12-31（否则 2030 起的版本之间会留空档）
        PolicyParameter active = effectiveNow(code);
        if (active == null) {
            insertVersion(code, "180", CURRENT_FROM, LocalDate.of(2029, 12, 31), 1, "ACTIVE");
            active = effectiveNow(code);
        }
        if (active != null && (active.getValidTo() == null
                || !active.getValidTo().equals(LocalDate.of(2029, 12, 31)))) {
            active.setValidTo(LocalDate.of(2029, 12, 31));
            parameterMapper.updateById(active);
        }

        int version = maxVersion(code);
        for (int i = 0; i < MIN_MONTHS_SCHEDULE.length; i++) {
            LocalDate from = LocalDate.of(2030 + i, 1, 1);
            LocalDate to = i == MIN_MONTHS_SCHEDULE.length - 1 ? null : LocalDate.of(2030 + i, 12, 31);
            version++;
            insertVersion(code, String.valueOf(MIN_MONTHS_SCHEDULE[i]), from, to, version, "DRAFT");
        }
        log.info("最低缴费年限时间轴已重排：2030 起每年 +6 个月，共 {} 个 DRAFT 版本（186→240）",
                MIN_MONTHS_SCHEDULE.length);
        return MIN_MONTHS_SCHEDULE.length;
    }

    private boolean matchesSchedule(List<PolicyParameter> drafts) {
        for (int i = 0; i < drafts.size(); i++) {
            PolicyParameter draft = drafts.get(i);
            if (!String.valueOf(MIN_MONTHS_SCHEDULE[i]).equals(draft.getParamValue())
                    || draft.getValidFrom() == null
                    || draft.getValidFrom().getYear() != 2030 + i) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 基础工具
    // ------------------------------------------------------------------

    private PolicyParameter effectiveNow(String code) {
        LocalDate today = LocalDate.now();
        List<PolicyParameter> rows = parameterMapper.selectList(new LambdaQueryWrapper<PolicyParameter>()
                .eq(PolicyParameter::getParamCode, code)
                .eq(PolicyParameter::getRegionCode, NATIONAL)
                .le(PolicyParameter::getValidFrom, today)
                .and(w -> w.isNull(PolicyParameter::getValidTo).or().ge(PolicyParameter::getValidTo, today))
                .orderByDesc(PolicyParameter::getVersionNo)
                .last("limit 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private int countVersions(String code) {
        Long count = parameterMapper.selectCount(new LambdaQueryWrapper<PolicyParameter>()
                .eq(PolicyParameter::getParamCode, code)
                .eq(PolicyParameter::getRegionCode, NATIONAL));
        return count == null ? 0 : count.intValue();
    }

    private int maxVersion(String code) {
        List<PolicyParameter> rows = parameterMapper.selectList(new LambdaQueryWrapper<PolicyParameter>()
                .eq(PolicyParameter::getParamCode, code)
                .eq(PolicyParameter::getRegionCode, NATIONAL)
                .orderByDesc(PolicyParameter::getVersionNo)
                .last("limit 1"));
        return rows.isEmpty() || rows.get(0).getVersionNo() == null ? 0 : rows.get(0).getVersionNo();
    }

    private int insertVersion(String code, String value, LocalDate from, LocalDate to,
                              int versionNo, String status) {
        PolicyParameter parameter = new PolicyParameter();
        parameter.setParamCode(code);
        parameter.setRegionCode(NATIONAL);
        parameter.setParamValue(value);
        parameter.setValidFrom(from);
        parameter.setValidTo(to);
        parameter.setVersionNo(versionNo);
        parameter.setStatus(status);
        parameter.setCreatedBy("system");
        parameter.setCreatedAt(LocalDateTime.now());
        parameterMapper.insert(parameter);
        return 1;
    }

    private static BigDecimal blankToNull(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return new BigDecimal(value.trim());
    }

    private static String blankStringToNull(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    /** 供测试引用：种子参数编码清单。 */
    public static List<String> seededCodes() {
        List<String> codes = new ArrayList<>();
        for (String[] spec : DEFINITIONS) {
            codes.add(spec[0]);
        }
        return codes;
    }
}
