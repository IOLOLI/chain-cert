package com.chainsip.controller;

import com.chainsip.common.ApiResult;
import com.chainsip.policy.dto.PolicyModifyRequest;
import com.chainsip.policy.entity.PolicyAuditLog;
import com.chainsip.policy.entity.PolicyDefinition;
import com.chainsip.policy.entity.PolicyParameter;
import com.chainsip.policy.service.PolicyAdminService;
import com.chainsip.policy.service.PolicyChainService;
import com.chainsip.policy.service.PolicyContextBuilder;
import com.chainsip.policy.service.PolicyQueryService;
import com.chainsip.security.AuthContext;
import com.chainsip.security.AuthUser;
import com.chainsip.security.RequireRole;
import com.chainsip.service.AuthService;
import com.chainsip.service.IdempotencyService;
import io.swagger.v3.oas.annotations.Operation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 政策参数管理（ADMIN 维护全国口径，BUREAU 只能维护本局服务城市）。
 *
 * <p>一期的定位是"参数可查、可改、可审计"：所有写操作都是追加版本 + 写审计，
 * 不覆盖历史；改完立即清缓存，下一次业务查询就按新参数算。
 *
 * <p>权限：{@code GET /definitions} 与 {@code GET /audit} 只给 ADMIN；
 * 参数查询 / 版本时间线与参数追加对 BUREAU 开放（地区边界由
 * {@link PolicyAdminService#modify} 再校验一次，越权直接 403）。
 */
@Tag(name = "07 POLICY 政策参数", description = "参数字典 / 版本时间线 / 追加版本 / 变更审计")
@RestController
@RequestMapping("/api/admin/policy")
@RequireRole({AuthService.ROLE_ADMIN, AuthService.ROLE_BUREAU})
public class PolicyController {

    private static final Logger log = LoggerFactory.getLogger(PolicyController.class);

    private final PolicyAdminService adminService;
    private final PolicyQueryService queryService;
    private final PolicyContextBuilder contextBuilder;
    private final PolicyChainService policyChainService;
    private final IdempotencyService idempotencyService;

    public PolicyController(PolicyAdminService adminService, PolicyQueryService queryService,
                            PolicyContextBuilder contextBuilder, PolicyChainService policyChainService,
                            IdempotencyService idempotencyService) {
        this.adminService = adminService;
        this.queryService = queryService;
        this.contextBuilder = contextBuilder;
        this.policyChainService = policyChainService;
        this.idempotencyService = idempotencyService;
    }

    @Operation(summary = "参数字典列表（按分类分组；仅 ADMIN）")
    @GetMapping("/definitions")
    @RequireRole(AuthService.ROLE_ADMIN)
    public ApiResult<Map<String, Object>> definitions() {
        List<PolicyDefinition> definitions = queryService.definitions();
        Map<String, List<PolicyDefinition>> grouped = new LinkedHashMap<>();
        for (PolicyDefinition definition : definitions) {
            String category = definition.getCategory() == null ? "其他" : definition.getCategory();
            grouped.computeIfAbsent(category, key -> new java.util.ArrayList<>()).add(definition);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("categories", grouped.keySet());
        out.put("groups", grouped);
        out.put("definitions", definitions);
        return ApiResult.ok(out);
    }

    @Operation(summary = "参数详情：字典 + 当前生效版本 + 全部版本时间线（ADMIN / BUREAU；BUREAU 建议传本局城市）")
    @GetMapping("/parameters")
    public ApiResult<Map<String, Object>> parameters(@RequestParam String code,
                                                     @RequestParam(required = false) String region) {
        return ApiResult.ok(adminService.detail(code, region));
    }

    @Operation(summary = "调整参数：追加新版本（未来日期即预置 DRAFT）、写审计；生效版本会自动发布到链上")
    @PostMapping("/parameters")
    public ApiResult<PolicyParameter> modify(@Valid @RequestBody PolicyModifyRequest request) {
        AuthUser me = AuthContext.require();
        PolicyParameter saved = idempotencyService.run("policy-modify",
                String.valueOf(me.getAccountId()), request,
                () -> adminService.modify(request, me));
        // 本地版本已提交，再把"当前生效参数"发布到链上；发布失败不影响本地保存，
        // policyConsistency 会报红并每 10 分钟自动补发。
        try {
            policyChainService.publish(false);
        } catch (Exception e) {
            log.warn("政策参数发布上链失败（本地已保存）：{}", e.getMessage());
        }
        return ApiResult.ok(saved);
    }

    @Operation(summary = "链上政策版本与链下生效参数的对账结果（仅 ADMIN）")
    @GetMapping("/chain/status")
    @RequireRole(AuthService.ROLE_ADMIN)
    public ApiResult<Map<String, Object>> chainStatus() {
        return ApiResult.ok(policyChainService.chainStatus());
    }

    @Operation(summary = "把链下当前生效参数发布到链上（仅 ADMIN；链上已一致时不发交易）")
    @PostMapping("/chain/publish")
    @RequireRole(AuthService.ROLE_ADMIN)
    public ApiResult<Map<String, Object>> publish() {
        return ApiResult.ok(policyChainService.publish(true));
    }

    @Operation(summary = "参数变更审计流水（仅 ADMIN；可按 code 过滤）")
    @GetMapping("/audit")
    @RequireRole(AuthService.ROLE_ADMIN)
    public ApiResult<List<PolicyAuditLog>> audit(@RequestParam(required = false) String code) {
        return ApiResult.ok(adminService.auditLogs(code));
    }

    @Operation(summary = "当前生效参数清单（规则页 / 一致性巡检用；含版本号、文号、是否回落默认）")
    @GetMapping("/effective")
    public ApiResult<List<Map<String, Object>>> effective(@RequestParam(required = false) String region) {
        return ApiResult.ok(contextBuilder.describeEffective(region, LocalDate.now()));
    }
}
