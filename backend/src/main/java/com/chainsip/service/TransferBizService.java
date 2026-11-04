package com.chainsip.service;

import com.chainsip.chain.dto.CallResult;
import com.chainsip.common.BizException;
import com.chainsip.common.ErrorCode;
import com.chainsip.chain.dto.SendResult;
import com.chainsip.config.ChainProperties;
import com.chainsip.entity.InstitutionKey;
import com.chainsip.security.AuthContext;
import com.chainsip.security.AuthUser;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;

/**
 * 社保局（BUREAU）侧业务与转移查询，同时提供监管（REGULATOR）审计只读接口。
 *
 * <p>放行 / 接收只能由"该申请对应的转出地 / 转入地社保局"发起，且该社保局必须处于已授权状态——
 * 这两条都在合约里强制，后端只是用对应机构的私钥去发交易。
 */
@Service
public class TransferBizService {

    private static final String CORE = ChainProperties.CORE;
    private static final String TRANSFER = ChainProperties.TRANSFER;

    /** 按职工列申请时最多回扫多少条申请，避免链调用随全平台申请数线性增长。 */
    private static final int MAX_TRANSFER_SCAN = 200;

    private final ChainTxService chain;
    private final InstitutionKeyService institutionKeyService;
    private final RedisCacheService cache;

    public TransferBizService(ChainTxService chain, InstitutionKeyService institutionKeyService,
                              RedisCacheService cache) {
        this.chain = chain;
        this.institutionKeyService = institutionKeyService;
        this.cache = cache;
    }

    // ------------------------------------------------------------------
    // 社保局：办理转移
    // ------------------------------------------------------------------

    /** 转出地复核放行；approved=false 即"复核失败"，需给出原因。 */
    public SendResult authorizeTransfer(Object id, boolean approved, String reason) {
        return chain.send(chain.currentInstitutionCode(), TRANSFER, "authorizeTransfer",
                ChainParams.uint(id, "申请编号"), approved, reason == null ? "" : reason);
    }

    /** 转入地接收：建立新城市参保档案并落下托管基金。 */
    public SendResult receiveTransfer(Object id) {
        return chain.send(chain.currentInstitutionCode(), TRANSFER, "receiveTransfer",
                ChainParams.uint(id, "申请编号"));
    }

    /** 转入地待办列表。 */
    public Map<String, Object> getInboundTransfers(String bureau, int offset, int limit) {
        return idListView(chain.call(TRANSFER, "getInboundTransfers",
                ChainParams.address(bureau, "社保局地址"), ChainParams.uint(offset, "offset"),
                ChainParams.uint(limit, "limit")));
    }

    /** 转出地待办列表。 */
    public Map<String, Object> getOutboundTransfers(String bureau, int offset, int limit) {
        return idListView(chain.call(TRANSFER, "getOutboundTransfers",
                ChainParams.address(bureau, "社保局地址"), ChainParams.uint(offset, "offset"),
                ChainParams.uint(limit, "limit")));
    }

    // ------------------------------------------------------------------
    // 转移查询（本人 / 相关社保局 / ADMIN / REGULATOR 都可读）
    // ------------------------------------------------------------------

    /**
     * 查转移申请详情。
     *
     * <p>合约 {@code getTransfer} 是「单个 tuple 输出」，必须用 {@link ChainViewMapper#mapStruct} 拆一层；
     * 否则整行会被塞进第一个字段名 {@code id}，前端读 {@code transfer.fromCity / toCity / status} 全是空。
     */
    public Map<String, Object> getTransfer(Object id) {
        Map<String, Object> view = ChainViewMapper.mapStruct(chain.call(TRANSFER, "getTransfer",
                ChainParams.uint(id, "申请编号")), ChainViewMapper.TRANSFER_APPLICATION);
        view.put("statusName", ChainViewMapper.transferStatusName(view.get("status")));
        return view;
    }

    public Object getTransferCount() {
        return cache.getOrLoad("transfer:count:v1", Duration.ofSeconds(20),
                () -> chain.call(TRANSFER, "getTransferCount").first());
    }

    /**
     * 当前登录账号的全部转移申请（职工本人视角，按申请编号倒序）。
     *
     * <p>合约没有"按职工列申请"的接口，只有 getTransferCount + getTransfer(id)。这里用 ADMIN 身份
     * 倒序回扫最近 {@value #MAX_TRANSFER_SCAN} 条申请再按 employee 过滤——不能用职工身份扫，
     * 读别人的申请会被合约拒绝。扫描后只做内存分页；申请量大了应改为事件索引。
     */
    public Map<String, Object> listMyTransfers(int offset, int limit) {
        AuthUser me = AuthContext.require();
        String key = "transfer:mine:" + me.getAccountId() + ":" + me.getInstitutionCode()
                + ":" + offset + ":" + limit;
        return cache.getOrLoad(key, Duration.ofSeconds(15),
                () -> listMyTransfersUncached(me, offset, limit));
    }

    private Map<String, Object> listMyTransfersUncached(AuthUser me, int offset, int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> matched = new ArrayList<>();
        out.put("list", new ArrayList<Map<String, Object>>());
        out.put("total", 0);
        out.put("offset", offset);
        out.put("limit", limit);

        InstitutionKey key = me.getInstitutionCode() == null ? null
                : institutionKeyService.findByCode(me.getInstitutionCode());
        String address = key == null || key.getAddress() == null ? "" : key.getAddress().trim();
        if (address.isEmpty()) {
            out.put("reason", "当前账号没有链上地址，无法查询转移申请");
            return out;
        }

        Object countValue = chain.callAs(AuthService.ROLE_ADMIN, TRANSFER, "getTransferCount").first();
        long count = countValue instanceof Number ? ((Number) countValue).longValue()
                : parseLong(String.valueOf(countValue));
        int scanned = 0;
        for (long id = count - 1; id >= 0 && scanned < MAX_TRANSFER_SCAN; id--, scanned++) {
            Map<String, Object> row = ChainViewMapper.mapStruct(
                    chain.callAs(AuthService.ROLE_ADMIN, TRANSFER, "getTransfer", ChainParams.uint(id, "申请编号")),
                    ChainViewMapper.TRANSFER_APPLICATION);
            String employee = row.get("employee") == null ? "" : String.valueOf(row.get("employee"));
            if (!address.equalsIgnoreCase(employee)) {
                continue;
            }
            row.put("statusName", ChainViewMapper.transferStatusName(row.get("status")));
            matched.add(row);
        }
        int from = Math.min(Math.max(offset, 0), matched.size());
        int to = Math.min(from + Math.max(limit, 1), matched.size());
        out.put("list", new ArrayList<>(matched.subList(from, to)));
        out.put("total", matched.size());
        out.put("scanned", scanned);
        return out;
    }

    /**
     * 待接续候选（内部）：转入本市、已接收（RECEIVED）且当前仍无单位的职工。
     *
     * <p>含完整身份证号，仅用于搜索匹配与接续校验，<b>不直接下发前端</b>。
     */
    private List<Map<String, Object>> rehireCandidates() {
        AuthUser me = AuthContext.require();
        List<Map<String, Object>> out = new ArrayList<>();
        InstitutionKey key = me.getInstitutionCode() == null ? null
                : institutionKeyService.findByCode(me.getInstitutionCode());
        String sponsor = key == null || key.getAddress() == null ? "" : key.getAddress().trim();
        if (sponsor.isEmpty()) {
            return out;
        }

        String city;
        try {
            Map<String, Object> view = ChainViewMapper.mapStruct(
                    chain.callAs(AuthService.ROLE_ADMIN, CORE, "getSponsorAccount", sponsor),
                    ChainViewMapper.SPONSOR_ACCOUNT);
            city = textOf(view.get("city"));
        } catch (RuntimeException e) {
            return out;
        }
        if (city.isEmpty()) {
            return out;
        }

        long count = 0L;
        try {
            Object value = chain.callAs(AuthService.ROLE_ADMIN, TRANSFER, "getTransferCount").first();
            count = value instanceof Number ? ((Number) value).longValue() : parseLong(String.valueOf(value));
        } catch (RuntimeException e) {
            return out;
        }

        Set<String> seen = new LinkedHashSet<>();
        int scanned = 0;
        for (long id = count - 1; id >= 0 && scanned < MAX_TRANSFER_SCAN; id--, scanned++) {
            Map<String, Object> transfer;
            try {
                transfer = ChainViewMapper.mapStruct(
                        chain.callAs(AuthService.ROLE_ADMIN, TRANSFER, "getTransfer", ChainParams.uint(id, "申请编号")),
                        ChainViewMapper.TRANSFER_APPLICATION);
            } catch (RuntimeException e) {
                continue;
            }
            if (asInt(transfer.get("status")) != 3) {
                continue;
            }
            String toCity = textOf(transfer.get("toCity"));
            if (!city.equals(toCity)) {
                continue;
            }
            String employee = textOf(transfer.get("employee"));
            if (employee.isEmpty() || !seen.add(employee.toLowerCase())) {
                continue;
            }
            Map<String, Object> account;
            try {
                account = ChainViewMapper.mapStruct(
                        chain.callAs(AuthService.ROLE_ADMIN, CORE, "getInsuranceAccount", employee),
                        ChainViewMapper.INSURANCE_ACCOUNT);
            } catch (RuntimeException e) {
                continue;
            }
            if (asInt(account.get("status")) != 3
                    || !city.equals(textOf(account.get("city")))
                    || !isZeroAddress(textOf(account.get("sponsor")))) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("address", employee);
            row.put("name", account.get("name"));
            row.put("age", account.get("age"));
            row.put("idNumber", account.get("idNumber"));
            row.put("employer", account.get("employer"));
            row.put("paymentBase", account.get("paymentBase"));
            row.put("salary", account.get("salary"));
            row.put("paidMonths", account.get("paidMonths"));
            row.put("fromCity", transfer.get("fromCity"));
            row.put("toCity", toCity);
            row.put("transferId", id);
            row.put("receivedAt", transfer.get("receivedAt"));
            out.add(row);
        }
        return out;
    }

    /**
     * 企业按信息搜索待接续职工（方案 A：只查不列）。
     *
     * <p>不提供"列出全部待接续人员"，避免同一城市任意企业都能看到辖区所有人的姓名/身份证。
     * 关键词只接受两种：完整 18 位身份证号（精确匹配），或 姓名 + 身份证后 4 位（如 李四2345）。
     * 命中后身份证做掩码，只返回接续所需的最小字段，最多 20 条。
     */
    public List<Map<String, Object>> searchRehire(String keyword) {
        String key = keyword == null ? "" : keyword.trim();
        List<Map<String, Object>> out = new ArrayList<>();
        if (key.isEmpty()) {
            return out;
        }
        boolean idMode = key.matches("\\d{18}");
        String namePart = null;
        String last4 = null;
        if (!idMode) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("^(.*?)(\\d{4})$").matcher(key);
            if (!matcher.matches() || matcher.group(1).trim().length() < 2) {
                return out;
            }
            namePart = matcher.group(1).trim();
            last4 = matcher.group(2);
        }
        for (Map<String, Object> row : rehireCandidates()) {
            String id = textOf(row.get("idNumber"));
            String name = textOf(row.get("name"));
            boolean hit = idMode ? key.equals(id) : (id.endsWith(last4) && name.contains(namePart));
            if (!hit) {
                continue;
            }
            Map<String, Object> view = new LinkedHashMap<>(row);
            view.put("idNumber", maskId(id));
            out.add(view);
            if (out.size() >= 20) {
                break;
            }
        }
        return out;
    }

    /**
     * 企业发起接续参保（方案 A）。
     *
     * <p>姓名 / 年龄 / 身份证 / 缴费基数全部取链上档案，前端只传地址，避免参数被改错；
     * 签名身份仍是当前登录的缴费单位，合约照常校验 onlySponsor 与城市一致。
     */
    public SendResult rehire(String employeeAddress) {
        AuthUser me = AuthContext.require();
        InstitutionKey key = me.getInstitutionCode() == null ? null
                : institutionKeyService.findByCode(me.getInstitutionCode());
        String sponsor = key == null || key.getAddress() == null ? "" : key.getAddress().trim();
        if (sponsor.isEmpty()) {
            throw new BizException(ErrorCode.FORBIDDEN, "当前账号没有机构链地址，无法发起接续参保");
        }
        Map<String, Object> sponsorView = ChainViewMapper.mapStruct(
                chain.callAs(AuthService.ROLE_ADMIN, CORE, "getSponsorAccount", sponsor),
                ChainViewMapper.SPONSOR_ACCOUNT);
        String city = textOf(sponsorView.get("city"));
        String sponsorName = textOf(sponsorView.get("name"));
        Map<String, Object> account = ChainViewMapper.mapStruct(
                chain.callAs(AuthService.ROLE_ADMIN, CORE, "getInsuranceAccount", employeeAddress),
                ChainViewMapper.INSURANCE_ACCOUNT);
        if (asInt(account.get("status")) != 3) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该职工不是「已转出待参保」状态，无法接续");
        }
        if (!city.equals(textOf(account.get("city")))) {
            throw new BizException(ErrorCode.FORBIDDEN, "该职工关系不在本市，不能由本单位接续");
        }
        if (!isZeroAddress(textOf(account.get("sponsor")))) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该职工已有参保单位，无需接续");
        }
        String name = textOf(account.get("name"));
        String idNumber = textOf(account.get("idNumber"));
        int age = asInt(account.get("age"));
        if (age <= 0) {
            age = 30;
        }
        long salary = asLong(account.get("paymentBase"));
        if (salary <= 0) {
            salary = asLong(account.get("salary"));
        }
        if (salary <= 0) {
            salary = 10000L;
        }
        return chain.send(me.getInstitutionCode(), CORE, "addEmployee",
                employeeAddress, name, age, idNumber,
                sponsorName.isEmpty() ? "接续参保" : sponsorName,
                System.currentTimeMillis() / 1000L, salary);
    }

    private static String textOf(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static long asLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return parseLong(String.valueOf(value));
    }

    /** 身份证掩码：保留前 6 位与后 4 位。 */
    private static String maskId(String id) {
        if (id == null || id.length() < 12) {
            return id == null ? "" : id;
        }
        return id.substring(0, 6) + "********" + id.substring(id.length() - 4);
    }

    private static int asInt(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private static boolean isZeroAddress(String address) {
        if (address == null || address.isEmpty()) {
            return true;
        }
        String hex = address.startsWith("0x") || address.startsWith("0X") ? address.substring(2) : address;
        if (hex.isEmpty()) {
            return true;
        }
        for (int i = 0; i < hex.length(); i++) {
            if (hex.charAt(i) != '0') {
                return false;
            }
        }
        return true;
    }

    private static long parseLong(String text) {
        try {
            return Long.parseLong(text == null ? "" : text.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    // ------------------------------------------------------------------
    // 社保局注册信息（公开）
    // ------------------------------------------------------------------

    public Map<String, Object> getBureau(String bureau) {
        return ChainViewMapper.map(chain.call(TRANSFER, "getBureau",
                ChainParams.address(bureau, "社保局地址")), ChainViewMapper.BUREAU);
    }

    public Object getBureauCount() {
        return cache.getOrLoad("transfer:bureau-count:v1", Duration.ofSeconds(20),
                () -> chain.call(TRANSFER, "getBureauCount").first());
    }

    public Map<String, Object> getBureauList(int offset, int limit) {
        return idListView(chain.call(TRANSFER, "getBureauList",
                ChainParams.uint(offset, "offset"), ChainParams.uint(limit, "limit")));
    }

    public Object bureauOfCity(String city) {
        return chain.call(TRANSFER, "bureauOfCity", city).first();
    }

    // ------------------------------------------------------------------
    // 监管（只读审计）
    // ------------------------------------------------------------------

    /** 监管大屏：平台规模 + 资金/权益对账数据。 */
    public Map<String, Object> getRegulatorDashboard() {
        return cache.getOrLoad("regulator:dashboard:v1", Duration.ofSeconds(20),
                this::getRegulatorDashboardUncached);
    }

    private Map<String, Object> getRegulatorDashboardUncached() {
        CallResult result = chain.call(CORE, "getRegulatorDashboard");
        Map<String, Object> view = new LinkedHashMap<>();
        List<Object> values = result.getValues();
        if (values != null) {
            for (int i = 0; i < ChainViewMapper.DASHBOARD_HEAD.length && i < values.size(); i++) {
                view.put(ChainViewMapper.DASHBOARD_HEAD[i], values.get(i));
            }
            if (values.size() > ChainViewMapper.DASHBOARD_HEAD.length) {
                Map<String, Object> stats = new LinkedHashMap<>();
                Object raw = values.get(ChainViewMapper.DASHBOARD_HEAD.length);
                if (raw instanceof List) {
                    List<?> fields = (List<?>) raw;
                    for (int i = 0; i < ChainViewMapper.SYSTEM_STATS.length && i < fields.size(); i++) {
                        stats.put(ChainViewMapper.SYSTEM_STATS[i], fields.get(i));
                    }
                }
                view.put("stats", stats);
            }
        }
        return view;
    }

    /** 平台资金/权益总览。合约 {@code getSystemStats} 是「单个 tuple 输出」，故用 mapStruct 拆一层。 */
    public Map<String, Object> getSystemStats() {
        return cache.getOrLoad("regulator:system-stats:v1", Duration.ofSeconds(20),
                () -> ChainViewMapper.mapStruct(chain.call(CORE, "getSystemStats"), ChainViewMapper.SYSTEM_STATS));
    }

    /** 某城市的统筹基金池余额（统筹基金属于公共基金，公开可查）。 */
    public Object getCityPool(String city) {
        String normalized = city == null ? "" : city.trim();
        return cache.getOrLoad("regulator:city-pool:" + normalized, Duration.ofSeconds(20),
                () -> chain.call(CORE, "getCityPool", city).first());
    }

    private Map<String, Object> idListView(CallResult result) {
        Map<String, Object> view = new LinkedHashMap<>();
        List<Object> values = result.getValues();
        view.put("ids", values != null && !values.isEmpty() ? values.get(0) : null);
        view.put("total", values != null && values.size() > 1 ? values.get(1) : null);
        view.put("nextOffset", values != null && values.size() > 2 ? values.get(2) : null);
        return view;
    }
}
