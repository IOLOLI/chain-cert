package com.chainsip.policy.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chainsip.common.BizException;
import com.chainsip.common.ErrorCode;
import com.chainsip.policy.entity.PolicyDefinition;
import com.chainsip.policy.entity.PolicyParameter;
import com.chainsip.policy.mapper.PolicyDefinitionMapper;
import com.chainsip.policy.mapper.PolicyParameterMapper;
import com.chainsip.service.RedisCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 政策参数只读查询：按「参数编码 + 地区 + 时点」取生效值。
 *
 * <p>三级回落：<b>参数表生效版本 → 字典 default_value → 调用方给的硬默认（= 合约常量）</b>。
 * 这样即便参数表是空的 / 服务刚部署没跑种子，缴费、退休、发放的主流程也不会中断。
 *
 * <p>地区优先：先取本城市（{@code region_code = city}），没有再回落 {@code NATIONAL}。
 *
 * <p><b>缓存</b>：参数表是"读多写少、总量很小"的配置数据，一期不引 Caffeine，
 * 用进程内 {@code volatile List} 全量缓存；管理端追加版本后调用 {@link #evict()}，
 * 下一次查询惰性重建。缓存内容为全表（含历史版本），因为复算/追溯要查过去的时点。
 */
@Service
public class PolicyQueryService {

    private static final Logger log = LoggerFactory.getLogger(PolicyQueryService.class);

    /** 全国地区编码，与参数表 region_code 的口径一致。 */
    public static final String NATIONAL = "NATIONAL";

    private static final String REDIS_KEY = "policy:parameters:v1";
    private static final Duration REDIS_TTL = Duration.ofMinutes(10);

    /**
     * 本地缓存最多保留 5 秒：多实例部署时，管理端在某台机器发布新版本后，
     * 其他实例最迟 5 秒就能通过 Redis 看到变化；5 秒内继续用本地副本承担高频读取。
     */
    private static final long LOCAL_TTL_MILLIS = 5_000L;

    private final PolicyParameterMapper parameterMapper;
    private final PolicyDefinitionMapper definitionMapper;
    private final RedisCacheService redisCacheService;

    /** 全量参数版本缓存；null = 尚未加载。 */
    private volatile List<PolicyParameter> cache;
    private volatile long cacheLoadedAt;

    public PolicyQueryService(PolicyParameterMapper parameterMapper, PolicyDefinitionMapper definitionMapper,
                              RedisCacheService redisCacheService) {
        this.parameterMapper = parameterMapper;
        this.definitionMapper = definitionMapper;
        this.redisCacheService = redisCacheService;
    }

    // ------------------------------------------------------------------
    // 生效值解析
    // ------------------------------------------------------------------

    /** 取某参数在某地区某时点生效的原始字符串值；没有生效版本时返回 empty。 */
    public Optional<String> resolve(String code, String region, LocalDate date) {
        Optional<PolicyParameter> hit = resolveParameter(code, region, date);
        return hit.isPresent() ? Optional.of(hit.get().getParamValue()) : Optional.<String>empty();
    }

    /** 取原始值失败时再查字典默认值；都没有返回 null。 */
    public String resolveOrDefault(String code, String region, LocalDate date) {
        Optional<String> value = resolve(code, region, date);
        if (value.isPresent()) {
            return value.get();
        }
        PolicyDefinition definition = definition(code);
        return definition == null ? null : definition.getDefaultValue();
    }

    /** 数值参数：参数表 → 字典默认 → 硬默认；值非法直接报错，不静默吞掉。 */
    public BigDecimal resolveDecimal(String code, String region, LocalDate date, String hardDefault) {
        String value = resolveOrDefault(code, region, date);
        if (value == null) {
            value = hardDefault;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "政策参数 [" + code + "] 值非法（期望数值）: " + value);
        }
    }

    /** 整型参数。 */
    public int resolveInt(String code, String region, LocalDate date, int hardDefault) {
        try {
            return resolveDecimal(code, region, date, String.valueOf(hardDefault)).intValue();
        } catch (BizException e) {
            throw e;
        }
    }

    /** 日期参数（yyyy-MM-dd）。 */
    public LocalDate resolveDate(String code, String region, LocalDate date, LocalDate hardDefault) {
        String value = resolveOrDefault(code, region, date);
        if (value == null || value.trim().isEmpty()) {
            return hardDefault;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (RuntimeException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "政策参数 [" + code + "] 值非法（期望日期 yyyy-MM-dd）: " + value);
        }
    }

    /**
     * 某参数在某时点的生效版本号（找不到返回 null）。
     *
     * <p>核定单的 {@code policy_version_ref} 用它拼摘要，例如
     * {@code qual.min.months#6;benefit.basic.rate.yearly#3}，事后审计能直接定位到具体版本。
     */
    public Integer versionNoOf(String code, String region, LocalDate date) {
        Optional<PolicyParameter> hit = resolveParameter(code, region, date);
        return hit.isPresent() ? hit.get().getVersionNo() : null;
    }

    /** 取出命中行（先本地区，再全国，同一地区取版本号最大的）。 */
    public Optional<PolicyParameter> resolveParameter(String code, String region, LocalDate date) {
        List<PolicyParameter> candidates = effective(code, region, date);
        Optional<PolicyParameter> local = pick(candidates, region);
        if (local.isPresent()) {
            return local;
        }
        return pick(candidates, NATIONAL);
    }

    // ------------------------------------------------------------------
    // 字典
    // ------------------------------------------------------------------

    public PolicyDefinition definition(String code) {
        if (code == null || code.trim().isEmpty()) {
            return null;
        }
        return definitionMapper.selectOne(new LambdaQueryWrapper<PolicyDefinition>()
                .eq(PolicyDefinition::getParamCode, code.trim()));
    }

    /** 全部参数字典（按分类、编码排序，管理端左侧分组用）。 */
    public List<PolicyDefinition> definitions() {
        return definitionMapper.selectList(new LambdaQueryWrapper<PolicyDefinition>()
                .orderByAsc(PolicyDefinition::getCategory)
                .orderByAsc(PolicyDefinition::getParamCode));
    }

    // ------------------------------------------------------------------
    // 缓存
    // ------------------------------------------------------------------

    /** 清缓存：管理端发布新版本、到期切换任务执行后调用。 */
    public void evict() {
        cache = null;
        cacheLoadedAt = 0L;
        redisCacheService.delete(REDIS_KEY);
        log.debug("政策参数缓存已清空，下次查询将重新加载");
    }

    /** 预热缓存（启动时调用一次，避免首个业务请求承担全表加载的延迟）。 */
    public void warmUp() {
        int size = rows().size();
        log.info("政策参数缓存已预热：{} 个版本行", size);
    }

    /** 当前缓存的版本行数（健康检查 / 管理端展示用）。 */
    public int cachedRowCount() {
        return rows().size();
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 与查询条件匹配的全部版本（不区分地区），按版本号倒序。 */
    private List<PolicyParameter> effective(String code, String region, LocalDate date) {
        LocalDate day = date == null ? LocalDate.now() : date;
        List<PolicyParameter> result = new ArrayList<>();
        for (PolicyParameter row : rows()) {
            if (!code.equals(row.getParamCode())) {
                continue;
            }
            if (row.getValidFrom() != null && row.getValidFrom().isAfter(day)) {
                continue;
            }
            if (row.getValidTo() != null && row.getValidTo().isBefore(day)) {
                continue;
            }
            String rowRegion = row.getRegionCode();
            if (!NATIONAL.equals(rowRegion) && (region == null || !region.equals(rowRegion))) {
                continue;
            }
            result.add(row);
        }
        result.sort((a, b) -> Integer.compare(
                b.getVersionNo() == null ? 0 : b.getVersionNo(),
                a.getVersionNo() == null ? 0 : a.getVersionNo()));
        return result;
    }

    private Optional<PolicyParameter> pick(List<PolicyParameter> candidates, String region) {
        if (region == null) {
            return Optional.empty();
        }
        for (PolicyParameter row : candidates) {
            if (region.equals(row.getRegionCode())) {
                return Optional.of(row);
            }
        }
        return Optional.empty();
    }

    /** 全量加载（双重检查，避免并发重复查库）。 */
    private List<PolicyParameter> rows() {
        List<PolicyParameter> local = cache;
        if (local != null && System.currentTimeMillis() - cacheLoadedAt < LOCAL_TTL_MILLIS) {
            return local;
        }
        synchronized (this) {
            local = cache;
            if (local != null && System.currentTimeMillis() - cacheLoadedAt < LOCAL_TTL_MILLIS) {
                return local;
            }
            local = loadFromRedis();
            if (local == null) {
                local = parameterMapper.selectList(null);
                if (local == null) {
                    local = new ArrayList<>();
                }
                redisCacheService.put(REDIS_KEY, local, REDIS_TTL);
                log.info("政策参数缓存已从数据库加载：{} 个版本行", local.size());
            } else {
                log.debug("政策参数缓存命中 Redis：{} 个版本行", local.size());
            }
            cache = local;
            cacheLoadedAt = System.currentTimeMillis();
        }
        return local;
    }

    private List<PolicyParameter> loadFromRedis() {
        Optional<Object> cached = redisCacheService.get(REDIS_KEY);
        if (!cached.isPresent() || !(cached.get() instanceof List)) {
            return null;
        }
        List<?> raw = (List<?>) cached.get();
        List<PolicyParameter> rows = new ArrayList<>();
        for (Object item : raw) {
            if (item instanceof PolicyParameter) {
                rows.add((PolicyParameter) item);
            }
        }
        // 非空列表却解不出实体，说明缓存结构不兼容：按未命中处理，回源数据库。
        if (rows.isEmpty() && !raw.isEmpty()) {
            return null;
        }
        return rows;
    }
}
