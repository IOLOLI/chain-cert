package com.chainsip.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 合约错误名 → 给最终用户看的<b>中文人话</b>。
 *
 * <p><b>为什么要有这一层</b>：合约用 {@code require(cond, "SomeErrorName")} 回滚，回执里带的是错误名
 * （例如 {@code PaymentCycleNotElapsed}）与 txHash。这些是给开发/运维看的，直接甩到界面上没人看得懂，
 * 所以对外只给中文提示，<b>原文只留给日志与 {@code cs_chain_tx_log.message}</b>。
 *
 * <p><b>三个入口</b>：
 * <ul>
 *   <li>{@link #friendly(String)} / {@link #friendly(String, String)}：已知是合约失败时用，
 *       命中错误名给对应中文，否则给兜底话术；</li>
 *   <li>{@link #sanitize(String)}：用于"拼接型"消息（如 {@code "链上注册失败：" + 原因}），
 *       命中错误名就整句替换成中文，没命中则原样返回但抹掉 txHash；</li>
 *   <li>{@link #forClient(ErrorCode, String)}：{@code GlobalExceptionHandler} 的统一出口。</li>
 * </ul>
 *
 * <p>错误名清单来自 {@code contracts/*.sol} 里全部 {@code require(..., "...")} 与
 * {@code revert Xxx(...)} / {@code error Xxx(...)} 的字符串（2026-10 全量 grep）。
 */
public final class ContractErrorMessages {

    /** 命中不了任何已知错误名时的兜底话术（绝不能把原始错误名漏给用户）。 */
    public static final String FALLBACK = "操作未成功（合约拒绝了这笔交易），请联系管理员";

    /** 链上只读调用（{@code code=2002}）失败、又拿不到具体错误名时的兜底话术。 */
    public static final String CHAIN_CALL_FALLBACK = "链上查询失败，请稍后重试；若持续失败请联系管理员";

    /** 错误名 → 中文提示。 */
    private static final Map<String, String> MESSAGES;

    /** 按长度倒序的错误名数组：先匹配长的，避免子串误判。 */
    private static final String[] NAMES_BY_LENGTH;

    /** 抹掉 {@code txHash=0x…}（含常见的中/英文括号包裹形式）。 */
    private static final Pattern TX_HASH = Pattern.compile(
            "\\(?\\s*(?:txHash|txhash)\\s*=\\s*0x[0-9a-fA-F]+\\s*\\)?");

    static {
        Map<String, String> m = new LinkedHashMap<>();

        // ---- 账号 / 角色 ----
        m.put("NotAnAdmin", "当前账号不是链上管理员，无法执行该操作");
        m.put("NotAuthorized", "当前账号无权执行该操作");
        m.put("NotAdminOrRegulator", "只有管理员或监管方可以查看该数据");
        m.put("AccountHasRole", "该账号已拥有该角色，无需重复授予");
        m.put("AccountLacksRole", "该账号还没有该角色，无法执行该操作");
        m.put("AccountRemoved", "该账号已注销，无法继续操作");
        m.put("LastAdminCannotBeRemoved", "平台至少要保留一名管理员，不能移除最后一位管理员");
        m.put("RoleNotGrantable", "该角色不可授予");
        m.put("RoleNotRevocable", "该角色不可撤销");

        // ---- 可见性 ----
        m.put("AccountNotVisible", "当前账号无权查看该数据");
        m.put("SponsorNotVisible", "当前账号无权查看该数据");
        m.put("TransferNotVisible", "当前账号无权查看该数据");

        // ---- 缴费单位 / 城市 / 注资 ----
        m.put("UnknownSponsor", "该缴费单位尚未在链上注册");
        m.put("SponsorAlreadyExists", "该缴费单位已注册，无需重复注册");
        m.put("InsufficientSponsorBalance", "单位统筹账户余额不足，请先让管理员或社保局注资");
        m.put("UnknownBureau", "该社保局尚未在链上注册");
        m.put("BureauNotAuthorized", "该社保局尚未获得授权，请联系平台管理员");
        m.put("CityAlreadyBound", "该城市已绑定其他社保局");
        m.put("CityNotRegistered", "该城市尚未注册社保局，请先完成社保局登记");
        m.put("CityPoolInsufficient", "参保城市的统筹基金池余额不足");
        m.put("UnknownRegulator", "该监管账户尚未在链上注册");
        m.put("RegulatorAlreadyExists", "该监管账户已存在，无需重复注册");
        m.put("AuthorizationUnchanged", "授权状态与当前一致，无需重复操作");
        m.put("TransferContractUnchanged", "转移合约地址没有变化，无需重复设置");
        m.put("NotTransferContract", "只有转移合约可以调用该操作");

        // ---- 职工 / 参保 ----
        m.put("UnknownEmployee", "该职工尚未在链上建档");
        m.put("AlreadyInsured", "该职工已参保，无需重复建档");
        m.put("IdNumberAlreadyUsed", "该身份证号已被其他职工使用");
        m.put("IdNumberMismatch", "身份证号与档案不一致");
        m.put("EmployeeNotActive", "该职工当前不是正常参保状态，无法执行该操作");
        m.put("EmployeeNotSuspended", "请先为该职工办理减员，再办理转移");
        m.put("NotYourEmployee", "该职工不属于本单位，无法操作");
        m.put("InvalidSalary", "月工资不合法，请填写大于 0 的金额");

        // ---- 缴费 ----
        m.put("PaymentCycleNotElapsed", "当前周期已缴费（同一职工 30 天内只能缴一次）");
        m.put("NoEscrow", "当前没有在途托管资金");
        m.put("EscrowMismatch", "在途托管金额与申请不一致，请联系管理员");

        // ---- 养老金支出（退休 / 发放） ----
        m.put("EmployeeNotRetirable", "该职工当前状态无法办理退休（需为正常参保或已停缴）");
        m.put("RetireAgeNotReached", "未达到法定退休年龄 60 周岁，不能办理退休");
        m.put("PaidMonthsNotEnough", "累计缴费不足 180 个月（15 年），不能办理退休");
        m.put("EmployeeNotRetired", "该职工尚未办理退休，不能领取养老金");
        m.put("PensionCycleNotElapsed", "本期养老金已发放（同一职工 30 天内只能发一次）");
        m.put("AccountRetired", "该职工已退休，不能重新建档参保");

        // ---- 转移 ----
        m.put("UnknownTransfer", "该转移申请不存在");
        m.put("TransferInProgress", "该职工已有在途的转移申请，请先办结");
        m.put("NoActiveTransfer", "当前没有在途的转移申请");
        m.put("InvalidTransferStatus", "该转移申请当前的状态不支持此操作");
        m.put("NotTransferBureau", "该转移申请不由本社保局办理");
        m.put("SameCityTransfer", "转出地与转入地相同，无需办理转移");
        m.put("CrossCityRequiresTransfer", "跨城市参保需要先办理转移，不能直接建档");
        m.put("BalanceNotZero", "该账户仍有余额，请先结清后再办理");

        // ---- 参数 ----
        m.put("ZeroAddress", "提交的信息不完整（地址不能为空）");
        m.put("EmptyString", "提交的信息不完整，请填写必填项");
        m.put("ZeroAmount", "金额必须大于 0");

        // ---- 库级溢出保护（0.6 兼容库） ----
        m.put("SafeMath: addition overflow", "计算溢出，请联系管理员");
        m.put("SafeMath: subtraction overflow", "计算溢出，请联系管理员");
        m.put("SafeMath: multiplication overflow", "计算溢出，请联系管理员");
        m.put("SafeMath: division by zero", "计算异常，请联系管理员");
        m.put("SafeMathTransfer: addition overflow", "计算溢出，请联系管理员");
        m.put("SafeMathTransfer: subtraction overflow", "计算溢出，请联系管理员");
        m.put("SafeMathTransfer: multiplication overflow", "计算溢出，请联系管理员");
        m.put("SafeMathTransfer: division by zero", "计算异常，请联系管理员");

        MESSAGES = Collections.unmodifiableMap(m);

        List<String> names = new ArrayList<>(m.keySet());
        names.sort((a, b) -> b.length() - a.length());
        NAMES_BY_LENGTH = names.toArray(new String[0]);
    }

    private ContractErrorMessages() {
    }

    /** 命中已知错误名 → 对应中文；否则返回 {@link #FALLBACK}。 */
    public static String friendly(String raw) {
        return friendly(raw, FALLBACK);
    }

    /**
     * 命中已知错误名 → 对应中文；否则返回调用方给的 {@code fallback}。
     *
     * <p>只有"确定这次失败来自合约"的场景才该用本方法（例如交易回执失败）；
     * 链未就绪、网络不通这类原因要走调用方自己的兜底话术。
     */
    public static String friendly(String raw, String fallback) {
        String text = MESSAGES.get(detect(raw));
        return text != null ? text : fallback;
    }

    /**
     * 用于"拼接型"消息：命中已知错误名 → 整句替换成该错误的中文；
     * 没命中 → 原样返回，但把 {@code txHash=0x…} 这类内部细节抹掉。
     *
     * <p>整句替换而不是原地改词，是为了避免 {@code 链上注册失败：X（txHash=…）} 这种半截内部文本漏出去。
     */
    public static String sanitize(String message) {
        if (message == null) {
            return null;
        }
        String text = MESSAGES.get(detect(message));
        if (text != null) {
            return text;
        }
        return stripTxHash(message);
    }

    /** HTTP 响应里的统一出口：能直接展示给用户的中文。 */
    public static String forClient(ErrorCode code, String message) {
        String text = MESSAGES.get(detect(message));
        if (text != null) {
            return text;
        }
        if (code == ErrorCode.CHAIN_CALL_ERROR) {
            // 只读调用失败（可读性最差的一类内部包装话术）不进界面，具体原因看日志
            return CHAIN_CALL_FALLBACK;
        }
        return stripTxHash(message);
    }

    /** 在原文里识别合约错误名；识别不出返回 {@code null}。 */
    public static String detect(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        for (String name : NAMES_BY_LENGTH) {
            if (raw.contains(name)) {
                return name;
            }
        }
        return null;
    }

    private static String stripTxHash(String message) {
        if (message == null) {
            return null;
        }
        return TX_HASH.matcher(message).replaceAll("").trim();
    }
}
