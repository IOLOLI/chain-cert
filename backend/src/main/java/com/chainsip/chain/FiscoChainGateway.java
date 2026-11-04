package com.chainsip.chain;

import com.chainsip.chain.dto.BlockInfo;
import com.chainsip.chain.dto.CallResult;
import com.chainsip.chain.dto.ChainAccount;
import com.chainsip.chain.dto.SendResult;
import com.chainsip.common.BizException;
import com.chainsip.common.ErrorCode;
import com.chainsip.config.ChainProperties;
import com.chainsip.crypto.WdprNativeLibraryBootstrap;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.fisco.bcos.sdk.BcosSDK;
import org.fisco.bcos.sdk.abi.ABICodecException;
import org.fisco.bcos.sdk.client.Client;
import org.fisco.bcos.sdk.client.protocol.response.BcosBlock;
import org.fisco.bcos.sdk.client.protocol.response.BcosTransaction;
import org.fisco.bcos.sdk.client.protocol.response.BcosTransactionReceipt;
import org.fisco.bcos.sdk.client.protocol.response.TotalTransactionCount;
import org.fisco.bcos.sdk.client.protocol.model.JsonTransactionResponse;
import org.fisco.bcos.sdk.crypto.keypair.CryptoKeyPair;
import org.fisco.bcos.sdk.model.TransactionReceipt;
import org.fisco.bcos.sdk.transaction.manager.AssembleTransactionProcessor;
import org.fisco.bcos.sdk.transaction.manager.TransactionProcessorFactory;
import org.fisco.bcos.sdk.transaction.model.dto.CallResponse;
import org.fisco.bcos.sdk.transaction.model.dto.TransactionResponse;
import org.fisco.bcos.sdk.transaction.model.exception.TransactionBaseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FISCO BCOS 2.x Java SDK 实现（SDK 2.10.1 → 链 2.11.0）。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>懒初始化</b>：链没起来时应用照样能启动（认证、账号、地址绑定都不依赖链），
 *       链相关接口会返回 503 与明确的排查提示，避免"链没起来后端就起不来"的死结；</li>
 *   <li><b>按机构缓存交易处理器</b>：每个机构私钥对应一个 {@link AssembleTransactionProcessor}，
 *       这样链上 msg.sender 才是该机构自己的地址，合约的角色判权才能生效；</li>
 *   <li><b>只读调用用随机密钥</b>：view 调用不签名，用一次性的随机密钥构造处理器即可，
 *       不让读操作牵扯机构私钥。</li>
 * </ul>
 *
 * <h2>SDK 3.x → 2.x 的 API 替换点（均以 javap 核对过 SDK 2.10.1 的真实签名）</h2>
 * <table border="1">
 *   <tr><th>用途</th><th>3.x</th><th>2.x</th></tr>
 *   <tr><td>包名</td><td>{@code org.fisco.bcos.sdk.v3.*}</td><td>{@code org.fisco.bcos.sdk.*}（去掉 .v3）</td></tr>
 *   <tr><td>取 Client</td><td>{@code sdk.getClient()}</td><td>{@code sdk.getClient(int groupId)}</td></tr>
 *   <tr><td>编码异常</td><td>{@code codec.ContractCodecException}</td><td>{@code abi.ABICodecException}</td></tr>
 *   <tr><td>取区块</td><td>{@code getBlockByNumber(n, true, false)}</td><td>{@code getBlockByNumber(n, true)}（少一个参数）</td></tr>
 *   <tr><td>区块内交易</td><td>{@code block.getTransactionHashes()}</td><td>{@code block.getTransactions()} → 元素 {@code TransactionHash}</td></tr>
 *   <tr><td>回执状态</td><td>{@code receipt.getStatus() == 0}</td><td>{@code receipt.getStatus()} 是十六进制字符串，用 {@code isStatusOK()}</td></tr>
 *   <tr><td>回执区块号</td><td>{@code receipt.getBlockNumber()} 返回 BigInteger</td><td>{@code getBlockNumber()} 返回 String</td></tr>
 *   <tr><td>时间戳</td><td>{@code getTimestamp()} 返回 long</td><td>{@code getTimestamp()} 返回 String</td></tr>
 *   <tr><td>随机密钥</td><td>{@code generateRandomKeyPair()}</td><td>{@code createKeyPair()}</td></tr>
 *   <tr><td>载入密钥</td><td>{@code loadKeyPair(hex)}</td><td>{@code createKeyPair(hex)}</td></tr>
 * </table>
 */
@Component
public class FiscoChainGateway implements ChainGateway {

    private static final Logger log = LoggerFactory.getLogger(FiscoChainGateway.class);
    private static final String SDK_VERSION = "fisco-bcos-java-sdk 2.10.1";

    private final ChainProperties properties;
    private final AbiResourceExporter abiResourceExporter;
    private final ObjectMapper objectMapper;

    private final Object initLock = new Object();
    private final Map<String, AssembleTransactionProcessor> processors = new ConcurrentHashMap<>();

    private volatile boolean initialized;
    private volatile String initError;
    private volatile BcosSDK sdk;
    private volatile Client client;
    private volatile String abiDir;
    private volatile String binDir;
    private volatile AssembleTransactionProcessor readProcessor;

    public FiscoChainGateway(ChainProperties properties, AbiResourceExporter abiResourceExporter,
                             ObjectMapper objectMapper) {
        this.properties = properties;
        this.abiResourceExporter = abiResourceExporter;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        synchronized (initLock) {
            if (initialized) {
                return;
            }
            if (!properties.isEnabled()) {
                initError = "链交互已在配置中关闭（chainsip.chain.enabled=false）";
                return;
            }
            try {
                // SDK 2.x 的 ECDSA 走 WeDPR JNI 原生库；先确保它能被加载，
                // 否则 BcosSDK.build 会在证书之前就先崩在 UnsatisfiedLinkError 上。
                WdprNativeLibraryBootstrap.provision();

                Path configFile = Paths.get(properties.getSdkConfigFile()).toAbsolutePath().normalize();
                if (!Files.exists(configFile)) {
                    initError = "SDK 配置文件不存在：" + configFile
                            + "（把节点 build_chain 生成的 sdk 目录放到该路径，并确认证书内容）";
                    return;
                }

                // 先做一次证书体检：SDK 在解析配置阶段就会校验证书路径，
                // 证书缺失时它只抛 "parse Config ... failed"，很容易被误判成配置文件格式错误。
                String certProblem = checkCertificates(configFile);
                if (certProblem != null) {
                    initError = certProblem;
                    return;
                }

                Path[] dirs = abiResourceExporter.export();
                this.abiDir = dirs[0].toString();
                this.binDir = dirs[1].toString();

                // 2.x 与 3.x 的另一个行为差异：SDK 2.x 把 certPath 之类的相对路径
                // 按"进程工作目录"解析，而不是按 config.toml 所在目录解析
                // （3.x 是按配置文件目录解析的）。这样只要不是刚好在 backend/ 下启动，
                // 就会报 "Missed certificates: [conf\ca.crt,...] currentPath: <cwd>"。
                // 因此这里先把配置物化到临时文件、把证书路径改成绝对路径，再交给 SDK。
                Path effectiveConfig = materializeConfig(configFile);

                // 两个针对 2.x SDK 的防御（都有实证，见下）：
                //  ① SDK 的 ConfigProperty 在"从磁盘读取某个配置项失败"时，会退化成
                //     getResource("") 去取资源根路径。若此时线程上下文类加载器是基于 jar 的
                //     （Spring Boot fat jar + Tomcat 请求线程正是如此），JDK 的 JarLoader 会对
                //     空资源名抛 IllegalArgumentException: name，最终被包成完全没有信息量的
                //     "parse Config ... failed, error info:"。而"读磁盘失败"的常见原因就是
                //     keyStoreDir 指向的目录不存在 —— 所以这里先把它建出来。
                //  ② 万一还有别的项走退化路径，把上下文类加载器临时换成"基于目录"的类加载器，
                //     让 getResource("") 安全返回目录 URL，而不是抛异常。
                ensureAccountDir(configFile);
                ClassLoader originalCl = Thread.currentThread().getContextClassLoader();
                ClassLoader safeCl = directoryClassLoader(configFile);
                Thread.currentThread().setContextClassLoader(safeCl);
                BcosSDK newSdk;
                try {
                    newSdk = BcosSDK.build(effectiveConfig.toString());
                } finally {
                    Thread.currentThread().setContextClassLoader(originalCl);
                }
                // 2.x 的关键差异：群组号不在 config.toml 里，而是 getClient(groupId) 的入参。
                Client newClient = newSdk.getClient(properties.getGroupId());
                this.sdk = newSdk;
                this.client = newClient;
                this.processors.clear();
                this.readProcessor = null;
                this.initialized = true;
                this.initError = null;
                log.info("FISCO BCOS 2.x SDK 初始化完成：groupId={}, 配置={}（实际使用 {}）, abi={}",
                        properties.getGroupId(), configFile, effectiveConfig, this.abiDir);
            } catch (Throwable t) {
                // 注意：2.x SDK 常把底层异常包成一句没有信息量的消息
                // （例如 "parse Config xxx failed, error info: " 后面是空的），
                // 所以这里既打完整堆栈，也把"根因链"拼进对外暴露的状态信息里，便于现场定位。
                this.initError = "SDK 初始化失败：" + describe(t);
                log.warn("FISCO BCOS SDK 初始化失败，链相关接口将返回 503：{}", describe(t), t);
            }
        }
    }

    @Override
    public boolean isReady() {
        ensureInitialized();
        return initialized;
    }

    /**
     * 建出 SDK 配置里 {@code keyStoreDir} 指向的目录。
     *
     * <p>该目录不存在时，2.x 的 {@code ConfigProperty} 会走上"读磁盘失败 → getResource("")"
     * 的退化路径，在基于 jar 的类加载器下直接抛 {@code IllegalArgumentException: name}。
     */
    private static void ensureAccountDir(Path configFile) {
        try {
            Path parent = configFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent.resolve("account"));
            }
        } catch (Exception ignored) {
            // 建不出来不致命：还有 directoryClassLoader 兜底
        }
    }

    /**
     * 构造一个"基于目录"的类加载器，用作初始化期间的线程上下文类加载器。
     *
     * <p>目的：让 2.x SDK 内部的 {@code ClassLoader.getResource("")} 安全返回目录 URL。
     * 在 Spring Boot fat jar 下，默认的上下文类加载器是基于 jar 的，JDK 的 JarLoader 会对
     * 空资源名抛 {@code IllegalArgumentException: name}。
     */
    private static ClassLoader directoryClassLoader(Path configFile) {
        try {
            Path parent = configFile.toAbsolutePath().getParent();
            if (parent != null) {
                return new java.net.URLClassLoader(new java.net.URL[]{parent.toUri().toURL()},
                        FiscoChainGateway.class.getClassLoader());
            }
        } catch (Exception ignored) {
            // 退回应用类加载器
        }
        return FiscoChainGateway.class.getClassLoader();
    }
    /**
     * 把异常及其"根因链"拼成一行可读文本。
     *
     * <p>FISCO 2.x SDK 的包装异常经常只有一句没有信息量的消息（cause 为 null 或消息为空），
     * 直接展示 {@code t.getMessage()} 会导致现场只能看到 "parse Config ... failed, error info: "。
     */
    private static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (sb.length() > 0) {
                sb.append(" <- ");
            }
            String msg = cur.getMessage();
            sb.append(cur.getClass().getSimpleName());
            if (msg != null && !msg.trim().isEmpty()) {
                sb.append(": ").append(msg.trim());
            }
            if (cur.getCause() == cur) {
                break;
            }
        }
        return sb.toString();
    }

    @Override
    public String statusMessage() {
        ensureInitialized();
        if (initialized) {
            return "就绪（" + SDK_VERSION + "，group=" + groupId() + "）";
        }
        return initError == null ? "未初始化" : initError;
    }

    @Override
    public synchronized boolean refresh() {
        closeQuietly();
        initialized = false;
        initError = null;
        sdk = null;
        client = null;
        processors.clear();
        readProcessor = null;
        ensureInitialized();
        return initialized;
    }

    private void closeQuietly() {
        try {
            if (sdk != null) {
                sdk.stopAll();
            }
        } catch (Throwable ignore) {
            // 关闭失败不影响重新初始化
        }
    }

    // ------------------------------------------------------------------
    // 只读调用
    // ------------------------------------------------------------------

    @Override
    public CallResult call(ChainAccount account, String contractName, String functionName, List<Object> params) {
        if (account == null) {
            return call(contractName, functionName, params);
        }
        requireClient();
        String address = requireAddress(contractName);
        // 用调用者所属机构的私钥发这次 read 调用：合约里的可见性判断（msg.sender）才能认出来人。
        AssembleTransactionProcessor processor = processorFor(account);
        try {
            CallResponse response = processor.sendCallByContractLoader(
                    contractName, address, functionName, params == null ? new ArrayList<>() : params);
            return new CallResult(response.getReturnObject(), response.getValues(), null);
        } catch (ABICodecException e) {
            log.warn("链上只读调用 ABI 编解码失败：{}#{} → {}", contractName, functionName, e.getMessage());
            throw new BizException(ErrorCode.CHAIN_CALL_ERROR,
                    "ABI 编码/解码失败：" + contractName + "#" + functionName + " — " + e.getMessage()
                            + "（检查参数类型与合约地址是否匹配）", e);
        } catch (TransactionBaseException e) {
            throw callFailure(contractName, functionName, params, e);
        } catch (RuntimeException e) {
            log.warn("链上只读调用异常：{}#{} → {}", contractName, functionName, e.getMessage());
            throw new BizException(ErrorCode.CHAIN_CALL_ERROR,
                    "链上调用异常：" + contractName + "#" + functionName + " — " + e.getMessage(), e);
        }
    }

    @Override
    public CallResult call(String contractName, String functionName, List<Object> params) {
        Client current = requireClient();
        String address = requireAddress(contractName);
        AssembleTransactionProcessor processor = readProcessor();
        try {
            CallResponse response = processor.sendCallByContractLoader(
                    contractName, address, functionName, params == null ? new ArrayList<>() : params);
            return new CallResult(response.getReturnObject(), response.getValues(), null);
        } catch (ABICodecException e) {
            log.warn("链上只读调用 ABI 编解码失败：{}#{} → {}", contractName, functionName, e.getMessage());
            throw new BizException(ErrorCode.CHAIN_CALL_ERROR,
                    "ABI 编码/解码失败：" + contractName + "#" + functionName + " — " + e.getMessage()
                            + "（检查参数类型与合约地址是否匹配）", e);
        } catch (TransactionBaseException e) {
            throw callFailure(contractName, functionName, params, e);
        } catch (RuntimeException e) {
            log.warn("链上只读调用异常：{}#{} → {}", contractName, functionName, e.getMessage());
            throw new BizException(ErrorCode.CHAIN_CALL_ERROR,
                    "链上调用异常：" + contractName + "#" + functionName + " — " + e.getMessage(), e);
        } finally {
            if (current == null) {
                log.debug("client 尚未就绪");
            }
        }
    }

    /**
     * 只读调用失败的统一出口：把「合约#函数」写进 message 与日志。
     *
     * <p>没有这两项时，前端只能看到一句 {@code code=2002 链上调用失败：Revert instruction}，
     * 既不知道是哪个方法、也不知道往哪查——这几乎是排查时最贵的成本。
     */
    private BizException callFailure(String contractName, String functionName, List<Object> params, Exception e) {
        String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        log.warn("链上只读调用失败：{}#{}({}) → {}", contractName, functionName, params, detail);
        return new BizException(ErrorCode.CHAIN_CALL_ERROR,
                "链上调用失败：" + contractName + "#" + functionName + " — " + detail + revertHint(detail), e);
    }

    /**
     * Revert 的两种常见成因，按概率排序给出可执行的下一步。
     *
     * <ol>
     *   <li><b>链上合约版本落后于后端 ABI</b>（最常见）：后端 ABI 已编译进新函数，
     *       但链上还是升级前部署的那一份，EVM 找不到 selector 直接回滚。改了合约就必须重新部署，
     *       光重启后端不会让链上的字节码变新。</li>
     *   <li><b>当前账号在链上没有读取权限</b>：合约的 view 大量按 {@code msg.sender} 判权，
     *       用错私钥签这次 read 就会回滚。</li>
     * </ol>
     */
    private String revertHint(String detail) {
        if (detail != null && detail.contains("Revert")) {
            return "（合约执行被回滚。优先核对链上合约版本：若最近改过合约，链上仍是旧部署的那一份，"
                    + "新增函数在旧合约上不存在，必须重新部署核心合约；若刚部署过，"
                    + "则可能是当前账号在链上没有该函数的读取权限）";
        }
        return "";
    }

    // ------------------------------------------------------------------
    // 发交易
    // ------------------------------------------------------------------

    @Override
    public SendResult send(ChainAccount account, String contractName, String functionName, List<Object> params) {
        requireClient();
        String address = requireAddress(contractName);
        AssembleTransactionProcessor processor = processorFor(account);
        List<Object> args = params == null ? new ArrayList<>() : params;

        try {
            TransactionResponse response = processor.sendTransactionAndGetResponseByContractLoader(
                    contractName, address, functionName, args);
            TransactionReceipt receipt = response.getTransactionReceipt();
            if (receipt == null) {
                return new SendResult(false, null, null, -1, response.getReturnObject(), response.getEvents(),
                        null,
                        joinMessage(response.getReceiptMessages(), "交易未上链（编码或发送阶段失败）"));
            }
            // 2.x 的回执 status 是十六进制字符串（"0x0" 成功），不是 3.x 的 int。
            boolean success = receipt.isStatusOK();
            String message = success
                    ? "成功"
                    : joinMessage(response.getReceiptMessages(), describeRevert(receipt));
            return new SendResult(
                    success,
                    receipt.getTransactionHash(),
                    parseLong(receipt.getBlockNumber()),
                    parseStatus(receipt.getStatus()),
                    response.getReturnObject(),
                    response.getEvents(),
                    serializeLogs(receipt),
                    message);
        } catch (ABICodecException e) {
            log.warn("链上发交易 ABI 编码失败：{}#{} → {}", contractName, functionName, e.getMessage());
            throw new BizException(ErrorCode.CHAIN_CALL_ERROR,
                    "ABI 编码失败：" + contractName + "#" + functionName + " — " + e.getMessage()
                            + "（检查参数类型与合约地址）", e);
        } catch (TransactionBaseException e) {
            log.warn("链上发交易失败：{}#{}({}) → {}", contractName, functionName, args, e.getMessage());
            throw new BizException(ErrorCode.CHAIN_CALL_ERROR,
                    "交易失败：" + contractName + "#" + functionName + " — " + e.getMessage() + revertHint(e.getMessage()), e);
        } catch (RuntimeException e) {
            log.warn("链上发交易异常：{}#{}({}) → {}", contractName, functionName, args, e.getMessage());
            throw new BizException(ErrorCode.CHAIN_CALL_ERROR,
                    "交易异常：" + contractName + "#" + functionName + " — " + e.getMessage(), e);
        }
    }

    private String describeRevert(TransactionReceipt receipt) {
        String output = receipt.getOutput();
        if (output == null || output.isEmpty() || "0x".equals(output)) {
            return "合约执行回滚，状态码 " + receipt.getStatus()
                    + (receipt.getStatusMsg() == null ? "" : "（" + receipt.getStatusMsg() + "）")
                    + "（无返回数据）";
        }
        String selector = output.length() >= 10 ? output.substring(0, 10) : output;
        return "合约执行回滚，状态码 " + receipt.getStatus() + "，自定义错误选择器 " + selector
                + "（可用合约 ABI 的 error 定义对照解析）";
    }

    // ------------------------------------------------------------------
    // 区块信息
    // ------------------------------------------------------------------

    @Override
    public BlockInfo latestBlock() {
        Client current = requireClient();
        BigInteger number = current.getBlockNumber().getBlockNumber();
        // 2.x 的 getBlockByNumber 只有 (number, returnFullTx) 两个参数（3.x 还有一个
        // returnFullTx 之外的扩展参数）。
        // 这里刻意传 false：javap 反编译 BcosBlock$TransactionResultDeserialiser 可见，
        // 节点返回字符串数组时元素会被反序列化成 BcosBlock$TransactionHash，
        // 返回对象数组时才变成 TransactionObject —— 传 false 能确保拿到的是交易哈希，
        // 正是这里需要的（与 3.x getTransactionHashes() 语义一致）。
        BcosBlock bcosBlock = current.getBlockByNumber(number, false);
        BcosBlock.Block block = bcosBlock == null ? null : bcosBlock.getBlock();
        if (block == null) {
            return new BlockInfo(number.toString(), null, null, 0, null, groupId(), SDK_VERSION);
        }
        // 2.x 把 getTransactions() 声明成了裸类型 List（没有泛型参数），所以这里只能接裸 List，
        // 不能用 List<TransactionResult<?>>（泛型不变，裸类型转不过去）。
        @SuppressWarnings("rawtypes")
        List transactions = block.getTransactions();
        String latestTx = null;
        if (transactions != null && !transactions.isEmpty()) {
            Object last = transactions.get(transactions.size() - 1);
            if (last instanceof BcosBlock.TransactionHash) {
                latestTx = ((BcosBlock.TransactionHash) last).get();
            } else if (last != null) {
                // 兜底：万一节点返回了完整交易对象，仍能取到一个可展示的值
                latestTx = String.valueOf(last);
            }
        }
        return new BlockInfo(
                String.valueOf(block.getNumber()),
                block.getHash(),
                latestTx,
                transactions == null ? 0 : transactions.size(),
                // 2.x 的 getTimestamp() 已经是 String，不需要再 String.valueOf
                block.getTimestamp(),
                groupId(),
                SDK_VERSION);
    }

    // ------------------------------------------------------------------
    // 区块链浏览器（只读）
    // ------------------------------------------------------------------

    @Override
    public Map<String, Object> chainOverview() {
        Client current = requireClient();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("groupId", groupId());
        out.put("nodeVersion", nodeVersion());
        out.put("coreContract", properties.getContracts().getCore());
        out.put("transferContract", properties.getContracts().getTransfer());
        try {
            out.put("blockNumber", current.getBlockNumber().getBlockNumber().toString());
        } catch (RuntimeException e) {
            out.put("blockNumber", null);
        }
        try {
            TotalTransactionCount count = current.getTotalTransactionCount();
            if (count != null && count.getTotalTransactionCount() != null) {
                out.put("txSum", hexToDecimal(count.getTotalTransactionCount().getTxSum()));
                out.put("failedTxSum", hexToDecimal(count.getTotalTransactionCount().getFailedTxSum()));
                out.put("countBlockNumber", hexToDecimal(count.getTotalTransactionCount().getBlockNumber()));
            }
        } catch (RuntimeException e) {
            out.put("txSum", null);
            out.put("failedTxSum", null);
        }
        return out;
    }

    @Override
    public Map<String, Object> blockByNumber(long number, boolean withTransactions) {
        Client current = requireClient();
        BcosBlock response = current.getBlockByNumber(BigInteger.valueOf(number), withTransactions);
        return blockView(response == null ? null : response.getBlock(), withTransactions);
    }

    @Override
    public Map<String, Object> blockByHash(String hash, boolean withTransactions) {
        Client current = requireClient();
        BcosBlock response = current.getBlockByHash(hash, withTransactions);
        return blockView(response == null ? null : response.getBlock(), withTransactions);
    }

    @Override
    public Map<String, Object> transactionByHash(String hash) {
        Client current = requireClient();
        BcosTransaction response = current.getTransactionByHash(hash);
        JsonTransactionResponse tx = response == null ? null : response.getTransaction().orElse(null);
        if (tx == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "链上不存在该交易：" + hash);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("hash", tx.getHash());
        out.put("blockNumber", tx.getBlockNumber() == null ? null : tx.getBlockNumber().toString());
        out.put("blockHash", tx.getBlockHash());
        out.put("transactionIndex", tx.getTransactionIndex());
        out.put("from", tx.getFrom());
        out.put("to", tx.getTo());
        out.put("value", tx.getValue());
        out.put("gas", tx.getGas());
        out.put("gasPrice", tx.getGasPrice());
        out.put("nonce", tx.getNonce());
        out.put("input", tx.getInput());
        try {
            BcosTransactionReceipt receiptResponse = current.getTransactionReceipt(hash);
            TransactionReceipt receipt = receiptResponse == null
                    ? null : receiptResponse.getTransactionReceipt().orElse(null);
            if (receipt != null) {
                out.put("status", receipt.getStatus());
                out.put("success", receipt.isStatusOK());
                out.put("gasUsed", receipt.getGasUsed());
                out.put("output", receipt.getOutput());
                out.put("contractAddress", receipt.getContractAddress());
                out.put("logs", logsView(receipt.getLogs()));
            }
        } catch (RuntimeException e) {
            out.put("receiptError", e.getMessage());
        }
        return out;
    }

    private Map<String, Object> blockView(BcosBlock.Block block, boolean withTransactions) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (block == null) {
            return out;
        }
        out.put("number", block.getNumber() == null ? null : block.getNumber().toString());
        out.put("hash", block.getHash());
        out.put("parentHash", block.getParentHash());
        out.put("sealer", block.getSealer());
        out.put("sealerList", block.getSealerList());
        out.put("gasUsed", block.getGasUsed());
        out.put("gasLimit", block.getGasLimit());
        out.put("stateRoot", block.getStateRoot());
        out.put("receiptsRoot", block.getReceiptsRoot());
        out.put("transactionsRoot", block.getTransactionsRoot());
        out.put("dbHash", block.getDbHash());
        out.put("timestamp", block.getTimestamp());
        out.put("timestampMillis", toMillis(block.getTimestamp()));

        @SuppressWarnings("rawtypes")
        List transactions = block.getTransactions();
        List<Object> list = new ArrayList<>();
        if (transactions != null) {
            for (Object item : transactions) {
                if (item instanceof BcosBlock.TransactionObject) {
                    JsonTransactionResponse tx = ((BcosBlock.TransactionObject) item).get();
                    list.add(withTransactions ? txBrief(tx) : tx.getHash());
                } else if (item instanceof BcosBlock.TransactionHash) {
                    list.add(((BcosBlock.TransactionHash) item).get());
                } else if (item != null) {
                    list.add(String.valueOf(item));
                }
            }
        }
        out.put("txCount", list.size());
        out.put("transactions", list);
        return out;
    }

    private Map<String, Object> txBrief(JsonTransactionResponse tx) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("hash", tx.getHash());
        out.put("from", tx.getFrom());
        out.put("to", tx.getTo());
        out.put("value", tx.getValue());
        out.put("input", tx.getInput());
        out.put("transactionIndex", tx.getTransactionIndex());
        return out;
    }

    private List<Map<String, Object>> logsView(List<TransactionReceipt.Logs> logs) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (logs == null) {
            return out;
        }
        for (TransactionReceipt.Logs log : logs) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("address", log.getAddress());
            item.put("topics", log.getTopics());
            item.put("data", log.getData());
            out.add(item);
        }
        return out;
    }

    /** 节点返回的计数是十六进制（如 0x51），转成十进制字符串给前端。 */
    private static String hexToDecimal(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String text = value.trim();
        try {
            return new BigInteger(text.startsWith("0x") || text.startsWith("0X") ? text.substring(2) : text, 16)
                    .toString();
        } catch (NumberFormatException e) {
            return text;
        }
    }

    /** 区块时间戳（秒）→ 毫秒；解析失败返回 null。 */
    private static Long toMillis(String timestamp) {
        if (timestamp == null || timestamp.trim().isEmpty()) {
            return null;
        }
        try {
            long value = Long.parseLong(timestamp.trim());
            return value > 1_000_000_000_000L ? value : value * 1000L;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public String nodeVersion() {
        return isReady() ? SDK_VERSION + " / group=" + groupId() : "未就绪";
    }

    @Override
    public Map<String, Object> topology() {
        Client current = requireClient();
        List<String> sealers = new ArrayList<>();
        List<String> observers = new ArrayList<>();
        List<String> nodeIds = new ArrayList<>();
        try {
            sealers.addAll(current.getSealerList().getResult());
        } catch (Exception ignored) {
            // 老版本 / 权限受限时该查询可能不可用，下面用空表顺着返回，不阻塞页面
        }
        try {
            observers.addAll(current.getObserverList().getResult());
        } catch (Exception ignored) {
        }
        try {
            nodeIds.addAll(current.getNodeIDList().getNodeIDList());
        } catch (Exception ignored) {
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("groupId", groupId());
        data.put("sealers", sealers);
        data.put("observers", observers);
        data.put("nodeIds", nodeIds);
        data.put("sealerCount", sealers.size());
        data.put("observerCount", observers.size());
        data.put("nodeCount", nodeIds.size());
        // 观察节点只同步账本、不参与投票；有观察节点时把这句话直接给前端用
        data.put("description", sealers.size() + " 个共识节点 + " + observers.size() + " 个观察节点");
        return data;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private Client requireClient() {
        ensureInitialized();
        if (!initialized || client == null) {
            throw new BizException(ErrorCode.CHAIN_NOT_READY, statusMessage());
        }
        return client;
    }

    private String requireAddress(String contractName) {
        String address = properties.addressOf(contractName);
        if (address == null || address.trim().isEmpty()) {
            throw new BizException(ErrorCode.CONTRACT_NOT_DEPLOYED,
                    contractName + " 合约地址未配置（chainsip.chain.contracts."
                            + (ChainProperties.CORE.equals(contractName) ? "core" : "transfer") + "）");
        }
        return address.trim();
    }

    private AssembleTransactionProcessor readProcessor() {
        AssembleTransactionProcessor cached = readProcessor;
        if (cached != null) {
            return cached;
        }
        synchronized (initLock) {
            if (readProcessor == null) {
                // 3.x: generateRandomKeyPair()；2.x: createKeyPair() 无参重载即随机生成
                CryptoKeyPair randomKeyPair = client.getCryptoSuite().createKeyPair();
                readProcessor = createProcessor(randomKeyPair);
            }
            return readProcessor;
        }
    }

    private AssembleTransactionProcessor processorFor(ChainAccount account) {
        if (account == null || account.getPrivateKeyHex() == null || account.getPrivateKeyHex().isEmpty()) {
            throw new BizException(ErrorCode.INSTITUTION_KEY_NOT_FOUND, "缺少机构链账户私钥，无法签名发交易");
        }
        String key = account.getAddress() == null ? account.getInstitutionCode() : account.getAddress();
        return processors.computeIfAbsent(key, k -> {
            // 3.x: loadKeyPair(hex)；2.x: createKeyPair(hex)。
            // 注意实测差异：2.x 的 createKeyPair(String) 要求"64 位小写十六进制、不带 0x"，
            // 传 "0x..." 会直接 NumberFormatException: For input string: "x1"，
            // 传大写会 For input string: "X1"。3.x 的 loadKeyPair 对 0x 前缀是宽容的，
            // 所以这里必须自己归一化，否则数据库里存了带 0x 的私钥就会在发交易时炸。
            CryptoKeyPair keyPair = client.getCryptoSuite()
                    .createKeyPair(normalizePrivateKey(account.getPrivateKeyHex()));
            log.debug("为机构 [{}] 创建交易处理器，地址={}", account.getInstitutionCode(), keyPair.getAddress());
            return createProcessor(keyPair);
        });
    }

    /** 2.x 的密钥载入要求裸十六进制小写串，这里统一去掉 0x 前缀并转小写。 */
    private String normalizePrivateKey(String privateKeyHex) {
        String s = privateKeyHex.trim();
        if (s.startsWith("0x") || s.startsWith("0X")) {
            s = s.substring(2);
        }
        return s.toLowerCase(java.util.Locale.ROOT);
    }

    private AssembleTransactionProcessor createProcessor(CryptoKeyPair keyPair) {
        try {
            // 4 参与 3.x 的 createAssembleTransactionProcessor(client, keyPair, abiDir, binDir) 同名同参，
            // 内部构造 ContractLoader(abiDir, binDir)。
            //
            // 注意：2.x 还有一个 5 参重载 createAssembleTransactionProcessor(
            //   client, keyPair, abiDir, binDir, accountAddress)，但 javap -c 显示它
            //   * 把 ContractLoader 的构造异常吞掉了，失败时直接 return null，且自身不声明 throws，
            //   * 它的第 5 个参数才是"ABI 文件名里内嵌的签名者地址"。
            // 用 5 参重载会让 ABI 目录有问题时得到一个 null 处理器，然后在业务层炸成难懂的 NPE；
            // 因此这里用 4 参重载，把真实异常抛上去，保留 3.x 原有的一点排查能力。
            return TransactionProcessorFactory.createAssembleTransactionProcessor(
                    client, keyPair, this.abiDir, this.binDir);
        } catch (Exception e) {
            throw new BizException(ErrorCode.CHAIN_NOT_READY, "创建交易处理器失败：" + e.getMessage(), e);
        }
    }

    private String groupId() {
        return String.valueOf(properties.getGroupId());
    }

    /**
     * 证书体检：从 config.toml 里取出 certPath，检查 ca.crt / sdk.crt / sdk.key 是否齐全。
     *
     * <p>为什么值得单独做：证书缺失时 SDK 只报 "parse Config ... failed"，
     * 排查者会以为是 TOML 写错了，实际上九成是证书没放。
     *
     * @return 有问题时返回中文提示，没问题返回 null
     */
    private String checkCertificates(Path configFile) {
        try {
            String content = new String(Files.readAllBytes(configFile), java.nio.charset.StandardCharsets.UTF_8);
            java.util.regex.Matcher matcher =
                    java.util.regex.Pattern.compile("certPath\\s*=\\s*\"([^\"]+)\"").matcher(content);
            if (!matcher.find()) {
                return null; // 没写 certPath 时交给 SDK 用自己的默认规则判断
            }
            Path certDir = configFile.getParent().resolve(matcher.group(1)).normalize();
            if (!Files.isDirectory(certDir)) {
                return "证书目录不存在：" + certDir + "（config.toml 里 certPath 指向该目录）";
            }
            StringBuilder missing = new StringBuilder();
            for (String name : new String[] {"ca.crt", "sdk.crt", "sdk.key"}) {
                if (!Files.exists(certDir.resolve(name))) {
                    missing.append(missing.length() == 0 ? "" : "、").append(name);
                }
            }
            if (missing.length() > 0) {
                return "证书目录 " + certDir + " 下缺少：" + missing
                        + "（把节点 build_chain 生成的 sdk/ca.crt、sdk.crt、sdk.key 拷进去即可）";
            }
            return null;
        } catch (Exception e) {
            log.debug("证书体检跳过：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 把 config.toml 物化成一个"证书路径全为绝对路径"的临时副本。
     *
     * <p>为什么必须做：SDK 2.x 用 {@code Paths.get(certPath, "ca.crt")} 这类方式拼证书路径，
     * 相对路径是按<b>进程工作目录</b>解析的。于是同一份 config.toml，
     * 在 {@code backend/} 下启动正常，在仓库根目录用
     * {@code java -jar backend/target/chainsip-backend.jar} 启动就会报
     * {@code Missed certificates: [conf\ca.crt,...] currentPath: <PROJECT_DIR>}。
     * 3.x 没有这个问题（它按配置文件所在目录解析）。这里把 certPath 预先解析成绝对路径，
     * 两种启动方式就都能正常工作。
     *
     * <p>实现上只做"整行替换"：匹配 {@code key = "value"} 形式的行，把值换成绝对路径。
     * 因为不改动任何其它字节，TOML 语法与注释都原样保留。
     */
    private Path materializeConfig(Path configFile) throws java.io.IOException {
        String content = new String(Files.readAllBytes(configFile),
                java.nio.charset.StandardCharsets.ISO_8859_1);
        Path baseDir = configFile.getParent();
        String rewritten = content;
        for (String key : new String[] {"certPath", "caCertPath", "sslCertPath", "sslKeyPath",
                "enSSLCertPath", "enSSLPrivateKeyPath", "keyStoreDir"}) {
            rewritten = rewritePathKey(rewritten, key, baseDir);
        }
        Path dir = Files.createTempDirectory("chainsip-sdk-config-");
        Path target = dir.resolve("config.toml");
        // 用 ISO-8859-1 读写：字节级往返，不改变文件编码（文件本身要求是 ASCII）。
        Files.write(target, rewritten.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        return target;
    }

    private String rewritePathKey(String content, String key, Path baseDir) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?m)^(\\s*" + java.util.regex.Pattern.quote(key) + "\\s*=\\s*)\"([^\"]*)\"(.*)$")
                .matcher(content);
        StringBuffer out = new StringBuffer();
        boolean found = false;
        while (matcher.find()) {
            found = true;
            Path resolved = baseDir.resolve(matcher.group(2)).normalize().toAbsolutePath();
            matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(
                    matcher.group(1) + "\"" + resolved.toString().replace("\\", "/") + "\"" + matcher.group(3)));
        }
        if (!found) {
            return content;
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** 2.x 回执里的 status 是十六进制字符串（如 "0x0"），转成整数便于落库与前端展示。 */
    private Integer parseStatus(String status) {
        if (status == null || status.trim().isEmpty()) {
            return null;
        }
        String s = status.trim();
        try {
            return s.startsWith("0x") || s.startsWith("0X")
                    ? Integer.valueOf(s.substring(2), 16)
                    : Integer.valueOf(s);
        } catch (NumberFormatException e) {
            log.debug("无法解析回执状态码：{}", status);
            return null;
        }
    }

    private Long parseLong(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String s = value.trim();
        try {
            return s.startsWith("0x") || s.startsWith("0X")
                    ? Long.valueOf(s.substring(2), 16)
                    : Long.valueOf(s);
        } catch (NumberFormatException e) {
            log.debug("无法解析数字：{}", value);
            return null;
        }
    }

    private String joinMessage(String messages, String fallback) {
        if (messages != null && !messages.trim().isEmpty() && !"[]".equals(messages.trim())) {
            return messages;
        }
        return fallback;
    }

    /** 把回执日志序列化成 JSON（用于事件索引解码索引参数）。失败返回 null，不影响业务。 */
    private String serializeLogs(TransactionReceipt receipt) {
        try {
            if (receipt == null || receipt.getLogs() == null || receipt.getLogs().isEmpty()) {
                return null;
            }
            return objectMapper.writeValueAsString(receipt.getLogs());
        } catch (Exception e) {
            log.debug("序列化回执日志失败（事件索引将退化为仅非索引参数）：{}", e.getMessage());
            return null;
        }
    }
}
