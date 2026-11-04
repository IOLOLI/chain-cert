package com.chainsip.policy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chainsip.policy.entity.PolicyParameter;
import com.chainsip.policy.mapper.PolicyParameterMapper;
import com.chainsip.policy.service.PolicyQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 政策参数到期切换任务（每日 01:00）。
 *
 * <p>管理端可以"预置未来生效"的版本（状态 DRAFT，如 2030-01-01 起最低缴费年限调到 216 个月）。
 * 本任务每天扫一遍：
 * <ol>
 *   <li>{@code DRAFT 且 valid_from <= 今天} → 置 ACTIVE，并把它之前仍然 open 的旧版截止；</li>
 *   <li>{@code ACTIVE 且 valid_to < 今天} → 置 TERMINATED。</li>
 * </ol>
 * 全程幂等，重复执行结果不变；只有真的改了行才清参数缓存。
 *
 * <p>不提供"提前生效"写接口：时间轴校验只能有一条路径（改 valid_from），否则审计会失真。
 */
@Component
public class PolicyActivationScheduler {

    private static final Logger log = LoggerFactory.getLogger(PolicyActivationScheduler.class);

    private final PolicyParameterMapper parameterMapper;
    private final PolicyQueryService queryService;

    public PolicyActivationScheduler(PolicyParameterMapper parameterMapper, PolicyQueryService queryService) {
        this.parameterMapper = parameterMapper;
        this.queryService = queryService;
    }

    @Scheduled(cron = "0 0 1 * * *")
    public void scheduledActivate() {
        int changed = activateDueVersions(LocalDate.now());
        if (changed > 0) {
            log.info("政策参数到期切换完成：{} 行状态变更", changed);
        }
    }

    /** 执行一次切换，返回本次改动的行数（供定时任务与测试共用）。 */
    @Transactional
    public int activateDueVersions(LocalDate today) {
        int changed = 0;

        // 1) 到期的 DRAFT → ACTIVE
        List<PolicyParameter> due = parameterMapper.selectList(new LambdaQueryWrapper<PolicyParameter>()
                .eq(PolicyParameter::getStatus, "DRAFT")
                .le(PolicyParameter::getValidFrom, today));
        for (PolicyParameter draft : due) {
            closePreviousVersions(draft);
            draft.setStatus("ACTIVE");
            parameterMapper.updateById(draft);
            changed++;
        }

        // 2) 已过期的 ACTIVE → TERMINATED
        List<PolicyParameter> expired = parameterMapper.selectList(new LambdaQueryWrapper<PolicyParameter>()
                .eq(PolicyParameter::getStatus, "ACTIVE")
                .lt(PolicyParameter::getValidTo, today));
        for (PolicyParameter row : expired) {
            row.setStatus("TERMINATED");
            parameterMapper.updateById(row);
            changed++;
        }

        if (changed > 0) {
            queryService.evict();
        }
        return changed;
    }

    /** 把同一参数、同一地区里仍然 open 的更早版本截止到新版本生效前一日。 */
    private void closePreviousVersions(PolicyParameter current) {
        List<PolicyParameter> older = parameterMapper.selectList(new LambdaQueryWrapper<PolicyParameter>()
                .eq(PolicyParameter::getParamCode, current.getParamCode())
                .eq(PolicyParameter::getRegionCode, current.getRegionCode())
                .eq(PolicyParameter::getStatus, "ACTIVE")
                .lt(PolicyParameter::getVersionNo, current.getVersionNo()));
        for (PolicyParameter row : older) {
            if (row.getValidTo() != null && row.getValidTo().isBefore(current.getValidFrom())) {
                continue;
            }
            row.setValidTo(current.getValidFrom().minusDays(1));
            row.setStatus("TERMINATED");
            parameterMapper.updateById(row);
        }
    }
}
