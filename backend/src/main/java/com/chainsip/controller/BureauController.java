package com.chainsip.controller;

import com.chainsip.chain.dto.SendResult;
import com.chainsip.common.ApiResult;
import com.chainsip.dto.AccountCreateResult;
import com.chainsip.dto.AccountView;
import com.chainsip.dto.Requests;
import com.chainsip.security.RequireRole;
import com.chainsip.security.AuthContext;
import com.chainsip.security.AuthUser;
import com.chainsip.service.AccountHierarchyService;
import com.chainsip.service.AuthService;
import com.chainsip.service.BureauBizService;
import com.chainsip.service.BureauCityService;
import com.chainsip.service.BureauOverviewService;
import com.chainsip.service.CacheInvalidationService;
import com.chainsip.service.IdempotencyService;
import com.chainsip.service.PensionBizService;
import com.chainsip.service.PensionDeterminationService;
import com.chainsip.service.TransferBizService;
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
import java.util.List;
import java.util.Map;

/**
 * 社保局（BUREAU）侧：复核放行 / 接收转移、待办列表与机构信息查询，以及给本局下辖缴费单位注资。
 *
 * <p>注资的链上函数 {@code depositSponsor} 是 {@code onlyAdmin}，因此
 * {@link BureauBizService} 会在"单位城市属于本局服务城市"这一授权边界通过后，
 * 由后端用 <b>ADMIN 私钥代签</b>。
 */
@Tag(name = "06 BUREAU 社保局", description = "转移放行 / 接收 / 待办列表 / 社保局与城市绑定 / 给下辖单位注资 / 辖区总览 / 养老金支出")
@RestController
@RequestMapping("/api/bureau")
@RequireRole(AuthService.ROLE_BUREAU)
public class BureauController {

    private final TransferBizService transferBizService;
    private final AccountHierarchyService accountHierarchyService;
    private final BureauCityService bureauCityService;
    private final BureauBizService bureauBizService;
    private final BureauOverviewService bureauOverviewService;
    private final PensionBizService pensionBizService;
    private final PensionDeterminationService determinationService;
    private final IdempotencyService idempotencyService;
    private final CacheInvalidationService cacheInvalidationService;

    public BureauController(TransferBizService transferBizService,
                            AccountHierarchyService accountHierarchyService,
                            BureauCityService bureauCityService,
                            BureauBizService bureauBizService,
                            BureauOverviewService bureauOverviewService,
                            PensionBizService pensionBizService,
                            PensionDeterminationService determinationService,
                            IdempotencyService idempotencyService,
                            CacheInvalidationService cacheInvalidationService) {
        this.transferBizService = transferBizService;
        this.accountHierarchyService = accountHierarchyService;
        this.bureauCityService = bureauCityService;
        this.bureauBizService = bureauBizService;
        this.bureauOverviewService = bureauOverviewService;
        this.pensionBizService = pensionBizService;
        this.determinationService = determinationService;
        this.idempotencyService = idempotencyService;
        this.cacheInvalidationService = cacheInvalidationService;
    }

    @Operation(summary = "创建缴费单位账号（层级：社保局 → 缴费单位；建好后需管理员在链上注册该公司地址）")
    @PostMapping("/accounts")
    public ApiResult<AccountCreateResult> createSponsorAccount(
            @Valid @RequestBody Requests.BureauAccountCreate request) {
        AuthUser me = AuthContext.require();
        AccountCreateResult result = idempotencyService.run("bureau-create-sponsor",
                String.valueOf(me.getAccountId()), request,
                () -> accountHierarchyService.createSponsorByBureau(request));
        cacheInvalidationService.afterChainWrite();
        return ApiResult.ok(result);
    }

    @Operation(summary = "本局下辖的缴费单位账号列表（只返回链上城市属于本局服务城市的单位；"
            + "读不到城市的单位不返回，本局服务城市读不到时返回空数组）")
    @GetMapping("/accounts")
    public ApiResult<List<AccountView>> sponsorAccounts(
            @RequestParam(required = false) Integer offset,
            @RequestParam(required = false) Integer limit) {
        return ApiResult.ok(accountHierarchyService.listSponsorsInMyCities(offset, limit));
    }

    @Operation(summary = "转出地复核放行（approved=false 即复核失败，需填原因）")
    @PostMapping("/transfers/authorize")
    public ApiResult<SendResult> authorize(@Valid @RequestBody Requests.TransferAction request) {
        AuthUser me = AuthContext.require();
        SendResult result = idempotencyService.run("bureau-transfer-authorize",
                String.valueOf(me.getAccountId()), request,
                () -> transferBizService.authorizeTransfer(
                        request.getId(), request.getApproved(), request.getReason()));
        cacheInvalidationService.afterChainWrite();
        return ApiResult.ok(result);
    }

    @Operation(summary = "转入地接收：建立新城市参保档案并落下托管基金")
    @PostMapping("/transfers/{id}/receive")
    public ApiResult<SendResult> receive(@PathVariable Long id) {
        AuthUser me = AuthContext.require();
        SendResult result = idempotencyService.run("bureau-transfer-receive",
                String.valueOf(me.getAccountId()), id,
                () -> transferBizService.receiveTransfer(id));
        cacheInvalidationService.afterChainWrite();
        return ApiResult.ok(result);
    }

    @Operation(summary = "转入地待办列表")
    @GetMapping("/transfers/inbound")
    public ApiResult<Map<String, Object>> inbound(@RequestParam String bureau,
                                                  @RequestParam(defaultValue = "0") int offset,
                                                  @RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(transferBizService.getInboundTransfers(bureau, offset, limit));
    }

    @Operation(summary = "转出地待办列表")
    @GetMapping("/transfers/outbound")
    public ApiResult<Map<String, Object>> outbound(@RequestParam String bureau,
                                                   @RequestParam(defaultValue = "0") int offset,
                                                   @RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(transferBizService.getOutboundTransfers(bureau, offset, limit));
    }

    @Operation(summary = "转移申请详情")
    @GetMapping("/transfers/{id}")
    public ApiResult<Map<String, Object>> transfer(@PathVariable Long id) {
        return ApiResult.ok(transferBizService.getTransfer(id));
    }

    @Operation(summary = "社保局信息（名称 / 授权状态 / 服务城市）")
    @GetMapping("/info")
    public ApiResult<Map<String, Object>> bureauInfo(@RequestParam String bureau) {
        return ApiResult.ok(transferBizService.getBureau(bureau));
    }

    @Operation(summary = "给本局下辖的缴费单位注资（链上 depositSponsor 是 onlyAdmin，由后端用 ADMIN 私钥代签；"
            + "单位城市必须属于本局服务城市，否则 403 且不发交易）")
    @PostMapping("/sponsors/deposit")
    public ApiResult<SendResult> depositToSponsor(@Valid @RequestBody Requests.Deposit request) {
        AuthUser me = AuthContext.require();
        SendResult result = idempotencyService.run("bureau-deposit",
                String.valueOf(me.getAccountId()), request,
                () -> bureauBizService.depositToOwnSponsor(request.getSponsor(), request.getAmount()));
        cacheInvalidationService.afterChainWrite();
        return ApiResult.ok(result);
    }

    @Operation(summary = "本局在链上注册的服务城市（创建缴费单位时 city 的默认来源 / 下拉用；"
            + "链未就绪或本局尚未注册时返回空数组 + 200）")
    @GetMapping("/cities")
    public ApiResult<List<String>> cities() {
        return ApiResult.ok(bureauCityService.citiesOfInstitutionCode(AuthContext.require().getInstitutionCode()));
    }

    @Operation(summary = "社保局名单")
    @GetMapping("/list")
    public ApiResult<Map<String, Object>> bureauList(@RequestParam(defaultValue = "0") int offset,
                                                     @RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(transferBizService.getBureauList(offset, limit));
    }

    @Operation(summary = "城市与社保局绑定关系")
    @GetMapping("/city-bureau")
    public ApiResult<Object> cityBureau(@RequestParam String city) {
        return ApiResult.ok(transferBizService.bureauOfCity(city));
    }

    // ------------------------------------------------------------------
    // 辖区总览（本局服务城市内的单位 / 职工 / 参保记录 / 缴纳记录）
    // ------------------------------------------------------------------

    @Operation(summary = "辖区汇总：服务城市、单位数、职工数、统筹余额、累计已缴、参保状态分布；"
            + "链未就绪或无服务城市时返回全 0 + reason（HTTP 200）")
    @GetMapping("/city/overview")
    public ApiResult<Map<String, Object>> cityOverview() {
        return ApiResult.ok(bureauOverviewService.overview());
    }

    @Operation(summary = "辖区内的全部缴费单位（链上城市落在本局服务城市内；"
            + "含链下平台账号的用户名 / 显示名 / 启用状态；分页在命中结果上做）")
    @GetMapping("/city/sponsors")
    public ApiResult<Map<String, Object>> citySponsors(
            @RequestParam(required = false) Integer offset,
            @RequestParam(required = false) Integer limit) {
        return ApiResult.ok(bureauOverviewService.sponsors(offset, limit));
    }

    @Operation(summary = "辖区内的参保职工（参保城市落在本局服务城市内；sponsor 可选，只列该单位名下；"
            + "含链下平台账号；扫描量有上限，超出部分 truncated=true）")
    @GetMapping("/city/employees")
    public ApiResult<Map<String, Object>> cityEmployees(
            @RequestParam(required = false) String sponsor,
            @RequestParam(required = false) Integer offset,
            @RequestParam(required = false) Integer limit) {
        return ApiResult.ok(bureauOverviewService.employees(offset, limit, sponsor));
    }

    @Operation(summary = "某职工的参保记录（余额 / 基数 / 状态 / 累计月数等）；职工不在本辖区时 403")
    @GetMapping("/city/insurance")
    public ApiResult<Map<String, Object>> cityInsurance(@RequestParam String employee) {
        return ApiResult.ok(bureauOverviewService.insurance(employee));
    }

    @Operation(summary = "某职工的缴纳记录（分页）；职工不在本辖区时 403")
    @GetMapping("/city/payments")
    public ApiResult<Map<String, Object>> cityPayments(@RequestParam String employee,
                                                       @RequestParam(defaultValue = "0") int offset,
                                                       @RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(bureauOverviewService.payments(employee, offset, limit));
    }

    // ------------------------------------------------------------------
    // 养老金支出：退休办理 / 按月发放 / 发放流水（缴纳的对称面）
    // ------------------------------------------------------------------

    @Operation(summary = "养老金规则：退休年龄、最低缴费月数、计发月数与计算口径（与合约常量同源）")
    @GetMapping("/pension/rules")
    public ApiResult<Map<String, Object>> pensionRules() {
        return ApiResult.ok(pensionBizService.rules());
    }

    @Operation(summary = "养老金支出总览：辖区退休人数、本期待发人数与金额、可办退休人数、累计发放；"
            + "链未就绪或无服务城市时返回全 0 + reason（HTTP 200）")
    @GetMapping("/pension/overview")
    public ApiResult<Map<String, Object>> pensionOverview() {
        return ApiResult.ok(pensionBizService.overview());
    }

    @Operation(summary = "可办理退休的职工（本辖区、已达 60 岁且累计缴费满 180 个月；带年龄差 / 月数差）")
    @GetMapping("/pension/eligible")
    public ApiResult<Map<String, Object>> pensionEligible(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(pensionBizService.eligible(offset, limit));
    }

    @Operation(summary = "已退休人员：每月应发、已领月数、下次可领时间、个人账户剩余")
    @GetMapping("/pension/retirees")
    public ApiResult<Map<String, Object>> pensionRetirees(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(pensionBizService.retirees(offset, limit));
    }

    @Operation(summary = "办理退休（链上 retireEmployee 是 onlyAdmin，由后端用 ADMIN 私钥代签；"
            + "force=true 为特殊审批，跳过年龄与缴费月数门槛）")
    @PostMapping("/pension/retire")
    public ApiResult<SendResult> pensionRetire(@Valid @RequestBody Requests.PensionRetire request) {
        AuthUser me = AuthContext.require();
        SendResult result = idempotencyService.run("bureau-pension-retire",
                String.valueOf(me.getAccountId()), request,
                () -> pensionBizService.retire(request.getEmployee(),
                        Boolean.TRUE.equals(request.getForce())));
        cacheInvalidationService.afterChainWrite();
        return ApiResult.ok(result);
    }

    @Operation(summary = "发放一个月养老金（链上 claimPension；基础养老金扣城市统筹基金池、"
            + "个人账户养老金扣职工个人账户；同一职工 30 天内只能发一次）")
    @PostMapping("/pension/claim")
    public ApiResult<SendResult> pensionClaim(@Valid @RequestBody Requests.PensionClaim request) {
        AuthUser me = AuthContext.require();
        SendResult result = idempotencyService.run("bureau-pension-claim",
                String.valueOf(me.getAccountId()), request,
                () -> pensionBizService.claim(request.getEmployee()));
        cacheInvalidationService.afterChainWrite();
        return ApiResult.ok(result);
    }

    @Operation(summary = "某职工的养老金发放流水（来自链上 PensionClaimed 事件索引）；职工不在本辖区时 403")
    @GetMapping("/pension/records")
    public ApiResult<Map<String, Object>> pensionRecords(@RequestParam String employee,
                                                         @RequestParam(defaultValue = "0") int offset,
                                                         @RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(pensionBizService.records(employee, offset, limit));
    }

    // ------------------------------------------------------------------
    // 退休核定单：入参留档 / 政策快照 / 复算（政策参数化改造）
    // ------------------------------------------------------------------

    @Operation(summary = "核定单分页列表（按办理时间倒序；每行含快照哈希前 8 位、状态与交易哈希）")
    @GetMapping("/pension/determinations")
    public ApiResult<Map<String, Object>> determinations(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(determinationService.page(offset, limit));
    }

    @Operation(summary = "核定单详情（含政策快照 JSON 展开；可核对当时使用的参数口径）")
    @GetMapping("/pension/determinations/{id}")
    public ApiResult<Map<String, Object>> determination(@PathVariable Long id) {
        return ApiResult.ok(determinationService.detail(id));
    }

    @Operation(summary = "某职工的最新核定单；存量退休人员没有留档时按链上现状补建 LEGACY 核定单（不发链交易）")
    @GetMapping("/pension/determinations/latest")
    public ApiResult<Map<String, Object>> determinationLatest(@RequestParam String employee) {
        return ApiResult.ok(determinationService.latestOfEmployee(employee));
    }

    @Operation(summary = "复算指定核定单：用存档快照重算两笔月额并与落库值比对（政策调整后仍应一致）")
    @PostMapping("/pension/determinations/{id}/recompute")
    public ApiResult<Map<String, Object>> determinationRecompute(@PathVariable Long id) {
        return ApiResult.ok(determinationService.recompute(id));
    }

    @Operation(summary = "按职工复算（自动取最新核定单；存量人员先补建 LEGACY 再复算）")
    @PostMapping("/pension/determinations/recompute")
    public ApiResult<Map<String, Object>> determinationRecomputeByEmployee(@RequestParam String employee) {
        return ApiResult.ok(determinationService.recomputeOfEmployee(employee));
    }
}
