package com.chainsip.controller;

import com.chainsip.chain.dto.SendResult;
import com.chainsip.common.ApiResult;
import com.chainsip.dto.AccountCreateResult;
import com.chainsip.dto.AccountView;
import com.chainsip.dto.EmployeeDetailView;
import com.chainsip.dto.EmployeeRegisterRequest;
import com.chainsip.dto.Requests;
import com.chainsip.policy.service.PolicyChainService;
import com.chainsip.security.AuthContext;
import com.chainsip.security.AuthUser;
import com.chainsip.security.RequireRole;
import com.chainsip.service.AccountBizService;
import com.chainsip.service.AccountHierarchyService;
import com.chainsip.service.AuthService;
import com.chainsip.service.CacheInvalidationService;
import com.chainsip.service.IdempotencyService;
import com.chainsip.service.TransferBizService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * 缴费单位（SPONSOR）侧：为自己名下职工建档、减员、调薪、按月缴费与查询。
 *
 * <p>"只能操作自己名下职工"由合约保证（{@code emp.sponsor == msg.sender}），
 * 后端即使传了别家职工地址，交易也会被合约回滚。
 */
@Tag(name = "04 SPONSOR 缴费单位", description = "职工建档 / 减员 / 调薪 / 缴费 / 查本公司职工")
@RestController
@RequestMapping("/api/sponsor")
@RequireRole(AuthService.ROLE_SPONSOR)
public class SponsorController {

    private final AccountBizService accountBizService;
    private final AccountHierarchyService accountHierarchyService;
    private final PolicyChainService policyChainService;
    private final TransferBizService transferBizService;
    private final IdempotencyService idempotencyService;
    private final CacheInvalidationService cacheInvalidationService;

    public SponsorController(AccountBizService accountBizService,
                             AccountHierarchyService accountHierarchyService,
                             PolicyChainService policyChainService,
                             TransferBizService transferBizService,
                             IdempotencyService idempotencyService,
                             CacheInvalidationService cacheInvalidationService) {
        this.accountBizService = accountBizService;
        this.accountHierarchyService = accountHierarchyService;
        this.policyChainService = policyChainService;
        this.transferBizService = transferBizService;
        this.idempotencyService = idempotencyService;
        this.cacheInvalidationService = cacheInvalidationService;
    }

    @Operation(summary = "创建本单位职工账号（层级：缴费单位 → 参保职工；职工链上地址自动生成，无需绑定钱包）")
    @PostMapping("/accounts")
    public ApiResult<AccountCreateResult> createEmployeeAccount(
            @Valid @RequestBody Requests.SponsorAccountCreate request) {
        AuthUser me = AuthContext.require();
        AccountCreateResult result = idempotencyService.run("sponsor-create-employee",
                String.valueOf(me.getAccountId()), request,
                () -> accountHierarchyService.createEmployeeBySponsor(request));
        cacheInvalidationService.afterChainWrite();
        return ApiResult.ok(result);
    }

    @Operation(summary = "本单位职工账号列表（只返回本单位的职工：链上归属 ∪ 由本单位创建；"
            + "链不通 / 本单位没有链上地址时返回空数组，绝不会返回全部职工）")
    @GetMapping("/accounts")
    public ApiResult<List<AccountView>> employeeAccounts(
            @RequestParam(required = false) Integer offset,
            @RequestParam(required = false) Integer limit) {
        return ApiResult.ok(accountHierarchyService.listMyEmployeeAccounts(offset, limit));
    }

    @Operation(summary = "为职工建档（缴费单位 = 当前登录单位）")
    @PostMapping("/employees")
    public ApiResult<SendResult> addEmployee(@Valid @RequestBody EmployeeRegisterRequest request) {
        AuthUser me = AuthContext.require();
        SendResult result = idempotencyService.run("sponsor-add-employee",
                String.valueOf(me.getAccountId()), request,
                () -> accountBizService.addEmployee(request));
        cacheInvalidationService.afterChainWrite();
        return ApiResult.ok(result);
    }

    @Operation(summary = "搜索待接续职工（完整身份证号，或 姓名+身份证后4位；不提供全量列表）")
    @GetMapping("/employees/rehire-search")
    public ApiResult<List<Map<String, Object>>> rehireSearch(@RequestParam(required = false) String keyword) {
        return ApiResult.ok(transferBizService.searchRehire(keyword));
    }

    @Operation(summary = "企业发起接续参保（姓名/身份证/基数取链上档案，企业私钥签名）")
    @PostMapping("/employees/rehire")
    public ApiResult<SendResult> rehire(@Valid @RequestBody Requests.AddressOnly request) {
        AuthUser me = AuthContext.require();
        SendResult result = idempotencyService.run("sponsor-rehire",
                String.valueOf(me.getAccountId()), request,
                () -> transferBizService.rehire(request.getAddress()));
        cacheInvalidationService.afterChainWrite();
        return ApiResult.ok(result);
    }

    @Operation(summary = "减员（停缴）：转移放行的前置条件")
    @PostMapping("/employees/suspend")
    public ApiResult<SendResult> suspend(@Valid @RequestBody Requests.Suspend request) {
        AuthUser me = AuthContext.require();
        SendResult result = idempotencyService.run("sponsor-suspend",
                String.valueOf(me.getAccountId()), request,
                () -> accountBizService.suspendEmployee(request.getEmployee(), request.getReason()));
        cacheInvalidationService.afterChainWrite();
        return ApiResult.ok(result);
    }

    @Operation(summary = "调整职工工资并重算缴费基数")
    @PostMapping("/employees/salary")
    public ApiResult<SendResult> setSalary(@Valid @RequestBody Requests.Salary request) {
        AuthUser me = AuthContext.require();
        SendResult result = idempotencyService.run("sponsor-salary",
                String.valueOf(me.getAccountId()), request,
                () -> accountBizService.setSalary(request.getEmployee(), request.getSalary()));
        cacheInvalidationService.afterChainWrite();
        return ApiResult.ok(result);
    }

    @Operation(summary = "为职工缴纳当月费用（个人 8% + 统筹 19%，从单位统筹账户扣减）")
    @PostMapping("/payments")
    public ApiResult<SendResult> makePayment(@Valid @RequestBody Requests.AddressOnly request) {
        AuthUser me = AuthContext.require();
        SendResult result = idempotencyService.run("sponsor-payment",
                String.valueOf(me.getAccountId()), request,
                () -> accountBizService.makePayment(request.getAddress()));
        cacheInvalidationService.afterChainWrite();
        return ApiResult.ok(result);
    }

    @Operation(summary = "查本公司职工地址列表（分页）")
    @GetMapping("/employees")
    public ApiResult<Map<String, Object>> personList(@RequestParam String sponsor,
                                                     @RequestParam(defaultValue = "0") int offset,
                                                     @RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(accountBizService.getPersonList(sponsor, offset, limit));
    }

    @Operation(summary = "本单位职工明细（含姓名，供缴费管理页按姓名下拉选人；不需要 sponsor 参数，"
            + "取当前登录单位自己的链上地址；逐个读链上账户，limit 上限 50、默认 20）")
    @GetMapping("/employees/detail")
    public ApiResult<List<EmployeeDetailView>> employeeDetails(
            @RequestParam(required = false) Integer offset,
            @RequestParam(required = false) Integer limit) {
        return ApiResult.ok(accountBizService.getEmployeeDetails(offset, limit));
    }

    @Operation(summary = "查公司资金账户（余额 / 累计已缴 / 职工数）")
    @GetMapping("/account")
    public ApiResult<Map<String, Object>> sponsorAccount(@RequestParam String sponsor) {
        return ApiResult.ok(accountBizService.getSponsorAccount(sponsor));
    }

    @Operation(summary = "查某职工的缴费记录（限本公司职工）")
    @GetMapping("/payments")
    public ApiResult<Map<String, Object>> paymentRecords(@RequestParam String employee,
                                                         @RequestParam(defaultValue = "0") int offset,
                                                         @RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(accountBizService.getPaymentRecords(employee, offset, limit));
    }

    @Operation(summary = "查某职工参保账户（限本公司职工）")
    @GetMapping("/employees/account")
    public ApiResult<Map<String, Object>> employeeAccount(@RequestParam String employee) {
        return ApiResult.ok(accountBizService.getInsuranceAccount(employee));
    }

    @Operation(summary = "缴费试算：按链下生效政策参数做基数夹取与费率拆分（合约已移除 quotePayment）")
    @GetMapping("/payments/quote")
    public ApiResult<Map<String, Object>> quotePayment(@RequestParam Object salary) {
        return ApiResult.ok(policyChainService.quote(salary));
    }

    @Operation(summary = "缴费试算（按职工当前基数）：返回基数夹取结果与政策参数明细")
    @GetMapping("/payments/trial")
    public ApiResult<Map<String, Object>> trialPayment(@RequestParam String employee) {
        return ApiResult.ok(accountBizService.trialPayment(employee));
    }
}
