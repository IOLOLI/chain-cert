package com.chainsip.policy.service;

import com.chainsip.policy.entity.PolicyContext;
import com.chainsip.policy.entity.PolicyDefinition;
import com.chainsip.policy.entity.PolicyParameter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 把参数表解析成强类型 {@link PolicyContext}（核定快照的原料）。
 *
 * <p>参数编码与 PolicyContext 字段一一对应；缺省值 = 合约常量，参数表为空时老流程不受影响。
 * JSON 型参数（计发月数表）解析失败只记 warn 并回落内置表，避免一条脏参数把整次核定打挂。
 */
@Component
public class PolicyContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(PolicyContextBuilder.class);

    /** 一期移植的 9 个参数编码（顺序即页面展示顺序）。 */
    public static final String CODE_EMPLOYEE_RATE = "contrib.employee.rate";
    public static final String CODE_EMPLOYER_RATE = "contrib.employer.rate";
    public static final String CODE_BASE_FLOOR = "contrib.base.floor.value";
    public static final String CODE_BASE_CEILING = "contrib.base.ceiling.value";
    public static final String CODE_MIN_PAID_MONTHS = "qual.min.months";
    public static final String CODE_RETIRE_AGE_MALE = "retire.age.male";
    public static final String CODE_BASIC_YEARLY_RATE = "benefit.basic.rate.yearly";
    public static final String CODE_PAYMENT_MONTHS = "benefit.payment.months.table";
    public static final String CODE_PENSION_CYCLE_DAYS = "benefit.pension.cycle.days";
    public static final String CODE_DELAY_START = "retire.delay.start";
    public static final String CODE_DELAY_TARGET_MALE = "retire.delay.target.male";
    public static final String CODE_DELAY_PACE_MALE = "retire.delay.pace.months.male";

    /** 与 {@link PolicyContext} 字段一一对应的编码清单。 */
    public static final String[] CODES = {
            CODE_EMPLOYEE_RATE, CODE_EMPLOYER_RATE, CODE_BASE_FLOOR, CODE_BASE_CEILING,
            CODE_MIN_PAID_MONTHS, CODE_RETIRE_AGE_MALE, CODE_BASIC_YEARLY_RATE,
            CODE_PAYMENT_MONTHS, CODE_PENSION_CYCLE_DAYS,
            CODE_DELAY_START, CODE_DELAY_TARGET_MALE, CODE_DELAY_PACE_MALE
    };

    private final PolicyQueryService query;
    private final ObjectMapper objectMapper;

    public PolicyContextBuilder(PolicyQueryService query, ObjectMapper objectMapper) {
        this.query = query;
        this.objectMapper = objectMapper;
    }

    /** 按「地区 + 时点」生成参数快照；地区为空时按全国口径。 */
    public PolicyContext build(String regionCode, LocalDate date) {
        String region = normalizeRegion(regionCode);
        LocalDate day = date == null ? LocalDate.now() : date;

        PolicyContext.PolicyContextBuilder builder = PolicyContext.builder()
                .employeeContribRate(query.resolveDecimal(CODE_EMPLOYEE_RATE, region, day, "0.08"))
                .employerContribRate(query.resolveDecimal(CODE_EMPLOYER_RATE, region, day, "0.19"))
                .baseFloorValue(query.resolveDecimal(CODE_BASE_FLOOR, region, day, "3200"))
                .baseCeilingValue(query.resolveDecimal(CODE_BASE_CEILING, region, day, "24000"))
                .minPaidMonths(query.resolveInt(CODE_MIN_PAID_MONTHS, region, day, 180))
                .retireAgeMale(query.resolveInt(CODE_RETIRE_AGE_MALE, region, day, 60))
                .basicPensionYearlyRate(query.resolveDecimal(CODE_BASIC_YEARLY_RATE, region, day, "0.01"))
                .pensionCycleDays(query.resolveInt(CODE_PENSION_CYCLE_DAYS, region, day, 30))
                .retireDelayStart(query.resolveDate(CODE_DELAY_START, region, day, LocalDate.of(2025, 1, 1)))
                .retireDelayTargetMale(query.resolveInt(CODE_DELAY_TARGET_MALE, region, day, 63))
                .retireDelayPaceMonthsMale(query.resolveInt(CODE_DELAY_PACE_MALE, region, day, 4));

        Map<Integer, Integer> table = readPaymentMonths(region, day);
        if (table != null) {
            builder.paymentMonthsTable(table);
        }
        return builder.build();
    }

    /**
     * 本次核定使用的关键参数版本摘要，如 {@code qual.min.months#6;benefit.basic.rate.yearly#3}。
     *
     * <p>只列"参数表里确实有生效版本"的编码；没有任何版本时返回空串（表示全部走默认值）。
     */
    public String versionRef(String regionCode, LocalDate date) {
        String region = normalizeRegion(regionCode);
        LocalDate day = date == null ? LocalDate.now() : date;
        StringBuilder ref = new StringBuilder();
        for (String code : CODES) {
            Integer version = query.versionNoOf(code, region, day);
            if (version == null) {
                continue;
            }
            if (ref.length() > 0) {
                ref.append(';');
            }
            ref.append(code).append('#').append(version);
        }
        return ref.toString();
    }

    /**
     * 生效参数清单（规则页 / 一致性巡检用）：编码、名称、值、版本号、文号、生效日。
     *
     * <p>参数表缺版本时仍返回一行，{@code fallback=true} 表示当前用的是字典默认 / 合约常量。
     */
    public List<Map<String, Object>> describeEffective(String regionCode, LocalDate date) {
        String region = normalizeRegion(regionCode);
        LocalDate day = date == null ? LocalDate.now() : date;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String code : CODES) {
            PolicyDefinition definition = query.definition(code);
            Optional<PolicyParameter> hit = query.resolveParameter(code, region, day);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("paramCode", code);
            row.put("paramName", definition == null ? code : definition.getParamName());
            row.put("category", definition == null ? "" : definition.getCategory());
            row.put("policyDoc", definition == null ? "" : definition.getPolicyDoc());
            row.put("paramType", definition == null ? "" : definition.getParamType());
            if (hit.isPresent()) {
                row.put("paramValue", hit.get().getParamValue());
                row.put("versionNo", hit.get().getVersionNo());
                row.put("validFrom", hit.get().getValidFrom() == null ? "" : hit.get().getValidFrom().toString());
                row.put("fallback", false);
            } else {
                row.put("paramValue", definition == null ? "" : definition.getDefaultValue());
                row.put("versionNo", null);
                row.put("validFrom", "");
                row.put("fallback", true);
            }
            rows.add(row);
        }
        return rows;
    }

    /** 解析 {年龄:月数} 形式的 JSON 参数；解析失败返回 null（由调用方保留内置默认表）。 */
    @SuppressWarnings("unchecked")
    private Map<Integer, Integer> readPaymentMonths(String region, LocalDate day) {
        String raw = query.resolve(CODE_PAYMENT_MONTHS, region, day).orElse(null);
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {
            });
            Map<Integer, Integer> result = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : parsed.entrySet()) {
                int age = Integer.parseInt(entry.getKey().trim());
                int months = new BigDecimal(String.valueOf(entry.getValue())).intValue();
                result.put(age, months);
            }
            return result.isEmpty() ? null : result;
        } catch (Exception e) {
            log.warn("政策参数 [{}] 的 JSON 解析失败，回落内置计发月数表：{}", CODE_PAYMENT_MONTHS, e.getMessage());
            return null;
        }
    }

    private static String normalizeRegion(String regionCode) {
        if (regionCode == null || regionCode.trim().isEmpty()) {
            return PolicyQueryService.NATIONAL;
        }
        return regionCode.trim();
    }
}
