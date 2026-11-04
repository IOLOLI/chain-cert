package com.chainsip.policy;

import com.chainsip.policy.service.PolicyChainService;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 政策参数「链下生效值 vs 链上已发布值」的一致性巡检。
 *
 * <p>改造前：合约把费率 / 基数 / 退休年龄写成 constant，巡检拿链下参数与"合约常量基线"对比，
 * 只能发现漂移、不能让新政策生效。
 *
 * <p>改造后：合约通过 {@code publishPolicy(packed)} 接收版本化参数，链上业务实时读取；
 * 巡检改为对比「链下当前生效参数」与「链上 policyPacked」，不一致说明发布没跟上
 * （后端会在 {@code /api/chain/status} 的 {@code policyConsistency} 上报红，并每 10 分钟自动补发）。
 */
@Component
public class PolicyConsistencyCheck {

    private final PolicyChainService policyChainService;

    public PolicyConsistencyCheck(PolicyChainService policyChainService) {
        this.policyChainService = policyChainService;
    }

    /** 返回结构与改造前保持一致：{@code consistent / region / items / note}，前端无需调整。 */
    public Map<String, Object> check() {
        return policyChainService.chainStatus();
    }
}
