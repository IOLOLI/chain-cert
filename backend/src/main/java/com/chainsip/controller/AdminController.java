package com.chainsip.controller;

import com.chainsip.chain.dto.SendResult;
import com.chainsip.common.ApiResult;
import com.chainsip.dto.AccountCreateResult;
import com.chainsip.dto.AccountView;
import com.chainsip.dto.InstitutionRegisterResult;
import com.chainsip.dto.InstitutionRegistrationInfo;
import com.chainsip.dto.PersonRegisterRequest;
import com.chainsip.dto.Requests;
import com.chainsip.security.AuthContext;
import com.chainsip.security.AuthUser;
import com.chainsip.security.RequireRole;
import com.chainsip.service.AccountHierarchyService;
import com.chainsip.service.AdminBizService;
import com.chainsip.service.AuthService;
import com.chainsip.service.BureauCityService;
import com.chainsip.service.CacheInvalidationService;
import com.chainsip.service.IdempotencyService;
import com.chainsip.service.InstitutionKeyService;
import com.chainsip.service.InstitutionRegistrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ADMIN 平台管理：注册公司 / 社保局 / 监管账户、注资、全量建档、注销、机构接线、平台账号与机构私钥管理。
 *
 * <p>链下角色门禁（@RequireRole）只是第一道闸门；即使有人伪造 JWT 拿到 ADMIN 角色，
 * 合约仍会按实际签名地址拒绝非 ADMIN 的交易。
 */
@Tag(name = "03 ADMIN 平台管理", description = "公司 / 社保局 / 监管账户 / 注资 / 建档 / 注销 / 账号 / 机构私钥")
@RestController
@RequestMapping("/api/admin")
@RequireRole(AuthService.ROLE_ADMIN)
public class AdminController {

    private final AdminBizService adminBizService;
    private final AuthService authService;
    private final InstitutionKeyService institutionKeyService;
    private final AccountHierarchyService accountHierarchyService;
    private final InstitutionRegistrationService institutionRegistrationService;
    private final BureauCityService bureauCityService;
    private final IdempotencyService idempotencyService;
    private final CacheInvalidationService cacheInvalidationService;

    public AdminController(AdminBizService adminBizService, AuthService authService,
                           InstitutionKeyService institutionKeyService,
                           AccountHierarchyService accountHierarchyService,
                           InstitutionRegistrationService institutionRegistrationService,
                           BureauCityService bureauCityService,
                           IdempotencyService idempotencyService,
                           CacheInvalidationService cacheInvalidationService) {
        this.adminBizService = adminBizService;
        this.authService = authService;
        this.institutionKeyService = institutionKeyService;
        this.accountHierarchyService = accountHierarchyService;
        this.institutionRegistrationService = institutionRegistrationService;
        this.bureauCityService = bureauCityService;
        this.idempotencyService = idempotencyService;
        this.cacheInvalidationService = cacheInvalidationService;
    }

    // ------------------------------------------------------------------
    // 核心合约
    // ------------------------------------------------------------------

    @Operation(summary = "注册缴费单位（授予 SPONSOR 角色）")
    @PostMapping("/sponsors")
    public ApiResult<SendResult> addSponsor(@Valid @RequestBody Requests.SponsorRegister request) {
        AuthUser me = AuthContext.require();
        SendResult result = idempotencyService.run("admin-add-sponsor",
                String.valueOf(me.getAccountId()), request,
                () -> adminBizService.addSponsorAccount(request.getSponsor(), request.getName(), request.getCity()));
        cacheInvalidationService.afterChainWrite();
        return ApiResult.ok(result);
    }

    @Operation(summary = "给公司统筹账户注资（记账制，实际资金链下结算）")
    @PostMapping("/deposit")
    public ApiResult<SendResult> deposit(@Valid @RequestBody Requests.Deposit request) {
        AuthUser me = AuthContext.require();
        SendResult result = idempotencyService.run("admin-deposit",
                String.valueOf(me.getAccountId()), request,
                () -> adminBizService.depositSponsor(request.getSponsor(), request.getAmount()));
        cacheInvalidationService.afterChainWrite();
        return ApiResult.ok(result);
    }

    @Operation(summary = "ADMIN 全量录入职工档案（也可由单位自助建档）")
    @PostMapping("/persons")
    public ApiResult<SendResult> addPerson(@Valid @RequestBody PersonRegisterRequest request) {
        AuthUser me = AuthContext.require();
        SendResult result = idempotencyService.run("admin-add-person",
                String.valueOf(me.getAccountId()), request,
                () -> adminBizService.addPerson(request));
        cacheInvalidationService.afterChainWrite();
        return ApiResult.ok(result);
    }

    @Operation(summary = "注销职工账户（要求余额为 0 且无在途转移）")
    @PostMapping("/employees/remove")
    public ApiResult<SendResult> removeEmployee(@Valid @RequestBody Requests.AddressOnly request) {
        AuthUser me = AuthContext.require();
        SendResult result = idempotencyService.run("admin-remove-employee",
                String.valueOf(me.getAccountId()), request,
                () -> adminBizService.removeEmployee(request.getAddress()));
        cacheInvalidationService.afterChainWrite();
        return ApiResult.ok(result);
    }

    @Operation(summary = "设置转移合约地址（部署完两个合约后必须调一次）")
    @PostMapping("/transfer-contract")
    public ApiResult<SendResult> setTransferContract(@Valid @RequestBody Requests.AddressOnly request) {
        AuthUser me = AuthContext.require();
        SendResult result = idempotencyService.run("admin-set-transfer-contract",
                String.valueOf(me.getAccountId()), request,
                () -> adminBizService.setTransferContract(request.getAddress()));
        cacheInvalidationService.afterChainWrite();
        return ApiResult.ok(result);
    }

    @Operation(summary = "授予社保局角色（仅当未通过转移合约 addBureaus 注册时需要）")
    @PostMapping("/bureaus/grant-role")
    public ApiResult<SendResult> grantBureauRole(@Valid @RequestBody Requests.AddressOnly request) {
        return ApiResult.ok(adminBizService.grantBureauRole(request.getAddress()));
    }

    @Operation(summary = "注册监管账户（只读审计身份）")
    @PostMapping("/regulators")
    public ApiResult<SendResult> addRegulator(@Valid @RequestBody Requests.RegulatorRegister request) {
        return ApiResult.ok(adminBizService.addRegulatorAccount(
                request.getRegulator(), request.getName(), request.getAgency()));
    }

    @Operation(summary = "注销监管账户")
    @PostMapping("/regulators/remove")
    public ApiResult<SendResult> removeRegulator(@Valid @RequestBody Requests.AddressOnly request) {
        return ApiResult.ok(adminBizService.removeRegulatorAccount(request.getAddress()));
    }

    // ------------------------------------------------------------------
    // 转移合约上的机构管理（同样是 ADMIN 权限）
    // ------------------------------------------------------------------

    @Operation(summary = "注册社保局并绑定服务城市（在转移合约上）")
    @PostMapping("/bureaus")
    public ApiResult<SendResult> addBureau(@Valid @RequestBody Requests.BureauRegister request) {
        return ApiResult.ok(adminBizService.addBureaus(request.getBureau(), request.getName(), request.getCity()));
    }

    @Operation(summary = "切换社保局授权状态（在转移合约上）")
    @PostMapping("/bureaus/authorize")
    public ApiResult<SendResult> authorizeBureau(@Valid @RequestBody Requests.BureauAuthorize request) {
        return ApiResult.ok(adminBizService.changeBureausAuthorized(request.getBureau(), request.getAuthorized()));
    }

    // ------------------------------------------------------------------
    // 只读
    // ------------------------------------------------------------------

    @Operation(summary = "公司资金账户")
    @GetMapping("/sponsors/{address}")
    public ApiResult<Map<String, Object>> sponsorAccount(@PathVariable String address) {
        return ApiResult.ok(adminBizService.getSponsorAccount(address));
    }

    @Operation(summary = "平台资金/权益总览")
    @GetMapping("/system-stats")
    public ApiResult<Map<String, Object>> systemStats() {
        return ApiResult.ok(adminBizService.getSystemStats());
    }

    @Operation(summary = "链上已注册的全部城市（含所属社保局地址与名称）：遍历 getBureauList 汇总去重；"
            + "链未就绪返回空数组 + 200")
    @GetMapping("/cities")
    public ApiResult<List<Map<String, Object>>> cities() {
        return ApiResult.ok(bureauCityService.allRegisteredCities());
    }

    // ------------------------------------------------------------------
    // 平台账号与机构私钥
    // ------------------------------------------------------------------

    @Operation(summary = "开通平台账号（层级根节点：管理员可创建 BUREAU / SPONSOR / REGULATOR / EMPLOYEE / ADMIN；"
            + "未指定 institutionCode 时自动生成机构链账户；BUREAU/SPONSOR/REGULATOR 会按 name/city 自动完成链上注册）")
    @PostMapping("/accounts")
    public ApiResult<AccountCreateResult> createAccount(@Valid @RequestBody Requests.AccountCreate request) {
        AuthUser me = AuthContext.require();
        AccountCreateResult result = idempotencyService.run("admin-create-account",
                String.valueOf(me.getAccountId()), request,
                () -> accountHierarchyService.createByAdmin(request));
        cacheInvalidationService.afterChainWrite();
        return ApiResult.ok(result);
    }

    @Operation(summary = "平台账号列表（含每行链上角色 roleOf；链未就绪时该字段为 null，列表照常返回）")
    @GetMapping("/accounts")
    public ApiResult<List<AccountView>> accounts() {
        return ApiResult.ok(authService.listAccountsWithChainRole());
    }

    @Operation(summary = "启用/禁用平台账号")
    @PostMapping("/accounts/{id}/status")
    public ApiResult<Boolean> updateStatus(@PathVariable Long id, @RequestParam String status) {
        authService.updateStatus(id, status);
        return ApiResult.ok(true);
    }

    @Operation(summary = "重置账号密码")
    @PostMapping("/accounts/{id}/password")
    public ApiResult<Boolean> resetPassword(@PathVariable Long id, @RequestParam String password) {
        authService.resetPassword(id, password);
        return ApiResult.ok(true);
    }

    @Operation(summary = "机构链账户列表（不含私钥）")
    @GetMapping("/institution-keys")
    public ApiResult<List<InstitutionKeyService.View>> institutionKeys() {
        List<InstitutionKeyService.View> views = new ArrayList<>();
        institutionKeyService.listAll().forEach(key -> views.add(InstitutionKeyService.View.of(key)));
        return ApiResult.ok(views);
    }

    @Operation(summary = "登记机构链账户：privateKeyHex 留空则随机生成一把新私钥")
    @PostMapping("/institution-keys")
    public ApiResult<InstitutionKeyService.View> createInstitutionKey(
            @Valid @RequestBody Requests.InstitutionKeyCreate request) {
        if (request.getPrivateKeyHex() == null || request.getPrivateKeyHex().trim().isEmpty()) {
            return ApiResult.ok(InstitutionKeyService.View.of(institutionKeyService.generate(
                    request.getCode(), request.getName(), request.getRole(), request.getRemark())));
        }
        return ApiResult.ok(InstitutionKeyService.View.of(institutionKeyService.importKey(
                request.getCode(), request.getName(), request.getRole(),
                request.getPrivateKeyHex(), request.getRemark())));
    }

    @Operation(summary = "启用/停用机构链账户")
    @PostMapping("/institution-keys/{code}/enabled")
    public ApiResult<Boolean> setInstitutionKeyEnabled(@PathVariable String code, @RequestParam boolean enabled) {
        institutionKeyService.setEnabled(code, enabled);
        return ApiResult.ok(true);
    }

    // ------------------------------------------------------------------
    // 待上链注册（合并"创建机构账号"与"链上注册"两步的补录入口）
    // ------------------------------------------------------------------

    @Operation(summary = "待上链注册/待授权的机构列表（判定以链上 roleOf / getBureau 为准；链未就绪返回空数组）")
    @GetMapping("/institutions/pending")
    public ApiResult<List<InstitutionRegistrationInfo>> pendingInstitutions() {
        return ApiResult.ok(institutionRegistrationService.listPending());
    }

    @Operation(summary = "逐条注册机构：BUREAU/SPONSOR 需 body 里带 city，REGULATOR 可空；"
            + "失败也返回 HTTP 200 + ok:false，便于前端逐条展示")
    @PostMapping("/institutions/{code}/register")
    public ApiResult<InstitutionRegisterResult> registerInstitution(
            @PathVariable String code,
            @RequestBody(required = false) Requests.InstitutionRegister request) {
        String city = request == null ? null : request.getCity();
        AuthUser me = AuthContext.require();
        InstitutionRegisterResult result = idempotencyService.run("admin-register-institution",
                String.valueOf(me.getAccountId()), code + "|" + city,
                () -> institutionRegistrationService.register(code, city));
        cacheInvalidationService.afterChainWrite();
        return ApiResult.ok(result);
    }
}
