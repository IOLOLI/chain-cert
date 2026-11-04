package com.chainsip.policy.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chainsip.common.BizException;
import com.chainsip.common.ErrorCode;
import com.chainsip.policy.dto.PolicyModifyRequest;
import com.chainsip.policy.entity.PolicyAuditLog;
import com.chainsip.policy.entity.PolicyDefinition;
import com.chainsip.policy.entity.PolicyParameter;
import com.chainsip.policy.mapper.PolicyAuditLogMapper;
import com.chainsip.policy.mapper.PolicyDefinitionMapper;
import com.chainsip.policy.mapper.PolicyParameterMapper;
import com.chainsip.security.AuthUser;
import com.chainsip.service.AuthService;
import com.chainsip.service.BureauCityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 政策参数管理端写操作：<b>追加版本，不改历史</b>。
 *
 * <p>一次修改的完整动作（同一事务）：
 * <ol>
 *   <li>角色 + 地区双重校验：ADMIN 只能维护 NATIONAL；BUREAU 只能维护本局服务城市；</li>
 *   <li>字典存在且启用，按 {@code param_type} 与值域校验新值；</li>
 *   <li>找到该地区在生效日的现行版本，把它的 {@code valid_to} 截止到生效前一日、置 TERMINATED；</li>
 *   <li>插入新版本（{@code version_no = max + 1}；未来生效的版本状态为 DRAFT）；</li>
 *   <li>写审计流水（旧值 / 新值 / 生效日 / 原因 / 操作人）；</li>
 *   <li>清参数缓存，让下一次读立即看到新版本。</li>
 * </ol>
 *
 * <p>为什么不是"直接改值"：历史核定单要靠当时的参数口径复算，改值会让历史结果漂移；
 * 追加版本 + 有效期能让"任何一天的现行口径"都可还原。
 */
@Service
public class PolicyAdminService {

    private static final Logger log = LoggerFactory.getLogger(PolicyAdminService.class);

    private final PolicyDefinitionMapper definitionMapper;
    private final PolicyParameterMapper parameterMapper;
    private final PolicyAuditLogMapper auditLogMapper;
    private final PolicyQueryService queryService;
    private final BureauCityService bureauCityService;
    private final ObjectMapper objectMapper;

    public PolicyAdminService(PolicyDefinitionMapper definitionMapper,
                              PolicyParameterMapper parameterMapper,
                              PolicyAuditLogMapper auditLogMapper,
                              PolicyQueryService queryService,
                              BureauCityService bureauCityService,
                              ObjectMapper objectMapper) {
        this.definitionMapper = definitionMapper;
        this.parameterMapper = parameterMapper;
        this.auditLogMapper = auditLogMapper;
        this.queryService = queryService;
        this.bureauCityService = bureauCityService;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------
    // 追加版本
    // ------------------------------------------------------------------

    @Transactional
    public PolicyParameter modify(PolicyModifyRequest request, AuthUser actor) {
        String code = request.getParamCode() == null ? null : request.getParamCode().trim();
        PolicyDefinition definition = definitionMapper.selectOne(new LambdaQueryWrapper<PolicyDefinition>()
                .eq(PolicyDefinition::getParamCode, code));
        if (definition == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "参数项不存在：" + code);
        }
        if (!"ENABLED".equalsIgnoreCase(definition.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "参数项已停用：" + code);
        }

        String region = resolveRegion(request.getRegionCode(), actor);
        String value = request.getParamValue().trim();
        validateValue(definition, value);

        LocalDate validFrom = parseDate(request.getValidFrom());
        LocalDate today = LocalDate.now();
        if (validFrom.isBefore(today.minusDays(3650))) {
            throw new BizException(ErrorCode.BAD_REQUEST, "生效日期过于久远：" + validFrom);
        }

        List<PolicyParameter> history = parameterMapper.selectList(new LambdaQueryWrapper<PolicyParameter>()
                .eq(PolicyParameter::getParamCode, code)
                .eq(PolicyParameter::getRegionCode, region)
                .orderByDesc(PolicyParameter::getVersionNo));
        int maxVersion = history.isEmpty() || history.get(0).getVersionNo() == null
                ? 0 : history.get(0).getVersionNo();

        // 同一天对同一参数重复修改：直接改写这一天的版本，不追加同日版本。
        // 否则旧版会被截止到"生效日-1"（早于自己的生效日），时间轴断裂、前端与复算都会读到坏数据。
        PolicyParameter sameFrom = null;
        for (PolicyParameter row : history) {
            if (validFrom.equals(row.getValidFrom())) {
                sameFrom = row;
                break;
            }
        }

        PolicyParameter target;
        String oldValue = null;
        if (sameFrom != null) {
            if (sameFrom.getParamValue() != null && sameFrom.getParamValue().equals(value)) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "参数值与当前生效版本一致（当前 " + value + "），请修改「新值」后再提交");
            }
            oldValue = sameFrom.getParamValue();
            target = sameFrom;
        } else {
            PolicyParameter current = effectiveAt(history, validFrom);
            if (current != null) {
                if (current.getParamValue() != null && current.getParamValue().equals(value)) {
                    throw new BizException(ErrorCode.BAD_REQUEST,
                        "参数值与当前生效版本一致（当前 " + value + "），请修改「新值」后再提交");
                }
                if (validFrom.isBefore(current.getValidFrom())) {
                    throw new BizException(ErrorCode.BAD_REQUEST,
                            "生效日期不得早于当前版本的生效日期 " + current.getValidFrom());
                }
                oldValue = current.getParamValue();
                // 旧版截止到新版本生效前一日：版本时间轴连续、不重叠
                current.setValidTo(validFrom.minusDays(1));
                current.setStatus("TERMINATED");
                parameterMapper.updateById(current);
            }
            target = new PolicyParameter();
            target.setVersionNo(maxVersion + 1);
            target.setCreatedAt(LocalDateTime.now());
        }

        target.setParamCode(code);
        target.setRegionCode(region);
        target.setParamValue(value);
        target.setValidFrom(validFrom);
        target.setValidTo(null);
        target.setStatus(validFrom.isAfter(today) ? "DRAFT" : "ACTIVE");
        target.setCreatedBy(actor == null ? "system" : actor.getUsername());
        if (target.getId() == null) {
            parameterMapper.insert(target);
        } else {
            parameterMapper.updateById(target);
        }

        PolicyAuditLog audit = new PolicyAuditLog();
        audit.setParamCode(code);
        audit.setRegionCode(region);
        audit.setVersionNo(target.getVersionNo());
        audit.setOldValue(oldValue);
        audit.setNewValue(value);
        audit.setEffectiveDate(validFrom);
        audit.setReason(request.getReason());
        audit.setOperator(target.getCreatedBy());
        audit.setOperateAt(LocalDateTime.now());
        auditLogMapper.insert(audit);

        queryService.evict();
        log.info("政策参数已写入版本：{} @ {} = {}（v{}，{} 生效，操作人 {}）",
                code, region, value, target.getVersionNo(), validFrom,
                target.getCreatedBy());
        return target;
    }

    // ------------------------------------------------------------------
    // 查询（管理端）
    // ------------------------------------------------------------------

    /** 某参数的完整版本时间线（含 DRAFT），按版本号升序。 */
    public List<PolicyParameter> versions(String code, String regionCode) {
        String region = regionCode == null || regionCode.trim().isEmpty()
                ? PolicyQueryService.NATIONAL : regionCode.trim();
        return parameterMapper.selectList(new LambdaQueryWrapper<PolicyParameter>()
                .eq(PolicyParameter::getParamCode, code)
                .eq(PolicyParameter::getRegionCode, region)
                .orderByAsc(PolicyParameter::getVersionNo));
    }

    /** 某参数的变更审计流水，按时间倒序。 */
    public List<PolicyAuditLog> auditLogs(String code) {
        LambdaQueryWrapper<PolicyAuditLog> wrapper = new LambdaQueryWrapper<>();
        if (code != null && !code.trim().isEmpty()) {
            wrapper.eq(PolicyAuditLog::getParamCode, code.trim());
        }
        wrapper.orderByDesc(PolicyAuditLog::getOperateAt).orderByDesc(PolicyAuditLog::getId);
        return auditLogMapper.selectList(wrapper);
    }

    /**
     * 参数详情视图：字典 + 当前生效版本 + 全部版本时间线。
     *
     * <p>一次请求把管理端弹窗要的东西都带上，避免前端串三个接口。
     */
    public Map<String, Object> detail(String code, String regionCode) {
        String region = regionCode == null || regionCode.trim().isEmpty()
                ? PolicyQueryService.NATIONAL : regionCode.trim();
        PolicyDefinition definition = queryService.definition(code);
        if (definition == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "参数项不存在：" + code);
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("definition", definition);
        view.put("regionCode", region);
        PolicyParameter effective = queryService.resolveParameter(code, region, LocalDate.now()).orElse(null);
        view.put("effective", effective);
        view.put("versions", versions(code, region));
        return view;
    }

    // ------------------------------------------------------------------
    // 内部：地区与值域
    // ------------------------------------------------------------------

    /** ADMIN 只能写 NATIONAL；BUREAU 只能写本局服务城市（多个城市时必须显式指定）。 */
    private String resolveRegion(String requested, AuthUser actor) {
        String region = requested == null ? "" : requested.trim();
        String role = actor == null ? null : actor.getRole();

        if (AuthService.ROLE_ADMIN.equalsIgnoreCase(role)) {
            if (region.isEmpty()) {
                return PolicyQueryService.NATIONAL;
            }
            if (!PolicyQueryService.NATIONAL.equals(region)) {
                throw new BizException(ErrorCode.FORBIDDEN,
                        "管理员只能维护全国（NATIONAL）参数；城市级参数由该城市社保局维护");
            }
            return region;
        }
        if (AuthService.ROLE_BUREAU.equalsIgnoreCase(role)) {
            BureauCityService.CityLookup lookup =
                    bureauCityService.lookupByInstitutionCode(actor.getInstitutionCode());
            if (!lookup.isReadable()) {
                throw new BizException(ErrorCode.FORBIDDEN,
                        "读取本局链上服务城市失败，无法维护政策参数：" + lookup.getFailureReason());
            }
            List<String> cities = lookup.getCities();
            if (cities.isEmpty()) {
                throw new BizException(ErrorCode.FORBIDDEN, "本局尚未在链上注册服务城市，无法维护政策参数");
            }
            if (region.isEmpty()) {
                if (cities.size() > 1) {
                    throw new BizException(ErrorCode.BAD_REQUEST,
                            "本局有多个服务城市，请显式指定 regionCode（" + cities + "）");
                }
                return cities.get(0);
            }
            for (String city : cities) {
                if (city.equals(region)) {
                    return region;
                }
            }
            throw new BizException(ErrorCode.FORBIDDEN,
                    "只能维护本局服务城市（" + cities + "）的参数，不能修改 " + region + " 的参数");
        }
        throw new BizException(ErrorCode.FORBIDDEN, "当前角色无权维护政策参数");
    }

    /** 按 param_type 与字典值域校验；非法值直接拒绝，避免脏参数进入计算链路。 */
    private void validateValue(PolicyDefinition definition, String value) {
        if (value == null || value.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "参数值不能为空");
        }
        if (value.length() > 512) {
            throw new BizException(ErrorCode.BAD_REQUEST, "参数值不能超过 512 个字符");
        }
        String type = definition.getParamType() == null ? "TEXT" : definition.getParamType().toUpperCase();
        switch (type) {
            case "RATE":
            case "NUMBER":
                validateNumber(definition, value, type);
                break;
            case "INT":
                if (!value.matches("-?\\d+")) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "参数值必须是整数：" + value);
                }
                validateNumber(definition, value, type);
                break;
            case "DATE":
                try {
                    LocalDate.parse(value.trim());
                } catch (RuntimeException e) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "参数值必须是日期（yyyy-MM-dd）：" + value);
                }
                break;
            case "JSON":
                try {
                    objectMapper.readTree(value);
                } catch (Exception e) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "参数值不是合法 JSON：" + e.getMessage());
                }
                break;
            default:
                // TEXT 不校验格式
                break;
        }
    }

    private void validateNumber(PolicyDefinition definition, String value, String type) {
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(value);
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "参数值必须是数值：" + value);
        }
        if ("RATE".equals(type) && (parsed.compareTo(BigDecimal.ZERO) < 0 || parsed.compareTo(BigDecimal.ONE) > 0)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "费率必须是 0~1 之间的小数：" + value);
        }
        if (definition.getValueMin() != null && parsed.compareTo(definition.getValueMin()) < 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "参数值低于下限 " + definition.getValueMin());
        }
        if (definition.getValueMax() != null && parsed.compareTo(definition.getValueMax()) > 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "参数值高于上限 " + definition.getValueMax());
        }
    }

    /** 在历史行里找在 {@code day} 生效的那一版（供时间轴倒挂校验）。 */
    private PolicyParameter effectiveAt(List<PolicyParameter> rows, LocalDate day) {
        PolicyParameter hit = null;
        for (PolicyParameter row : rows) {
            if (row.getValidFrom() != null && row.getValidFrom().isAfter(day)) {
                continue;
            }
            if (row.getValidTo() != null && row.getValidTo().isBefore(day)) {
                continue;
            }
            if (hit == null || (row.getVersionNo() != null
                    && (hit.getVersionNo() == null || row.getVersionNo() > hit.getVersionNo()))) {
                hit = row;
            }
        }
        return hit;
    }

    private LocalDate parseDate(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return LocalDate.now();
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "生效日期格式应为 yyyy-MM-dd：" + raw);
        }
    }

    /** 供测试引用：一期支持的参数编码清单。 */
    public List<String> supportedCodes() {
        List<String> codes = new ArrayList<>();
        for (String code : PolicyContextBuilder.CODES) {
            codes.add(code);
        }
        return codes;
    }
}
