package com.chainsip.service;

import com.chainsip.chain.ChainGateway;
import com.chainsip.chain.dto.ChainAccount;
import com.chainsip.chain.dto.SendResult;
import com.chainsip.common.BizException;
import com.chainsip.common.ContractErrorMessages;
import com.chainsip.common.ErrorCode;
import com.chainsip.entity.ChainEvent;
import com.chainsip.entity.ChainTxLog;
import com.chainsip.mapper.ChainEventMapper;
import com.chainsip.mapper.ChainTxLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 上链操作门面：统一负责"挑私钥 → 发交易 → 落留痕 → 建事件索引"。
 *
 * <p>为什么每次都要留痕：链上交易是权威记录，但排查业务问题时还需要知道"是哪台服务、
 * 哪个机构、什么参数"发起的，因此链下再记一份操作日志；事件索引则用于列表与报表查询
 * （合约刻意不提供遍历接口，名单类查询按区块链惯例由事件驱动）。
 */
@Service
public class ChainTxService {

    private static final Logger log = LoggerFactory.getLogger(ChainTxService.class);

    private final InstitutionKeyService institutionKeyService;
    private final ChainEventParser eventParser;
    private final ChainTxLogMapper txLogMapper;
    private final ChainEventMapper eventMapper;
    private final ObjectMapper objectMapper;
    private final ChainGateway chainGateway;

    public ChainTxService(InstitutionKeyService institutionKeyService,
                          ChainEventParser eventParser,
                          ChainTxLogMapper txLogMapper,
                          ChainEventMapper eventMapper,
                          ObjectMapper objectMapper,
                          ChainGateway chainGateway) {
        this.institutionKeyService = institutionKeyService;
        this.eventParser = eventParser;
        this.txLogMapper = txLogMapper;
        this.eventMapper = eventMapper;
        this.objectMapper = objectMapper;
        this.chainGateway = chainGateway;
    }

    /** 用指定机构的私钥发交易。交易回滚时抛业务异常（附带 txHash 便于在链上浏览器核对）。 */
    public SendResult send(String institutionCode, String contractName, String functionName, List<Object> params) {
        ChainAccount account = institutionKeyService.resolveChainAccount(institutionCode);
        List<Object> normalized = ChainParams.normalizeAll(params);

        SendResult result;
        try {
            result = chainGateway.send(account, contractName, functionName, normalized);
        } catch (BizException e) {
            writeTxLog(account, contractName, functionName, normalized, null, false, e.getMessage());
            throw e;
        }

        writeTxLog(account, contractName, functionName, normalized, result, result.isSuccess(), result.getMessage());
        indexEvents(contractName, functionName, result);

        if (!result.isSuccess()) {
            // 给用户的只有一句中文人话；错误名与 txHash 只留在日志与 cs_chain_tx_log.message（上面已写入原文）
            throw new BizException(ErrorCode.CHAIN_RECEIPT_FAILED,
                    ContractErrorMessages.friendly(result.getMessage()));
        }
        return result;
    }

    /** 便捷重载：参数直接顺序传入。 */
    public SendResult send(String institutionCode, String contractName, String functionName, Object... params) {
        return send(institutionCode, contractName, functionName, ChainParams.of(params));
    }

    /**
     * <b>显式指定签名身份</b>发交易。
     *
     * <p>与 {@link #send(String, String, String, List)} 同义，但把"用谁的私钥"抬到方法名上，
     * 让调用点自解释、避免误用"当前登录账号"的身份。典型场景：社保局创建缴费单位账号后，
     * 由后端代签 {@code addSponsorAccount} 完成"创建即上链"——合约里该函数带 {@code onlyAdmin}，
     * 必须 {@code sendAs(AuthService.ROLE_ADMIN, ...)}，用发起创建的社保局私钥签名必然被回滚。
     */
    public SendResult sendAs(String institutionCode, String contractName, String functionName, Object... params) {
        return send(institutionCode, contractName, functionName, params);
    }

    /** 只读调用（参数同样做类型归一化）。 */
    public com.chainsip.chain.dto.CallResult call(String contractName, String functionName, List<Object> params) {
        // 2.x 的关键点：合约里的可见性判断（例如 getInsuranceAccount 只对本人/所属 SPONSOR/ADMIN/REGULATOR 开放）
        // 依赖 msg.sender，而 msg.sender 来自"这次调用用哪把私钥"。3.x 时代客户端自带默认身份所以能过；
        // 2.x 如果不显式指定身份，节点看到的就是陌生地址，于是返回 AccountNotVisible。
        // 这里让读调用也带上当前登录账号自己的身份；没有机构身份的账号（历史数据里的职工）回退到 ADMIN
        // （合约允许 ADMIN 读取，链下仍由 HTTP 层的角色门禁把关）。
        //
        // 职工账号现在也有一把自己的链上密钥（EMP_ 开头），因此读调用同样用职工自己的私钥签名。
        // 这是实测确认过的行为，不是想当然：职工本人读自己的参保账户可见（msg.sender == employee 命中
        // _requireAccountReader 的第一条），而职工读另一个职工的账户会被合约回滚 AccountNotVisible
        // （证明没有偷偷回退成 ADMIN，否则 ADMIN 能读所有人）。
        ChainAccount account = readAccount();
        return chainGateway.call(account, contractName, functionName, ChainParams.normalizeAll(params));
    }

    /**
     * <b>显式指定签名身份</b>的只读调用。
     *
     * <p>与 {@link #call(String, String, List)} 同义，只是把"这次 read 用谁的私钥"抬到方法名上。
     * 需要它的原因：有些 view 函数除了按 {@code msg.sender} 判可见性之外，调用者身份还会直接决定
     * "能不能读到"，典型场景是社保局给下辖缴费单位注资前要读该单位的
     * {@code getSponsorAccount} —— 合约只允许「单位自己 / ADMIN / REGULATOR」读，
     * BUREAU 读会被 {@code SponsorNotVisible} 回滚；这时必须用 ADMIN 身份读，
     * 与随后代签 {@code depositSponsor} 的身份保持一致。
     */
    public com.chainsip.chain.dto.CallResult callAs(String institutionCode, String contractName,
                                                    String functionName, Object... params) {
        ChainAccount account = institutionKeyService.resolveChainAccount(institutionCode);
        return chainGateway.call(account, contractName, functionName, ChainParams.normalizeAll(ChainParams.of(params)));
    }

    /** 读调用使用的身份：优先当前账号自己的链账户（含职工），没有可用私钥时回退 ADMIN。 */
    private ChainAccount readAccount() {
        try {
            String code = currentInstitutionCode();
            if (code != null && !code.trim().isEmpty()) {
                return institutionKeyService.resolveChainAccount(code);
            }
        } catch (RuntimeException ignored) {
            // 当前账号没有可用机构私钥时走下面的 ADMIN 回退
        }
        return institutionKeyService.resolveChainAccount("ADMIN");
    }

    public com.chainsip.chain.dto.CallResult call(String contractName, String functionName, Object... params) {
        return call(contractName, functionName, ChainParams.of(params));
    }

    private void writeTxLog(ChainAccount account, String contractName, String functionName,
                            List<Object> params, SendResult result, boolean success, String message) {
        try {
            ChainTxLog entity = new ChainTxLog();
            entity.setInstitutionCode(account.getInstitutionCode());
            entity.setSenderAddress(account.getAddress());
            entity.setContractName(contractName);
            entity.setFunctionName(functionName);
            entity.setParamsJson(toJson(params));
            entity.setSuccess(success);
            entity.setMessage(truncate(message, 500));
            if (result != null) {
                entity.setTxHash(result.getTransactionHash());
                entity.setBlockNumber(result.getBlockNumber());
                entity.setStatus(result.getStatus());
            }
            entity.setCreatedAt(LocalDateTime.now());
            txLogMapper.insert(entity);
            if (success) {
                log.info("上链成功：{}#{} by {} tx={}", contractName, functionName,
                        account.getInstitutionCode(), entity.getTxHash());
            } else {
                log.warn("上链失败：{}#{} by {} 原因={}", contractName, functionName,
                        account.getInstitutionCode(), message);
            }
        } catch (Exception e) {
            log.error("写链上操作日志失败（不影响业务结果）", e);
        }
    }

    private void indexEvents(String contractName, String functionName, SendResult result) {
        if (result == null || result.getEventsJson() == null) {
            return;
        }
        try {
            for (ChainEventParser.ParsedEvent parsed : eventParser.parse(result.getEventsJson(), result.getLogsJson())) {
                ChainEvent entity = new ChainEvent();
                entity.setTxHash(result.getTransactionHash());
                entity.setBlockNumber(result.getBlockNumber());
                entity.setContractName(contractName);
                entity.setFunctionName(functionName);
                entity.setEventName(parsed.eventName);
                entity.setEmployeeAddress(parsed.employeeAddress);
                entity.setSponsorAddress(parsed.sponsorAddress);
                entity.setAmount(toDecimal(parsed.amount));
                entity.setPersonalAmount(toDecimal(parsed.personalAmount));
                entity.setOverallAmount(toDecimal(parsed.overallAmount));
                entity.setPayload(truncate(parsed.payload, 4000));
                entity.setCreatedAt(LocalDateTime.now());
                eventMapper.insert(entity);
            }
        } catch (Exception e) {
            log.error("事件索引写入失败（不影响业务结果）", e);
        }
    }

    public boolean isReady() {
        return chainGateway.isReady();
    }

    /**
     * 当前登录账号对应的链账户编码（发交易/读链用谁的私钥）。
     *
     * <p>这是"用谁的私钥发交易"的答案：ADMIN 账号用 ADMIN 私钥，某社保局账号用该社保局私钥，
     * 这样链上 msg.sender 才是该机构自己的地址，合约的角色判权才会按预期生效。
     *
     * <p><b>职工账号（EMPLOYEE）也一样</b>：开号时会自动生成一把 {@code EMP_<6位随机大写十六进制>}
     * 的链上密钥，因此职工用<b>自己的</b>私钥签名。契约上这是必须的——转移合约
     * {@code applyForTransfer} 用 {@code msg.sender} 当职工地址，若回退成 ADMIN 签名，
     * 合约会因为该地址不是参保职工而回滚。已实测：单位用职工自己的链上地址建档后，
     * 职工可正常查自己的账户并成功发起转移申请（cs_chain_tx_log 里 sender = EMP 地址）。
     */
    public String currentInstitutionCode() {
        com.chainsip.security.AuthUser user = com.chainsip.security.AuthContext.require();
        String code = user.getInstitutionCode();
        if (code == null || code.trim().isEmpty()) {
            throw new BizException(ErrorCode.INSTITUTION_KEY_NOT_FOUND,
                    "当前账号未关联机构链账户（institutionCode），无法发起链上交易");
        }
        return code.trim();
    }

    public String statusMessage() {
        return chainGateway.statusMessage();
    }

    public boolean refresh() {
        return chainGateway.refresh();
    }

    /** 最新区块信息（区块高度 / 区块哈希 / 最新交易哈希）。 */
    public com.chainsip.chain.dto.BlockInfo latestBlock() {
        return chainGateway.latestBlock();
    }

    public String nodeVersion() {
        return chainGateway.nodeVersion();
    }

    /** 共识拓扑（sealers / observers / 全部已入组节点）。 */
    public java.util.Map<String, Object> topology() {
        return chainGateway.topology();
    }

    private String toJson(List<Object> params) {
        try {
            return truncate(objectMapper.writeValueAsString(params), 4000);
        } catch (Exception e) {
            return String.valueOf(params);
        }
    }

    private BigDecimal toDecimal(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
