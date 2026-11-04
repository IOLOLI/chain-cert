package com.chainsip.service;

import com.chainsip.chain.dto.CallResult;
import com.chainsip.common.BizException;
import com.chainsip.common.ContractErrorMessages;
import com.chainsip.common.ErrorCode;
import com.chainsip.config.ChainProperties;
import com.chainsip.dto.InstitutionRegisterResult;
import com.chainsip.dto.InstitutionRegistrationInfo;
import com.chainsip.entity.InstitutionKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「待上链注册」机构名单与逐条注册（ADMIN）。
 *
 * <p><b>为什么需要这个模块</b>：合约里 {@code addBureaus} / {@code addSponsorAccount} /
 * {@code addRegulatorAccount} 都带 {@code onlyAdmin}，社保局创建的缴费单位自己发交易必然被回滚。
 * 以前这些机构开完号就"挂空"了，要靠管理员凭记忆去调链上注册接口。现在把所有"链下有私钥、
 * 链上没角色（或没授权）"的机构统一列出来，管理员点一下即可注册。
 *
 * <p><b>判定完全以链为准</b>：链下 {@code cs_institution_key.role} 只是登记信息，真正的权威是
 * 核心合约的 {@code roleOf(address)}（角色表在核心合约里，BUREAU 由转移合约的 addBureaus 间接授予），
 * 以及转移合约 {@code getBureau(address)} 的 authorized 字段。
 *
 * <p>所有只读查询都包在 try/catch 里：链未就绪 / 单条查询失败时宁可返回空数组并 warn，
 * 也不能让管理页面 500。
 */
@Service
public class InstitutionRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(InstitutionRegistrationService.class);

    private static final String CORE = ChainProperties.CORE;
    private static final String TRANSFER = ChainProperties.TRANSFER;

    /** 需要链上注册的角色 → 链上应有的角色枚举序号（合约 Roles.Role：0 NONE / 1 ADMIN / 2 BUREAU / 3 SPONSOR / 4 REGULATOR）。 */
    private static final Map<String, Integer> ON_CHAIN_ROLE_INDEX;

    /** 链上角色序号 → 名称，与合约枚举一致。 */
    private static final String[] ROLE_NAMES = {"NONE", "ADMIN", "BUREAU", "SPONSOR", "REGULATOR"};

    private static final String MISSING_NONE = "";
    private static final String MISSING_NOT_REGISTERED = "未注册";
    private static final String MISSING_NOT_AUTHORIZED = "未授权";

    static {
        Map<String, Integer> index = new LinkedHashMap<>();
        index.put(AuthService.ROLE_BUREAU, 2);
        index.put(AuthService.ROLE_SPONSOR, 3);
        index.put(AuthService.ROLE_REGULATOR, 4);
        ON_CHAIN_ROLE_INDEX = java.util.Collections.unmodifiableMap(index);
    }

    private final InstitutionKeyService institutionKeyService;
    private final AdminBizService adminBizService;
    private final ChainTxService chainTxService;

    public InstitutionRegistrationService(InstitutionKeyService institutionKeyService,
                                          AdminBizService adminBizService,
                                          ChainTxService chainTxService) {
        this.institutionKeyService = institutionKeyService;
        this.adminBizService = adminBizService;
        this.chainTxService = chainTxService;
    }

    // ------------------------------------------------------------------
    // GET /api/admin/institutions/pending
    // ------------------------------------------------------------------

    /** 待上链注册或待授权的机构（链未就绪时返回空数组）。 */
    public List<InstitutionRegistrationInfo> listPending() {
        List<InstitutionRegistrationInfo> pending = new ArrayList<>();
        if (!isChainReady()) {
            return pending;
        }
        List<InstitutionKey> keys = institutionKeyService.listAll();

        for (InstitutionKey key : keys) {
            if (!Boolean.TRUE.equals(key.getEnabled())) {
                continue;
            }
            if (!ON_CHAIN_ROLE_INDEX.containsKey(key.getRole())) {
                // ADMIN（链上本来就该有）与 EMPLOYEE / 未知角色不在本列表范围内
                continue;
            }
            if (key.getAddress() == null || key.getAddress().trim().isEmpty()) {
                continue;
            }
            try {
                InstitutionRegistrationInfo info = inspect(key);
                if (!MISSING_NONE.equals(info.getMissing())) {
                    pending.add(info);
                }
            } catch (Exception e) {
                // 单条读链失败：保守地当作"未注册"放进列表（管理员可以点注册重试），并写 warn
                log.warn("读取机构 {}（{}）的链上状态失败，按未注册处理：{}",
                        key.getCode(), key.getAddress(), describe(e));
                InstitutionRegistrationInfo info = baseInfo(key);
                info.setRegistered(false);
                info.setAuthorized(false);
                info.setMissing(MISSING_NOT_REGISTERED);
                pending.add(info);
            }
        }
        return pending;
    }

    /** 查一个机构在链上的状态，得出 missing 结论。 */
    private InstitutionRegistrationInfo inspect(InstitutionKey key) {
        String role = key.getRole();
        InstitutionRegistrationInfo info = baseInfo(key);

        Integer expected = ON_CHAIN_ROLE_INDEX.get(role);
        int onChainRoleIndex = onChainRoleIndex(key.getAddress());
        info.setOnChainRole(roleName(onChainRoleIndex));

        if (expected == null || onChainRoleIndex != expected) {
            info.setRegistered(false);
            info.setAuthorized(false);
            info.setMissing(MISSING_NOT_REGISTERED);
            return info;
        }

        info.setRegistered(true);
        if (!AuthService.ROLE_BUREAU.equals(role)) {
            // SPONSOR / REGULATOR 没有"授权"这一层状态，角色到位即就绪
            info.setAuthorized(true);
            info.setMissing(MISSING_NONE);
            return info;
        }

        boolean authorized = bureauAuthorized(key.getAddress());
        info.setAuthorized(authorized);
        info.setMissing(authorized ? MISSING_NONE : MISSING_NOT_AUTHORIZED);
        return info;
    }

    private InstitutionRegistrationInfo baseInfo(InstitutionKey key) {
        InstitutionRegistrationInfo info = new InstitutionRegistrationInfo();
        info.setCode(key.getCode());
        info.setRole(key.getRole());
        info.setName(key.getName() == null ? "" : key.getName());
        info.setAddress(key.getAddress());
        return info;
    }

    // ------------------------------------------------------------------
    // POST /api/admin/institutions/{code}/register
    // ------------------------------------------------------------------

    /**
     * 注册单个机构（<b>状态感知、幂等</b>）。
     *
     * <p>先读链上状态再决定发哪笔交易，绝不无脑重放注册交易：
     * <ol>
     *   <li>BUREAU 已注册、仅未授权 → <b>只调</b> {@code changeBureausAuthorized(true)}，
     *       不再调 {@code addBureaus}（合约对已存在的社保局会因 CityAlreadyBound 等回滚），
     *       且这种情形<b>不要求</b> city；</li>
     *   <li>BUREAU 未注册 → {@code addBureaus} → {@code changeBureausAuthorized(true)}；</li>
     *   <li>SPONSOR / REGULATOR 链上角色已匹配 → 直接返回成功，<b>不发交易</b>；</li>
     *   <li>SPONSOR / REGULATOR 未注册 → 调对应注册方法。</li>
     * </ol>
     * 因此 {@code city} 只在"确实要发 addBureaus / addSponsorAccount"时才必填。
     *
     * <p>失败（含链上回滚）一律返回 {@code ok=false} 的结果对象（HTTP 200），
     * 只有"机构不存在 / 参数不合法"这类请求本身的问题才抛 {@link BizException}（404 / 400）。
     */
    public InstitutionRegisterResult register(String code, String rawCity) {
        InstitutionKey key = institutionKeyService.findByCode(code);
        if (key == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "未登记该机构链账户：" + code);
        }
        String role = key.getRole() == null ? "" : key.getRole().trim().toUpperCase();
        if (!ON_CHAIN_ROLE_INDEX.containsKey(role)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "机构 " + code + " 的角色是 " + key.getRole() + "，无需（也不支持）链上注册");
        }
        if (key.getAddress() == null || key.getAddress().trim().isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "机构 " + code + " 没有链上地址，无法注册");
        }
        String name = trimToNull(key.getName());
        if (name == null) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "机构 " + code + " 没有登记名称（cs_institution_key.name 为空），无法注册");
        }

        String city = trimToNull(rawCity);
        String address = key.getAddress();
        boolean bureau = AuthService.ROLE_BUREAU.equals(role);

        InstitutionRegisterResult result = new InstitutionRegisterResult();
        result.setCode(key.getCode());
        result.setRole(role);
        result.setAddress(address);

        // 先看链上真实状态：这是"幂等 + 状态感知"的关键，不能省
        boolean registered;
        boolean authorized = false;
        try {
            registered = onChainRoleIndex(address) == ON_CHAIN_ROLE_INDEX.get(role).intValue();
            if (bureau && registered) {
                authorized = bureauAuthorized(address);
            }
        } catch (Exception e) {
            // 读不出去也不 500：按"未知/未注册"继续，用城市要求兜住参数，真正的错误由写交易阶段报出
            log.warn("机构 {}（{}）链上状态读取失败，按未注册继续尝试注册：{}", code, role, describe(e));
            registered = false;
        }

        // city 只在确实需要发注册交易时才必填（只补授权的 BUREAU 不需要）
        boolean willRegister = !registered;
        if (willRegister && requiresCity(role) && city == null) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "机构 " + code + "（" + role + "）必须先补充 city 才能注册上链，"
                            + "例如 body: {\"city\":\"A市\"}");
        }

        if (registered && (!bureau || authorized)) {
            // 已就绪：幂等返回，不发任何交易（前端重复点击不会产生回滚噪音）
            result.setOk(true);
            result.setMessage("已注册，无需重复操作");
            return result;
        }

        // 已注册、只是未授权 → 只补授权
        boolean onlyAuthorize = registered && bureau && !authorized;
        try {
            if (onlyAuthorize) {
                adminBizService.changeBureausAuthorized(address, true);
                result.setOk(true);
                result.setMessage("已补授权：address=" + address);
            } else if (bureau) {
                adminBizService.addBureaus(address, name, city);
                adminBizService.changeBureausAuthorized(address, true);
                result.setOk(true);
                result.setMessage("已注册上链并授权：address=" + address);
            } else if (AuthService.ROLE_SPONSOR.equals(role)) {
                adminBizService.addSponsorAccount(address, name, city);
                result.setOk(true);
                result.setMessage("已注册上链：address=" + address);
            } else {
                // REGULATOR：agency 优先取 city，没给就用 name
                adminBizService.addRegulatorAccount(address, name, city == null ? name : city);
                result.setOk(true);
                result.setMessage("已注册上链：address=" + address);
            }
            log.info("待上链注册：机构 {}（{}）{}成功，address={}",
                    code, role, onlyAuthorize ? "补授权" : "注册", address);
        } catch (BizException e) {
            log.warn("待上链注册：机构 {}（{}）{}失败：{}", code, role, onlyAuthorize ? "补授权" : "注册", describe(e));
            result.setOk(false);
            result.setMessage((onlyAuthorize ? "补授权失败：" : "链上注册失败：")
                    + ContractErrorMessages.sanitize(describe(e)));
        } catch (Exception e) {
            log.warn("待上链注册：机构 {}（{}）{}异常：{}", code, role, onlyAuthorize ? "补授权" : "注册", describe(e));
            result.setOk(false);
            result.setMessage((onlyAuthorize ? "补授权异常：" : "链上注册异常：")
                    + ContractErrorMessages.sanitize(describe(e)));
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 链上只读
    // ------------------------------------------------------------------

    /** 核心合约 roleOf(address) → 角色序号；解析不出数字时返回 -1。 */
    private int onChainRoleIndex(String address) {
        CallResult result = chainTxService.call(CORE, "roleOf",
                ChainParams.address(address, "机构地址"));
        return asRoleIndex(result == null ? null : result.first());
    }

    /** 转移合约 getBureau(address).authorized；调用失败（例如根本没注册）当作未授权。 */
    private boolean bureauAuthorized(String address) {
        try {
            CallResult result = chainTxService.call(TRANSFER, "getBureau",
                    ChainParams.address(address, "社保局地址"));
            Object value = ChainViewMapper.map(result, ChainViewMapper.BUREAU).get("authorized");
            if (value instanceof Boolean) {
                return (Boolean) value;
            }
            return value != null && Boolean.parseBoolean(String.valueOf(value));
        } catch (Exception e) {
            log.debug("读取社保局 {} 的授权状态失败（按未授权处理）：{}", address, describe(e));
            return false;
        }
    }

    private static int asRoleIndex(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                // 落到下面的 -1
            }
        }
        return -1;
    }

    private static String roleName(int index) {
        return index >= 0 && index < ROLE_NAMES.length ? ROLE_NAMES[index] : "UNKNOWN";
    }

    /** 链是否就绪；未就绪时只写一条 warn，由调用方返回空结果。 */
    private boolean isChainReady() {
        try {
            if (!chainTxService.isReady()) {
                log.warn("链未就绪，机构链上注册状态查询返回空结果（请检查 SDK 配置、证书与合约地址）");
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("查询链状态失败，机构链上注册状态查询返回空结果：{}", describe(e));
            return false;
        }
    }

    private static boolean requiresCity(String role) {
        return AuthService.ROLE_BUREAU.equals(role) || AuthService.ROLE_SPONSOR.equals(role);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String describe(Throwable e) {
        if (e instanceof BizException) {
            String message = e.getMessage();
            if (message != null && !message.trim().isEmpty()) {
                return message.trim();
            }
        }
        String message = e.getMessage();
        if (message != null && !message.trim().isEmpty()) {
            return message.trim();
        }
        return e.getClass().getSimpleName();
    }
}
