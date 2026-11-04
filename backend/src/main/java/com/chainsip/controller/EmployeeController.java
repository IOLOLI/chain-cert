package com.chainsip.controller;

import com.chainsip.chain.dto.SendResult;
import com.chainsip.common.ApiResult;
import com.chainsip.dto.Requests;
import com.chainsip.security.AuthContext;
import com.chainsip.security.AuthUser;
import com.chainsip.service.AccountBizService;
import com.chainsip.service.CacheInvalidationService;
import com.chainsip.service.IdempotencyService;
import com.chainsip.service.PensionBizService;
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
import java.util.Map;

/**
 * 职工（EMPLOYEE）侧：查自己的参保账户与缴费记录、发起跨城市转移。
 *
 * <p>这里不设 @RequireRole，因为"能不能看某个地址的账户"由合约判定
 * （{@code _requireAccountReader}：本人 / 所属单位 / ADMIN / REGULATOR）。
 * 用一个普通职工账号去查别人的地址，链上会直接回滚，正好演示"授权在链上"。
 */
@Tag(name = "05 EMPLOYEE 职工", description = "参保账户 / 缴费记录 / 转移申请 / 托管金额")
@RestController
@RequestMapping("/api/employee")
public class EmployeeController {

    private final AccountBizService accountBizService;
    private final TransferBizService transferBizService;
    private final PensionBizService pensionBizService;
    private final IdempotencyService idempotencyService;
    private final CacheInvalidationService cacheInvalidationService;

    public EmployeeController(AccountBizService accountBizService, TransferBizService transferBizService,
                              PensionBizService pensionBizService, IdempotencyService idempotencyService,
                              CacheInvalidationService cacheInvalidationService) {
        this.accountBizService = accountBizService;
        this.transferBizService = transferBizService;
        this.pensionBizService = pensionBizService;
        this.idempotencyService = idempotencyService;
        this.cacheInvalidationService = cacheInvalidationService;
    }

    @Operation(summary = "查参保账户（余额 / 缴费基数 / 状态 / 累计月数）")
    @GetMapping("/account")
    public ApiResult<Map<String, Object>> account(@RequestParam String employee) {
        return ApiResult.ok(accountBizService.getInsuranceAccount(employee));
    }

    @Operation(summary = "查缴费记录（分页）")
    @GetMapping("/payments")
    public ApiResult<Map<String, Object>> payments(@RequestParam String employee,
                                                   @RequestParam(defaultValue = "0") int offset,
                                                   @RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(accountBizService.getPaymentRecords(employee, offset, limit));
    }

    @Operation(summary = "查转移状态（状态 / 在途申请编号 / 参保城市）")
    @GetMapping("/transfer-state")
    public ApiResult<Map<String, Object>> transferState(@RequestParam String employee) {
        return ApiResult.ok(accountBizService.getTransferState(employee));
    }

    @Operation(summary = "查在途托管金额（转移放行后、接收前）")
    @GetMapping("/escrow")
    public ApiResult<Map<String, Object>> escrow(@RequestParam String employee) {
        return ApiResult.ok(accountBizService.getEscrow(employee));
    }

    @Operation(summary = "发起跨城市转移申请（转入地由目标城市的社保局绑定关系推导）")
    @PostMapping("/transfer/apply")
    public ApiResult<SendResult> apply(@Valid @RequestBody Requests.TransferApply request) {
        AuthUser me = AuthContext.require();
        SendResult result = idempotencyService.run("employee-transfer-apply",
                String.valueOf(me.getAccountId()), request,
                () -> accountBizService.applyForTransfer(request.getToCity()));
        cacheInvalidationService.afterChainWrite();
        return ApiResult.ok(result);
    }

    @Operation(summary = "查转移申请详情")
    @GetMapping("/transfer/{id}")
    public ApiResult<Map<String, Object>> transfer(@PathVariable Long id) {
        return ApiResult.ok(transferBizService.getTransfer(id));
    }

    @Operation(summary = "当前账号的全部转移申请（直接读链上，按编号倒序）")
    @GetMapping("/transfers")
    public ApiResult<Map<String, Object>> myTransfers(@RequestParam(defaultValue = "0") int offset,
                                                      @RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(transferBizService.listMyTransfers(offset, limit));
    }

    @Operation(summary = "查某城市绑定的社保局地址")
    @GetMapping("/bureau-of-city")
    public ApiResult<Object> bureauOfCity(@RequestParam String city) {
        return ApiResult.ok(transferBizService.bureauOfCity(city));
    }

    @Operation(summary = "领取本月养老金（职工本人私钥签名；需已办理退休，且距上次发放满 30 天）")
    @PostMapping("/pension/claim")
    public ApiResult<SendResult> claimPension() {
        AuthUser me = AuthContext.require();
        SendResult result = idempotencyService.run("employee-pension-claim",
                String.valueOf(me.getAccountId()), me.getInstitutionCode(),
                pensionBizService::claimMine);
        cacheInvalidationService.afterChainWrite();
        return ApiResult.ok(result);
    }
}
