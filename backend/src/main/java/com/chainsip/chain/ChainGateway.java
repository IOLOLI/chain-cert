package com.chainsip.chain;

import com.chainsip.chain.dto.BlockInfo;
import com.chainsip.chain.dto.CallResult;
import com.chainsip.chain.dto.ChainAccount;
import com.chainsip.chain.dto.SendResult;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 链交互网关。把具体 SDK（FISCO BCOS Java SDK 2.x）隔离在这一层后面：
 * 上层 Service / Controller 只认这个接口，将来换 SDK 版本或换链（如 3.x、长安链）
 * 只需要新增一个实现类，业务代码不动。
 */
public interface ChainGateway {

    /** 网关是否可用（配置齐全 + SDK 初始化成功）。 */
    boolean isReady();

    /** 不可用时的原因，用于接口返回明确的排查提示。 */
    String statusMessage();

    /** 主动重试初始化（改完配置或链刚起来时调用）。 */
    boolean refresh();

    /** 只读调用：对应合约的 view 函数（不带调用者身份）。 */
    CallResult call(String contractName, String functionName, List<Object> params);

    /**
     * 带调用者身份的只读调用。
     *
     * <p>合约里的可见性判断依赖 {@code msg.sender}，而 {@code msg.sender} 取决于"这次调用用哪把私钥"。
     * 2.x SDK 不像 3.x 那样自带默认身份，因此必须显式指定，否则节点看到的是陌生地址，
     * 会得到 AccountNotVisible 之类的链上拒绝。
     *
     * <p>默认实现退化为不带身份的 {@link #call(String, String, List)}，便于其它实现类保持兼容。
     */
    default CallResult call(ChainAccount account, String contractName, String functionName, List<Object> params) {
        return call(contractName, functionName, params);
    }

    /** 发交易：用指定机构链账户签名。 */
    SendResult send(ChainAccount account, String contractName, String functionName, List<Object> params);

    /** 最新区块信息（区块高度、区块哈希、最新交易哈希）。 */
    BlockInfo latestBlock();

    // ------------------------------------------------------------------
    // 区块链浏览器（只读）
    // ------------------------------------------------------------------

    /** 链概览：高度、累计交易数、失败交易数、节点版本、群组。 */
    default Map<String, Object> chainOverview() {
        return Collections.emptyMap();
    }

    /** 按高度取区块；withTransactions=true 时带完整交易对象，否则只带交易哈希。 */
    default Map<String, Object> blockByNumber(long number, boolean withTransactions) {
        throw new UnsupportedOperationException("当前链路实现不支持按高度查区块");
    }

    /** 按区块哈希取区块。 */
    default Map<String, Object> blockByHash(String hash, boolean withTransactions) {
        throw new UnsupportedOperationException("当前链路实现不支持按哈希查区块");
    }

    /** 按交易哈希取交易详情（含回执状态、日志与输入数据）。 */
    default Map<String, Object> transactionByHash(String hash) {
        throw new UnsupportedOperationException("当前链路实现不支持按哈希查交易");
    }

    /** 当前节点版本，便于确认链环境。 */
    String nodeVersion();

    /**
     * 共识拓扑：{@code sealers}（出块节点）、{@code observers}（观察节点，同步账本但不参与共识）、
     * {@code nodeIds}（已入组的全部节点）。
     *
     * <p>监管节点在 FISCO BCOS 里就是 observer：它拿到的是完整账本、能独立复核，
     * 但投票权在 sealers 手里——这一点在界面上要能看出来，所以单独开一个只读探测。
     * 默认返回空表，便于其它实现类保持兼容。
     */
    default Map<String, Object> topology() {
        return Collections.emptyMap();
    }
}
