package com.chainsip.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chainsip.chain.ChainGateway;
import com.chainsip.common.ApiResult;
import com.chainsip.common.BizException;
import com.chainsip.common.ErrorCode;
import com.chainsip.entity.Account;
import com.chainsip.entity.ChainEvent;
import com.chainsip.entity.ChainTxLog;
import com.chainsip.entity.InstitutionKey;
import com.chainsip.mapper.AccountMapper;
import com.chainsip.mapper.ChainEventMapper;
import com.chainsip.mapper.ChainTxLogMapper;
import com.chainsip.security.AuthContext;
import com.chainsip.security.AuthUser;
import com.chainsip.service.AuthService;
import com.chainsip.service.InstitutionKeyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 区块链浏览器：直接读链上的区块 / 交易 / 地址，而不是读链下索引表。
 *
 * <p>与「链上索引」页的区别：那边是 {@code cs_chain_event} / {@code cs_chain_tx_log} 的链下索引
 * （只包含本系统发起的交易，丢了也能重放事件重建）；这里是 FISCO BCOS 节点的原始账本视图，
 * 能看到别人（例如监管节点、控制台）直接发到链上的交易，用于核对"链上到底发生了什么"。
 *
 * <p>权限：对<b>所有已登录角色</b>开放（链上账本本身是公开数据）。为兼顾隐私，
 * 链下索引里的明文参数（{@code paramsJson}）、事件原始 payload 与地址关联的平台账号明细
 * 只返回给 ADMIN / REGULATOR；其它角色仍能看到区块、交易、方法与链上角色。
 */
@Tag(name = "09 区块链浏览器", description = "区块 / 交易 / 地址只读查询（直接读节点账本）")
@RestController
@RequestMapping("/api/chain/explorer")
public class ChainExplorerController {

    private final ChainGateway chain;
    private final ChainTxLogMapper txLogMapper;
    private final ChainEventMapper eventMapper;
    private final InstitutionKeyService institutionKeyService;
    private final AccountMapper accountMapper;
    private final AuthService authService;

    public ChainExplorerController(ChainGateway chain, ChainTxLogMapper txLogMapper,
                                   ChainEventMapper eventMapper,
                                   InstitutionKeyService institutionKeyService,
                                   AccountMapper accountMapper, AuthService authService) {
        this.chain = chain;
        this.txLogMapper = txLogMapper;
        this.eventMapper = eventMapper;
        this.institutionKeyService = institutionKeyService;
        this.accountMapper = accountMapper;
        this.authService = authService;
    }

    @Operation(summary = "链概览：最新高度、累计/失败交易数、群组、节点版本、两组合约地址")
    @GetMapping("/overview")
    public ApiResult<Map<String, Object>> overview() {
        requireReady();
        return ApiResult.ok(chain.chainOverview());
    }

    @Operation(summary = "最新区块列表（按高度倒序，不含完整交易）")
    @GetMapping("/blocks")
    public ApiResult<List<Map<String, Object>>> blocks(@RequestParam(defaultValue = "15") int limit,
                                                       @RequestParam(required = false) Long before) {
        requireReady();
        int size = Math.max(1, Math.min(limit, 50));
        long latest = Long.parseLong(String.valueOf(chain.latestBlock().getBlockNumber()));
        long start = before == null ? latest : Math.min(before, latest);
        List<Map<String, Object>> out = new ArrayList<>();
        for (long number = start; number >= 0 && out.size() < size; number--) {
            Map<String, Object> block = chain.blockByNumber(number, false);
            if (block != null && !block.isEmpty()) {
                out.add(block);
            }
        }
        return ApiResult.ok(out);
    }

    @Operation(summary = "区块详情（高度或 0x 区块哈希；带完整交易与本系统的方法名/事件名）")
    @GetMapping("/blocks/{id}")
    public ApiResult<Map<String, Object>> block(@PathVariable String id) {
        requireReady();
        Map<String, Object> block;
        try {
            if (id.startsWith("0x") || id.startsWith("0X")) {
                block = chain.blockByHash(id, true);
            } else {
                try {
                    block = chain.blockByNumber(Long.parseLong(id), true);
                } catch (NumberFormatException e) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "区块标识应为高度数字或 0x 区块哈希");
                }
            }
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BizException(ErrorCode.NOT_FOUND, "链上不存在该区块：" + id);
        }
        if (block == null || block.isEmpty() || block.get("hash") == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "链上不存在该区块：" + id);
        }
        enrichBlockTransactions(block);
        return ApiResult.ok(block);
    }

    @Operation(summary = "交易详情（区块、发送方、合约、方法、回执状态、输入数据、事件日志）")
    @GetMapping("/transactions/{hash}")
    public ApiResult<Map<String, Object>> transaction(@PathVariable String hash) {
        requireReady();
        Map<String, Object> tx = chain.transactionByHash(hash);
        ChainTxLog log = findTxLog(hash);
        if (log != null) {
            Map<String, Object> local = new LinkedHashMap<>();
            local.put("contractName", log.getContractName());
            local.put("functionName", log.getFunctionName());
            local.put("institutionCode", log.getInstitutionCode());
            local.put("senderAddress", log.getSenderAddress());
            if (isAuditor()) {
                local.put("paramsJson", log.getParamsJson());
            }
            local.put("success", log.getSuccess());
            local.put("message", log.getMessage());
            local.put("createdAt", log.getCreatedAt());
            tx.put("localRecord", local);
        }
        tx.put("events", eventsOf(hash));
        return ApiResult.ok(tx);
    }

    @Operation(summary = "地址详情：链上角色 + 机构链账户 + 平台账号")
    @GetMapping("/addresses/{address}")
    public ApiResult<Map<String, Object>> address(@PathVariable String address) {
        requireReady();
        String addr = address == null ? "" : address.trim().toLowerCase();
        if (!addr.startsWith("0x") || addr.length() != 42) {
            throw new BizException(ErrorCode.BAD_REQUEST, "地址应为 0x + 40 位十六进制");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("address", addr);
        out.put("onChainRole", safeRole(addr));

        InstitutionKey key = null;
        for (InstitutionKey item : institutionKeyService.listAll()) {
            if (item.getAddress() != null && addr.equalsIgnoreCase(item.getAddress().trim())) {
                key = item;
                break;
            }
        }
        if (key != null) {
            out.put("institutionCode", key.getCode());
            out.put("institutionName", key.getName());
            out.put("institutionRole", key.getRole());
            out.put("institutionEnabled", key.getEnabled());
            Account account = isAuditor() ? accountMapper.selectOne(new LambdaQueryWrapper<Account>()
                    .eq(Account::getInstitutionCode, key.getCode()).last("limit 1")) : null;
            if (account != null) {
                out.put("username", account.getUsername());
                out.put("displayName", account.getDisplayName());
                out.put("accountRole", account.getRole());
                out.put("accountStatus", account.getStatus());
            }
        }
        return ApiResult.ok(out);
    }

    @Operation(summary = "统一搜索：区块高度 / 交易哈希 / 地址，返回类型与规范化后的目标")
    @GetMapping("/search")
    public ApiResult<Map<String, Object>> search(@RequestParam String q) {
        requireReady();
        String text = q == null ? "" : q.trim();
        if (text.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请输入区块高度、交易哈希或地址");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", text);
        if (text.matches("\\d+")) {
            out.put("type", "block");
            out.put("target", text);
            return ApiResult.ok(out);
        }
        if (text.startsWith("0x") || text.startsWith("0X")) {
            if (text.length() == 42) {
                out.put("type", "address");
                out.put("target", text.toLowerCase());
                return ApiResult.ok(out);
            }
            if (text.length() == 66) {
                try {
                    chain.transactionByHash(text);
                    out.put("type", "transaction");
                    out.put("target", text);
                    return ApiResult.ok(out);
                } catch (RuntimeException ignored) {
                    Map<String, Object> block = chain.blockByHash(text, false);
                    if (block != null && block.get("hash") != null) {
                        out.put("type", "block");
                        out.put("target", text);
                        return ApiResult.ok(out);
                    }
                }
            }
        }
        throw new BizException(ErrorCode.NOT_FOUND, "没有找到对应的区块 / 交易 / 地址：" + text);
    }

    // ------------------------------------------------------------------
    // 链下索引补充
    // ------------------------------------------------------------------

    /** 给区块里的每笔交易补上本系统记录的方法名、成功与否、事件名（没有记录的保持为空）。 */
    private void enrichBlockTransactions(Map<String, Object> block) {
        @SuppressWarnings("unchecked")
        List<Object> transactions = (List<Object>) block.get("transactions");
        if (transactions == null || transactions.isEmpty()) {
            return;
        }
        List<String> hashes = new ArrayList<>();
        for (Object item : transactions) {
            if (item instanceof Map) {
                Object hash = ((Map<?, ?>) item).get("hash");
                if (hash != null) {
                    hashes.add(String.valueOf(hash));
                }
            } else if (item != null) {
                hashes.add(String.valueOf(item));
            }
        }
        if (hashes.isEmpty()) {
            return;
        }
        Map<String, ChainTxLog> logs = new HashMap<>();
        for (ChainTxLog log : txLogMapper.selectList(new LambdaQueryWrapper<ChainTxLog>()
                .in(ChainTxLog::getTxHash, hashes))) {
            logs.put(log.getTxHash(), log);
        }
        Map<String, List<Map<String, Object>>> events = new HashMap<>();
        for (ChainEvent event : eventMapper.selectList(new LambdaQueryWrapper<ChainEvent>()
                .in(ChainEvent::getTxHash, hashes))) {
            events.computeIfAbsent(event.getTxHash(), key -> new ArrayList<>()).add(eventView(event));
        }
        for (Object item : transactions) {
            if (!(item instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> tx = (Map<String, Object>) item;
            String hash = String.valueOf(tx.get("hash"));
            ChainTxLog log = logs.get(hash);
            if (log != null) {
                tx.put("contractName", log.getContractName());
                tx.put("functionName", log.getFunctionName());
                tx.put("success", log.getSuccess());
                tx.put("institutionCode", log.getInstitutionCode());
            }
            List<Map<String, Object>> txEvents = events.get(hash);
            if (txEvents != null) {
                tx.put("events", txEvents);
            }
        }
    }

    private ChainTxLog findTxLog(String hash) {
        List<ChainTxLog> logs = txLogMapper.selectList(new LambdaQueryWrapper<ChainTxLog>()
                .eq(ChainTxLog::getTxHash, hash).last("limit 1"));
        return logs.isEmpty() ? null : logs.get(0);
    }

    private List<Map<String, Object>> eventsOf(String hash) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ChainEvent event : eventMapper.selectList(new LambdaQueryWrapper<ChainEvent>()
                .eq(ChainEvent::getTxHash, hash).orderByAsc(ChainEvent::getId))) {
            out.add(eventView(event));
        }
        return out;
    }

    private Map<String, Object> eventView(ChainEvent event) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("eventName", event.getEventName());
        item.put("contractName", event.getContractName());
        item.put("employeeAddress", event.getEmployeeAddress());
        item.put("sponsorAddress", event.getSponsorAddress());
        item.put("amount", event.getAmount());
        item.put("personalAmount", event.getPersonalAmount());
        item.put("overallAmount", event.getOverallAmount());
        if (isAuditor()) {
            item.put("payload", event.getPayload());
        }
        return item;
    }

    /** 是否审计角色：链上原始数据人人可看，链下索引里的明文参数/账号明细只给 ADMIN、REGULATOR。 */
    private boolean isAuditor() {
        AuthUser user = AuthContext.get();
        return user != null && user.hasRole(AuthService.ROLE_ADMIN, AuthService.ROLE_REGULATOR);
    }

    /** 链未就绪时统一返回 2001，避免把 SDK 的 ClientException 直接抛成 500。 */
    private void requireReady() {
        if (!chain.isReady()) {
            throw new BizException(ErrorCode.CHAIN_NOT_READY, chain.statusMessage());
        }
    }

    private String safeRole(String address) {
        try {
            return authService.onChainRole(address);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
