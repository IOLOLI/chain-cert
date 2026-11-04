package com.chainsip.controller;

import com.chainsip.common.ApiResult;
import com.chainsip.config.ChainProperties;
import com.chainsip.security.RequireRole;
import com.chainsip.service.ChainTxService;
import com.chainsip.service.TransferBizService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 监管（REGULATOR）侧：只读审计。
 *
 * <p>监管账户在合约里是纯只读角色：能查全平台账户、流水、资金与转移记录，
 * 但注资、缴费、建档、放行等写操作一律被合约拒绝（测试里逐项验证过）。
 * 这里也刻意不提供任何写接口。
 */
@Tag(name = "07 REGULATOR 监管审计", description = "监管大屏 / 资金对账 / 城市基金池 / 转移与机构名单（全只读）")
@RestController
@RequestMapping("/api/regulator")
@RequireRole({"ADMIN", "REGULATOR"})
public class RegulatorController {

    private final TransferBizService transferBizService;
    private final ChainTxService chain;

    public RegulatorController(TransferBizService transferBizService, ChainTxService chain) {
        this.transferBizService = transferBizService;
        this.chain = chain;
    }

    @Operation(summary = "监管大屏：平台规模 + 资金/权益对账数据")
    @GetMapping("/dashboard")
    public ApiResult<Map<String, Object>> dashboard() {
        return ApiResult.ok(transferBizService.getRegulatorDashboard());
    }

    @Operation(summary = "资金/权益总览（三条对账恒等式的输入）")
    @GetMapping("/system-stats")
    public ApiResult<Map<String, Object>> systemStats() {
        return ApiResult.ok(transferBizService.getSystemStats());
    }

    @Operation(summary = "某城市统筹基金池余额")
    @GetMapping("/city-pool")
    public ApiResult<Object> cityPool(@RequestParam String city) {
        return ApiResult.ok(transferBizService.getCityPool(city));
    }

    @Operation(summary = "转移申请详情")
    @GetMapping("/transfers/{id}")
    public ApiResult<Map<String, Object>> transfer(@PathVariable Long id) {
        return ApiResult.ok(transferBizService.getTransfer(id));
    }

    @Operation(summary = "转移申请总数")
    @GetMapping("/transfers/count")
    public ApiResult<Object> transferCount() {
        return ApiResult.ok(transferBizService.getTransferCount());
    }

    @Operation(summary = "社保局总数")
    @GetMapping("/bureaus/count")
    public ApiResult<Object> bureauCount() {
        return ApiResult.ok(transferBizService.getBureauCount());
    }

    @Operation(summary = "监管账户名单")
    @GetMapping("/accounts")
    public ApiResult<com.chainsip.chain.dto.CallResult> regulators(@RequestParam(defaultValue = "0") int offset,
                                                                   @RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(chain.call(ChainProperties.CORE, "getRegulatorList", offset, limit));
    }

    @Operation(summary = "公司名单（审计用）")
    @GetMapping("/sponsors")
    public ApiResult<com.chainsip.chain.dto.CallResult> sponsors(@RequestParam(defaultValue = "0") int offset,
                                                                 @RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(chain.call(ChainProperties.CORE, "getSponsorList", offset, limit));
    }
}
