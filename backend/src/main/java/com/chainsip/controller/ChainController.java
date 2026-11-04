package com.chainsip.controller;

import com.chainsip.chain.dto.BlockInfo;
import com.chainsip.chain.dto.CallResult;
import com.chainsip.chain.dto.ChainAccount;
import com.chainsip.chain.dto.SendResult;
import com.chainsip.common.ApiResult;
import com.chainsip.config.ChainProperties;
import com.chainsip.dto.Requests;
import com.chainsip.policy.PolicyConsistencyCheck;
import com.chainsip.security.RequireRole;
import com.chainsip.service.AuthService;
import com.chainsip.service.ChainTxService;
import com.chainsip.service.InstitutionKeyService;
import com.chainsip.service.RedisCacheService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 链信息与通用调用。
 *
 * <p>通用调用接口让 Swagger 里可以直接调试合约的任意函数，非常适合骨架阶段联调；
 * 上生产后业务前端应走各角色的专用接口。
 * 注意：通用"发交易"接口并不等于"任意越权"——合约会按 msg.sender 再判一次权限，
 * 链下角色与链上不一致时交易照样被回滚。
 */
@Tag(name = "02 链信息与通用调用", description = "链状态 / 最新区块 / 资金总览 / 通用 call 与 send")
@RestController
@RequestMapping("/api/chain")
public class ChainController {

    private final ChainTxService chain;
    private final ChainProperties chainProperties;
    private final InstitutionKeyService institutionKeyService;
    private final AuthService authService;
    private final PolicyConsistencyCheck policyConsistencyCheck;
    private final RedisCacheService redisCache;

    public ChainController(ChainTxService chain, ChainProperties chainProperties,
                           InstitutionKeyService institutionKeyService, AuthService authService,
                           PolicyConsistencyCheck policyConsistencyCheck, RedisCacheService redisCache) {
        this.chain = chain;
        this.chainProperties = chainProperties;
        this.institutionKeyService = institutionKeyService;
        this.authService = authService;
        this.policyConsistencyCheck = policyConsistencyCheck;
        this.redisCache = redisCache;
    }

    @Operation(summary = "链网关状态（未就绪时给出具体排查提示）")
    @GetMapping("/status")
    public ApiResult<Map<String, Object>> status() {
        Map<String, Object> data = new HashMap<>();
        data.put("enabled", chainProperties.isEnabled());
        data.put("ready", chain.isReady());
        data.put("message", chain.statusMessage());
        data.put("sdkConfigFile", chainProperties.getSdkConfigFile());
        data.put("groupId", chainProperties.getGroupId());
        data.put("peers", chainProperties.getPeers());
        data.put("coreContract", chainProperties.getContracts().getCore());
        data.put("transferContract", chainProperties.getContracts().getTransfer());
        data.put("coreDeployed", chainProperties.isDeployed(ChainProperties.CORE));
        data.put("transferDeployed", chainProperties.isDeployed(ChainProperties.TRANSFER));

        // 后端签 ADMIN 类交易用的机构地址 + 合约里该地址的角色。
        // 这两项用来对齐"合约部署者"：合约的第一个 ADMIN 就是部署合约的账户，
        // 若它 != adminAddress，需要在 WeBASE 里用部署者账户调 addAdmin(adminAddress)，
        // 否则 setTransferContract / addBureaus / addSponsorAccount / addRegulatorAccount 会被回滚（NotAnAdmin）。
        String adminAddress = "";
        String adminRole = "";
        try {
            ChainAccount admin = institutionKeyService.resolveChainAccount(AuthService.ROLE_ADMIN);
            if (admin != null && admin.getAddress() != null) {
                adminAddress = admin.getAddress();
                adminRole = authService.onChainRole(adminAddress);
            }
        } catch (Exception ignored) {
            // 链未就绪 / 未配置合约 / 没有 ADMIN 机构私钥时留空即可，不影响状态接口本身
        }
        data.put("adminAddress", adminAddress);
        data.put("adminOnChainRole", adminRole == null ? "" : adminRole);
        data.put("contractCompat", contractCompat());
        // 参数生效值 vs 合约常量：不一致时前端在养老金页顶部告警（一期双口径漂移的唯一发现手段）
        try {
            data.put("policyConsistency", policyConsistencyCheck.check());
        } catch (RuntimeException e) {
            data.put("policyConsistency", null);
        }
        return ApiResult.ok(data);
    }

    // ------------------------------------------------------------------
    // 链上合约版本探针
    // ------------------------------------------------------------------

    /** 探针结果缓存：一次链调用有成本，60 秒内复用；合约重启/重新部署后最迟一分钟自动跟上。 */
    private static final long COMPAT_TTL_MILLIS = 60_000L;
    private static final String COMPAT_CACHE_KEY = "chain:contract-compat:v1";

    private volatile Map<String, Object> compatCache;
    private volatile long compatCheckedAt;

    /**
     * 链上合约版本探针：调一个「只有新版本合约才有的无参 view」来判断链上字节码是否跟得上后端 ABI。
     *
     * <p>为什么需要它：改了合约之后最容易犯的错是<b>只重启后端、没重新部署合约</b>。
     * 后端 ABI 已经是新的，链上还是旧字节码，任何只有新版本才有的函数都会以
     * {@code code=2002 Revert instruction} 回滚——一句话，看不出是哪个方法、也不知道往哪修。
     * 这里把"链上版本是否已升级"变成状态接口里的一个布尔值，前端的合约面板可以直接显示。
     *
     * <p>{@code pensionReady=null} 表示暂时判断不了（链未就绪），不要当成 false 展示。
     */
    private Map<String, Object> contractCompat() {
        Map<String, Object> cached = cachedContractCompat();
        if (cached != null) {
            return cached;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("probe", "totalPensionPaid");
        out.put("checked", false);
        out.put("pensionReady", null);
        out.put("detail", "链未就绪，暂不判断合约版本");
        if (chain.isReady()) {
            try {
                chain.call(ChainProperties.CORE, "totalPensionPaid");
                out.put("checked", true);
                out.put("pensionReady", Boolean.TRUE);
                out.put("detail", "链上合约已包含养老金函数（totalPensionPaid）");
            } catch (RuntimeException e) {
                out.put("checked", true);
                out.put("pensionReady", Boolean.FALSE);
                out.put("detail", "链上合约不支持 totalPensionPaid()：链上仍是升级前的合约，"
                        + "养老金相关功能会全部回滚。请按 README §5.4.6 重新部署核心合约，"
                        + "并把新地址写进 CHAINSIP_CORE_ADDRESS");
            }
        }
        writeCompatCache(out);
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> cachedContractCompat() {
        Optional<Object> remote = redisCache.get(COMPAT_CACHE_KEY);
        if (remote.isPresent() && remote.get() instanceof Map) {
            return (Map<String, Object>) remote.get();
        }
        Map<String, Object> local = compatCache;
        if (local != null && System.currentTimeMillis() - compatCheckedAt < COMPAT_TTL_MILLIS) {
            return local;
        }
        return null;
    }

    private void writeCompatCache(Map<String, Object> value) {
        compatCache = value;
        compatCheckedAt = System.currentTimeMillis();
        redisCache.put(COMPAT_CACHE_KEY, value, Duration.ofMillis(COMPAT_TTL_MILLIS));
    }

    @Operation(summary = "重新初始化链网关（改完配置或链刚起来时调用）")
    @PostMapping("/refresh")
    public ApiResult<Boolean> refresh() {
        compatCache = null;
        compatCheckedAt = 0L;
        redisCache.delete(COMPAT_CACHE_KEY);
        return ApiResult.ok(chain.refresh());
    }

    @Operation(summary = "共识拓扑：sealers 出块节点 / observers 观察节点（监管节点不参与共识，只同步并复核账本）")
    @GetMapping("/topology")
    public ApiResult<Map<String, Object>> topology() {
        return ApiResult.ok(chain.topology());
    }

    @Operation(summary = "最新区块高度 + 最新交易哈希")
    @GetMapping("/block")
    public ApiResult<BlockInfo> block() {
        return ApiResult.ok(chain.latestBlock());
    }

    @Operation(summary = "平台资金/权益总览（对账口径见合约 getSystemStats）")
    @GetMapping("/stats")
    public ApiResult<CallResult> stats() {
        return ApiResult.ok(chain.call(ChainProperties.CORE, "getSystemStats"));
    }

    @Operation(summary = "通用只读调用：contract=ChainSIP|ChainSIPTransfer，function=合约函数名")
    @PostMapping("/call")
    public ApiResult<CallResult> call(@Valid @RequestBody Requests.ChainInvoke request) {
        return ApiResult.ok(chain.call(request.getContract(), request.getFunction(), request.getParams()));
    }

    @Operation(summary = "通用发交易（仅平台管理员；用当前账号所属机构的私钥签名，权限最终由合约裁决）")
    @RequireRole({"ADMIN"})
    @PostMapping("/send")
    public ApiResult<SendResult> send(@Valid @RequestBody Requests.ChainInvoke request) {
        return ApiResult.ok(chain.send(chain.currentInstitutionCode(),
                request.getContract(), request.getFunction(), request.getParams()));
    }
}
