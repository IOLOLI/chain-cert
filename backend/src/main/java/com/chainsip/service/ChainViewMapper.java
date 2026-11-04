package com.chainsip.service;

import com.chainsip.chain.dto.CallResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把合约返回的"结构体（ABI 解码后是无名列表）"翻译成带字段名的 Map，前端直接可用。
 *
 * <p>字段顺序必须与合约里的结构体定义一致；将来合约调整字段，改这里一处即可。
 */
public final class ChainViewMapper {

    private ChainViewMapper() {
    }

    public static Map<String, Object> map(CallResult result, String[] fields) {
        Map<String, Object> view = new LinkedHashMap<>();
        List<Object> values = result == null ? null : result.getValues();
        if (values == null) {
            return view;
        }
        for (int i = 0; i < fields.length && i < values.size(); i++) {
            view.put(fields[i], values.get(i));
        }
        if (result.getRawValues() != null) {
            view.put("_raw", result.getRawValues());
        }
        return view;
    }

    /** InsuranceAccountView */
    public static final String[] INSURANCE_ACCOUNT = {
            "employee", "name", "idNumber", "employer", "city", "sponsor", "status", "salary",
            "paymentBase", "personalBalance", "overallBalance", "totalBalance", "paidMonths",
            "overdueCount", "registeredAt", "lastPaymentAt", "activeTransferId",
            // 养老金（支出侧）：未退休时 retiredAt=0、金额为 0
            "retiredAt", "lastPensionAt", "pensionPersonalMonthly", "pensionClaimedMonths", "age"
    };

    /**
     * 与 {@link #map(CallResult, String[])} 同义，但额外兼容「整个结构体是唯一一个 tuple 输出」的函数。
     *
     * <p>合约里返回结构体的 view 有两类 ABI 形状，SDK 解出来的 {@code values} 因此不同：
     * <ul>
     *   <li>字段被摊平成多个输出（如 {@code getSponsorAccount}、{@code getTransferState}）→
     *       {@code values} 就是扁平列表，两种映射结果一致；</li>
     *   <li>写成 1 个 tuple 输出（如 {@code getInsuranceAccount}、{@code getSystemStats}、
     *       {@code getTransfer}；ABI 里 outputs 只有一项 tuple）→ {@code values} 是「只装了这
     *       一个结构体的列表」，必须先用本方法拆一层，否则整行会被塞进第一个字段名下，
     *       其余字段名全部取不到值。</li>
     * </ul>
     *
     * <p>因此：<b>凡是「单个 tuple 输出」的 view 一律走本方法</b>（{@link #map} 只用于字段已摊平的函数）。
     * 本方法只在「恰好 1 个值、且它本身是列表」时拆一层，其它情况与 {@link #map} 完全一致，
     * 对已摊平的函数没有任何影响。两者的返回体都保留 {@code _raw}（前端排障用）。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> mapStruct(CallResult result, String[] fields) {
        List<Object> values = result == null ? null : result.getValues();
        if (values == null || values.size() != 1 || !(values.get(0) instanceof List)) {
            return map(result, fields);
        }
        Map<String, Object> view = new LinkedHashMap<>();
        List<Object> row = (List<Object>) values.get(0);
        for (int i = 0; i < fields.length && i < row.size(); i++) {
            view.put(fields[i], row.get(i));
        }
        if (result.getRawValues() != null) {
            view.put("_raw", result.getRawValues());
        }
        return view;
    }

    /** SystemStats */
    public static final String[] SYSTEM_STATS = {
            "deposited", "sponsorPoolTotal", "paid", "personalFund", "overallRights",
            "cityPoolTotal", "escrowedPersonal", "escrowedOverall"
    };

    /** getSponsorAccount */
    public static final String[] SPONSOR_ACCOUNT = {
            "name", "city", "registeredAt", "poolBalance", "paidTotal", "employeeCount"
    };

    /** TransferApplication */
    public static final String[] TRANSFER_APPLICATION = {
            "id", "employee", "fromCity", "toCity", "fromBureau", "toBureau", "status",
            "personalFund", "overallFund", "paidMonths", "appliedAt", "authorizedAt",
            "receivedAt", "reason"
    };

    /** getTransferState */
    public static final String[] TRANSFER_STATE = {"status", "activeTransferRef", "city"};

    /** getEscrow */
    public static final String[] ESCROW = {"personalFund", "overallFund", "paidMonths"};

    /** PaymentRecord */
    public static final String[] PAYMENT_RECORD = {
            "timestamp", "paymentBase", "personalAmount", "overallAmount", "sponsor", "overdue"
    };

    /** Bureau */
    public static final String[] BUREAU = {"name", "authorized", "registeredAt", "cities"};

    /** Regulator */
    public static final String[] REGULATOR = {"name", "agency", "registeredAt"};

    /** getRegulatorDashboard 前 7 个返回值（第 8 个是 SystemStats 结构体） */
    public static final String[] DASHBOARD_HEAD = {
            "systemName", "version", "adminCount", "sponsorCount", "bureauCount", "regulatorCount", "transferCount"
    };

    /** 员工状态枚举编号 → 文案（与合约 EmployeeStatus 一致） */
    public static String employeeStatusName(Object value) {
        return enumName(value, new String[] {"NONE", "ACTIVE", "SUSPENDED", "TRANSFERRED", "REMOVED", "RETIRED"});
    }

    /**
     * 员工状态枚举编号 → 中文文案（序号与合约 EmployeeStatus 一致：
     * 0 NONE / 1 ACTIVE / 2 SUSPENDED / 3 TRANSFERRED / 4 REMOVED / 5 RETIRED）。
     *
     * <p>与 {@link #employeeStatusName(Object)} 并存：后者是英文枚举名（历史接口在用），
     * 中文文案供「职工明细」下拉列表直接展示。
     */
    public static String employeeStatusNameCn(Object value) {
        return enumName(value, new String[] {"未参保", "正常参保", "已停缴", "已转出", "已注销", "已退休"});
    }

    /** 转移状态枚举编号 → 文案（与合约 TransferStatus 一致） */
    public static String transferStatusName(Object value) {
        return enumName(value, new String[] {"NONE", "PENDING", "AUTHORIZED", "RECEIVED", "REJECTED"});
    }

    private static String enumName(Object value, String[] names) {
        if (value instanceof Number) {
            int index = ((Number) value).intValue();
            if (index >= 0 && index < names.length) {
                return names[index];
            }
        }
        return value == null ? null : String.valueOf(value);
    }
}
