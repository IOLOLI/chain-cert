package com.chainsip.service;

import com.chainsip.chain.dto.CallResult;
import com.chainsip.chain.dto.SendResult;
import com.chainsip.common.BizException;
import com.chainsip.common.ErrorCode;
import com.chainsip.config.ChainProperties;
import com.chainsip.dto.EmployeeDetailView;
import com.chainsip.dto.EmployeeRegisterRequest;
import com.chainsip.entity.InstitutionKey;
import com.chainsip.policy.entity.PolicyContext;
import com.chainsip.policy.service.PolicyContextBuilder;
import com.chainsip.security.AuthContext;
import com.chainsip.security.AuthUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 缴费单位（SPONSOR）与职工（EMPLOYEE）侧业务：建档、减员、调薪、缴费、查询、发起转移。
 *
 * <p>这些方法全部用"当前登录账号关联的机构私钥"签名，因此：
 * 单位只能给自己名下职工缴费（合约里 {@code emp.sponsor == msg.sender}），
 * 职工只能查自己的账户与发起自己的转移申请（合约按 msg.sender 判定）。
 */
@Service
public class AccountBizService {

    private static final Logger log = LoggerFactory.getLogger(AccountBizService.class);

    private static final String CORE = ChainProperties.CORE;
    private static final String TRANSFER = ChainProperties.TRANSFER;

    /** 职工明细 limit 默认值（与契约一致）。 */
    private static final int EMPLOYEE_DETAIL_DEFAULT_LIMIT = 20;

    /** 职工明细 limit 生效上限（与契约一致）。 */
    private static final int EMPLOYEE_DETAIL_MAX_LIMIT = 50;

    /** 合约 EmployeeStatus.SUSPENDED 的编号：已停缴。 */
    private static final int STATUS_SUSPENDED = 2;

    /** 零地址：参保档案里 sponsor 为它时代表"没有所属单位"，不必去链上查名称。 */
    private static final String ZERO_ADDRESS = "0x0000000000000000000000000000000000000000";

    private final ChainTxService chain;
    private final InstitutionKeyService institutionKeyService;
    private final PolicyContextBuilder contextBuilder;

    public AccountBizService(ChainTxService chain, InstitutionKeyService institutionKeyService,
                             PolicyContextBuilder contextBuilder) {
        this.chain = chain;
        this.institutionKeyService = institutionKeyService;
        this.contextBuilder = contextBuilder;
    }

    // ------------------------------------------------------------------
    // 缴费单位
    // ------------------------------------------------------------------

    /** 单位自助为职工建档（缴费单位 = 调用者地址）。 */
    public SendResult addEmployee(EmployeeRegisterRequest request) {
        return chain.send(chain.currentInstitutionCode(), CORE, "addEmployee",
                ChainParams.address(request.getEmployee(), "职工地址"),
                request.getName(),
                ChainParams.uint(request.getAge(), "年龄"),
                request.getIdNumber(),
                request.getEmployer(),
                ChainParams.uint(request.getStartDate(), "入职日期"),
                ChainParams.uint(request.getSalary(), "月工资"));
    }

    /** 单位减员（停缴）：转移放行的前置条件。 */
    public SendResult suspendEmployee(String employee, String reason) {
        return chain.send(chain.currentInstitutionCode(), CORE, "suspendEmployee",
                ChainParams.address(employee, "职工地址"), reason == null ? "" : reason);
    }

    /** 调整职工工资并重算缴费基数。 */
    public SendResult setSalary(String employee, Object salary) {
        return chain.send(chain.currentInstitutionCode(), CORE, "setSalary",
                ChainParams.address(employee, "职工地址"), ChainParams.uint(salary, "月工资"));
    }

    /** 为职工缴纳当月费用（个人 8% + 统筹 19%，均从单位统筹账户余额扣减）。 */
    public SendResult makePayment(String employee) {
        return chain.send(chain.currentInstitutionCode(), CORE, "makePayment",
                ChainParams.address(employee, "职工地址"));
    }

    /**
     * 缴费试算（只读，不发交易）：给前端"提交前预览"用。
     *
     * <p>返回基数夹取结果与按政策参数拆出的个人 / 统筹金额，并带上费率版本摘要——
     * 实际扣款仍以链上 {@code makePayment} 为准（合约按自己的常量计算）。
     * 参数与合约常量不一致时，这里算出的数会和链上不同：一期靠一致性巡检告警，二期合并口径。
     */
    public Map<String, Object> trialPayment(String employee) {
        String address = ChainParams.address(employee, "职工地址");
        Map<String, Object> account = readInsuranceAccountView(address);
        PolicyContext ctx = contextBuilder.build(textOrEmpty(account.get("city")), LocalDate.now());

        BigDecimal rawBase = decimalOf(account.get("paymentBase"));
        BigDecimal effectiveBase = rawBase
                .max(ctx.getBaseFloorValue())
                .min(ctx.getBaseCeilingValue());

        BigDecimal personal = effectiveBase.multiply(ctx.getEmployeeContribRate())
                .setScale(0, RoundingMode.HALF_UP);
        BigDecimal overall = effectiveBase.multiply(ctx.getEmployerContribRate())
                .setScale(0, RoundingMode.HALF_UP);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("employee", address);
        out.put("name", textOrEmpty(account.get("name")));
        out.put("city", textOrEmpty(account.get("city")));
        out.put("paymentBase", rawBase.toPlainString());
        out.put("effectiveBase", effectiveBase.toPlainString());
        out.put("baseFloorValue", ctx.getBaseFloorValue().toPlainString());
        out.put("baseCeilingValue", ctx.getBaseCeilingValue().toPlainString());
        out.put("baseClamped", effectiveBase.compareTo(rawBase) != 0);
        out.put("employeeContribRate", ctx.getEmployeeContribRate().toPlainString());
        out.put("employerContribRate", ctx.getEmployerContribRate().toPlainString());
        out.put("personalAmount", personal.toPlainString());
        out.put("overallAmount", overall.toPlainString());
        out.put("totalAmount", personal.add(overall).toPlainString());
        out.put("policyVersionRef", contextBuilder.versionRef(
                textOrEmpty(account.get("city")), LocalDate.now()));
        out.put("note", "试算按链下政策参数计算；实际扣款以链上 makePayment 的合约常量为准");
        return out;
    }

    /** 数值兜底：null / 空串 → 0（链上 wei 金额用 BigDecimal 承载，避免溢出）。 */
    private static BigDecimal decimalOf(Object value) {
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof Number) {
            return new BigDecimal(String.valueOf(value));
        }
        String text = value == null ? "" : String.valueOf(value).trim();
        if (text.isEmpty()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    /** 查本公司职工地址列表（分页）。 */
    public Map<String, Object> getPersonList(String sponsor, int offset, int limit) {
        CallResult result = chain.call(CORE, "getPersonList",
                ChainParams.address(sponsor, "公司地址"), ChainParams.uint(offset, "offset"),
                ChainParams.uint(limit, "limit"));
        return listView(result, "list");
    }

    /** 查公司账户（余额、累计已缴、职工数）。 */
    public Map<String, Object> getSponsorAccount(String sponsor) {
        return ChainViewMapper.map(chain.call(CORE, "getSponsorAccount",
                ChainParams.address(sponsor, "公司地址")), ChainViewMapper.SPONSOR_ACCOUNT);
    }

    /**
     * 本单位职工明细（含姓名）：先取本单位名下的职工地址列表，再逐个读链上参保账户补姓名与状态。
     *
     * <p>{@code sponsor} 参数由<b>当前登录账号自己</b>的链上地址决定
     * （{@code AuthContext.institutionCode} → {@link InstitutionKeyService} → address），
     * 因此调用方不需要、也无法指定别家单位；账号没有链上地址时返回空数组（HTTP 200，不是 500）。
     *
     * <p><b>性能</b>：合约没有批量 getter（没有一次性返回「地址 + 姓名 + 状态」列表的 view 方法），
     * 所以只能对分页后的地址列表<b>逐个</b>调用 {@code getInsuranceAccount}，复杂度 O(n)；
     * 这也是 limit 封顶 {@value #EMPLOYEE_DETAIL_MAX_LIMIT} 的原因（默认 {@value #EMPLOYEE_DETAIL_DEFAULT_LIMIT}）。
     * {@code offset} 默认 0，是在<b>地址列表上</b>分页之后再逐个查。
     *
     * <p><b>容错</b>：
     * <ul>
     *   <li>账号没有链上地址 → 空数组；</li>
     *   <li>链未就绪 / 合约地址未配置 / 名单读取本身失败 → 空数组 + HTTP 200，不抛成 5xx；</li>
     *   <li>某个职工单独读取失败 → 只把该行标成 {@code status=-1 / statusName=读取失败}，不影响其它行。</li>
     * </ul>
     */
    public List<EmployeeDetailView> getEmployeeDetails(Integer offset, Integer limit) {
        List<EmployeeDetailView> rows = new ArrayList<>();
        String sponsor = currentChainAddress();
        if (sponsor == null) {
            log.debug("当前登录账号没有可用的链上地址，职工明细返回空数组");
            return rows;
        }

        int from = offset == null || offset < 0 ? 0 : offset;
        int size = limit == null || limit <= 0 ? EMPLOYEE_DETAIL_DEFAULT_LIMIT
                : Math.min(limit, EMPLOYEE_DETAIL_MAX_LIMIT);

        List<String> addresses;
        try {
            addresses = employeeAddresses(sponsor, from, size);
        } catch (BizException e) {
            if (isChainUnavailable(e.getErrorCode())) {
                log.warn("读取本单位职工地址列表失败，职工明细返回空数组：sponsor={}, 原因={}",
                        sponsor, e.getMessage());
                return rows;
            }
            throw e;
        }

        log.debug("职工明细：sponsor={}, offset={}, 生效 limit={}（上限 {}），地址条数={}",
                sponsor, from, size, EMPLOYEE_DETAIL_MAX_LIMIT, addresses.size());

        // 链上历史数据可能因旧版合约把同一人重复放进名单：这里按地址去重，页面不出现两条同一职工。
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (String address : addresses) {
            String key = address == null ? "" : address.trim().toLowerCase();
            if (key.isEmpty() || !seen.add(key)) {
                continue;
            }
            rows.add(loadEmployeeDetail(address));
        }
        log.debug("职工明细：sponsor={} 实际返回条数={}", sponsor, rows.size());
        return rows;
    }

    /** 当前登录账号自己的链上地址；没有 institutionCode、查不到机构密钥、地址不合法时都返回 null。 */
    private String currentChainAddress() {
        try {
            AuthUser user = AuthContext.get();
            if (user == null) {
                return null;
            }
            String code = user.getInstitutionCode();
            if (code == null || code.trim().isEmpty()) {
                log.debug("当前登录账号未关联机构链账户（institutionCode 为空），职工明细返回空数组");
                return null;
            }
            InstitutionKey key = institutionKeyService.findByCode(code.trim());
            String address = key == null || key.getAddress() == null ? null : key.getAddress().trim();
            if (address == null || address.isEmpty()) {
                log.debug("机构编码 {} 没有可用的链上地址，职工明细返回空数组", code);
                return null;
            }
            if (!address.startsWith("0x") || address.length() != 42) {
                log.warn("机构编码 {} 的链上地址格式不合法（{}），职工明细返回空数组", code, address);
                return null;
            }
            return address;
        } catch (RuntimeException e) {
            log.warn("解析当前登录账号的链上地址失败，职工明细返回空数组：{}", e.getMessage());
            return null;
        }
    }

    /** 本单位名下的职工地址（沿用现有 {@code getPersonList} 的链上读法，在地址列表上分页）。 */
    @SuppressWarnings("unchecked")
    private List<String> employeeAddresses(String sponsor, int offset, int limit) {
        Map<String, Object> page = getPersonList(sponsor, offset, limit);
        List<String> addresses = new ArrayList<>();
        Object list = page.get("list");
        if (!(list instanceof List)) {
            return addresses;
        }
        for (Object item : (List<Object>) list) {
            if (item != null) {
                addresses.add(String.valueOf(item));
            }
        }
        return addresses;
    }

    /** 单个职工一行明细：读失败只降级这一行，绝不让整次请求失败。 */
    private EmployeeDetailView loadEmployeeDetail(String address) {
        EmployeeDetailView row = new EmployeeDetailView();
        row.setAddress(address);
        // 先铺一层兜底值（数值 0 / 字符串空串）：读失败时也是这套值，前端不必判空
        row.setName("");
        row.setIdNumber("");
        row.setCity("");
        row.setSalary(0);
        row.setPaymentBase(0);
        row.setPersonalBalance(0);
        row.setOverallBalance(0);
        row.setPaidMonths(0);
        row.setOverdueCount(0);
        row.setLastPaymentAt(0);
        row.setLastPaidAt(0);
        row.setSuspended(Boolean.FALSE);
        try {
            Map<String, Object> account = readInsuranceAccountView(address);
            Object status = account.get("status");
            Integer statusCode = intValue(status);
            String statusName = ChainViewMapper.employeeStatusNameCn(status);
            // lastPaymentAt 与 lastPaidAt 是同一个值（后者是兼容别名）
            Object lastPaymentAt = numberOrZero(account.get("lastPaymentAt"));
            row.setName(textOrEmpty(account.get("name")));
            row.setIdNumber(textOrEmpty(account.get("idNumber")));
            row.setCity(textOrEmpty(account.get("city")));
            row.setSalary(numberOrZero(account.get("salary")));
            row.setPaymentBase(numberOrZero(account.get("paymentBase")));
            row.setPersonalBalance(numberOrZero(account.get("personalBalance")));
            row.setOverallBalance(numberOrZero(account.get("overallBalance")));
            row.setPaidMonths(numberOrZero(account.get("paidMonths")));
            row.setOverdueCount(numberOrZero(account.get("overdueCount")));
            row.setLastPaymentAt(lastPaymentAt);
            row.setLastPaidAt(lastPaymentAt);
            row.setStatus(statusCode);
            row.setStatusName(statusName == null ? "" : statusName);
            row.setSuspended(Integer.valueOf(STATUS_SUSPENDED).equals(statusCode));
        } catch (RuntimeException e) {
            row.setName("");
            row.setStatus(-1);
            row.setStatusName("读取失败");
            row.setSuspended(Boolean.FALSE);
            log.warn("读取职工参保账户失败（该行按读取失败返回，不影响其它行）：employee={} 原因={}",
                    address, e.getMessage());
        }
        return row;
    }

    /**
     * 读一个职工的参保账户并按 {@link ChainViewMapper#INSURANCE_ACCOUNT} 的字段名映射。
     *
     * <p>这里刻意不用 {@link #getInsuranceAccount(String)}：合约 {@code getInsuranceAccount} 的 ABI
     * 把整个结构体作为<b>唯一一个 tuple 输出</b>，SDK 解出来的 {@code values} 是「只装了这一个结构体的列表」，
     * 走扁平的 {@link ChainViewMapper#map} 会把整行塞进第一个字段名（{@code employee}）。
     * {@link ChainViewMapper#mapStruct} 会先拆掉这一层，形状正确后再按字段名映射。
     */
    private Map<String, Object> readInsuranceAccountView(String employee) {
        return ChainViewMapper.mapStruct(chain.call(CORE, "getInsuranceAccount",
                ChainParams.address(employee, "职工地址")), ChainViewMapper.INSURANCE_ACCOUNT);
    }

    /** 链未就绪 / 合约未配置 / 链上调用失败：这三类都按「返回空数组」处理，不抛给 HTTP 层。 */
    private boolean isChainUnavailable(ErrorCode code) {
        return code == ErrorCode.CHAIN_NOT_READY
                || code == ErrorCode.CONTRACT_NOT_DEPLOYED
                || code == ErrorCode.CHAIN_CALL_ERROR;
    }

    /** 字符串兜底：null → 空串（职工明细里所有文本字段都走它）。 */
    private String textOrEmpty(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** 数值兜底：null → 0（职工明细里所有数值字段都走它，前端不必判空）。 */
    private Object numberOrZero(Object value) {
        return value == null ? 0 : value;
    }

    /**
     * 在参保账户视图上补一个 {@code sponsorName}（所属缴费单位名称）。
     *
     * <p>取值链路：账户里的 {@code sponsor} 地址 → {@code getSponsorAccount(sponsor).name}。
     * 读不到（未参保 / 单位未注册 / 链不通 / 合约拒绝）一律给<b>空串</b>，绝不让整个账户查询报错，
     * 因此前端可以"优先显示 sponsorName、缺失时回退到 sponsor 地址"。
     *
     * <p><b>必须用 ADMIN 身份读</b>：合约 {@code getSponsorAccount} 只允许「单位自己 / ADMIN / REGULATOR」
     * 读，职工本人读所属单位会被 {@code SponsorNotVisible} 回滚，所以走 {@link ChainTxService#callAs}。
     */
    private String sponsorNameOf(Map<String, Object> accountView) {
        Object sponsor = accountView.get("sponsor");
        String address = sponsor == null ? null : String.valueOf(sponsor).trim();
        if (address == null || address.length() != 42 || !address.startsWith("0x")
                || address.equalsIgnoreCase(ZERO_ADDRESS)) {
            return "";
        }
        try {
            Map<String, Object> sponsorAccount = ChainViewMapper.mapStruct(
                    chain.callAs(AuthService.ROLE_ADMIN, CORE, "getSponsorAccount", address),
                    ChainViewMapper.SPONSOR_ACCOUNT);
            return textOrEmpty(sponsorAccount.get("name"));
        } catch (RuntimeException e) {
            log.warn("读取缴费单位 {} 的名称失败（sponsorName 按空串返回）：{}", address, e.getMessage());
            return "";
        }
    }

    private Integer intValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 职工
    // ------------------------------------------------------------------

    /**
     * 查参保账户（余额、缴费基数、状态等）。
     *
     * <p><b>必须用 {@link ChainViewMapper#mapStruct}</b>：合约 {@code getInsuranceAccount} 的 ABI 把整个
     * 结构体作为唯一一个 tuple 输出，SDK 解出来的 {@code values} 是「只装了这一个结构体的列表」，
     * 用扁平的 {@link ChainViewMapper#map} 会把整行塞进第一个字段名 {@code employee}，
     * 于是 {@code data.name} / {@code data.idNumber} / {@code data.salary} 等字段全部取不到值
     * （前端 {@code employee/Account.vue} 正是这么读的）。
     */
    public Map<String, Object> getInsuranceAccount(String employee) {
        Map<String, Object> view = ChainViewMapper.mapStruct(chain.call(CORE, "getInsuranceAccount",
                ChainParams.address(employee, "职工地址")), ChainViewMapper.INSURANCE_ACCOUNT);
        // 只加不删：既有字段（sponsor 地址 / city / paymentBase…）原样保留，额外补一个所属单位名称
        view.put("sponsorName", sponsorNameOf(view));
        return view;
    }

    /**
     * 查缴费记录（分页）。
     *
     * <p>合约 {@code getPaymentRecords} 返回的是 {@code records: tuple[]}（<b>结构体数组</b>），
     * 与 {@code getInsuranceAccount} 那种"单个 tuple 输出"形状不同：SDK 解出来的
     * {@code values} 就是 {@code [ [记录1的字段...], [记录2的字段...] ], total, nextOffset }，
     * 因此这里是"按 {@link ChainViewMapper#PAYMENT_RECORD} 的下标<b>逐条</b>拆"，
     * 不能整体只解一层。字段顺序与 ABI 的 components 完全一致：
     * {@code timestamp / paymentBase / personalAmount / overallAmount / sponsor / overdue}。
     */
    public Map<String, Object> getPaymentRecords(String employee, int offset, int limit) {
        CallResult result = chain.call(CORE, "getPaymentRecords",
                ChainParams.address(employee, "职工地址"), ChainParams.uint(offset, "offset"),
                ChainParams.uint(limit, "limit"));
        Map<String, Object> view = new LinkedHashMap<>();
        List<Map<String, Object>> records = new ArrayList<>();
        List<Object> values = result.getValues();
        if (values != null && !values.isEmpty() && values.get(0) instanceof List) {
            for (Object item : (List<?>) values.get(0)) {
                Map<String, Object> record = new LinkedHashMap<>();
                if (item instanceof List) {
                    List<?> fields = (List<?>) item;
                    for (int i = 0; i < ChainViewMapper.PAYMENT_RECORD.length && i < fields.size(); i++) {
                        record.put(ChainViewMapper.PAYMENT_RECORD[i], fields.get(i));
                    }
                } else {
                    record.put("value", item);
                }
                records.add(record);
            }
        }
        view.put("records", records);
        view.put("total", values != null && values.size() > 1 ? values.get(1) : null);
        view.put("nextOffset", values != null && values.size() > 2 ? values.get(2) : null);
        // 与其它映射过的接口保持一致：原样带一份链上原始返回值，便于前端/现场排查
        if (result.getRawValues() != null) {
            view.put("_raw", result.getRawValues());
        }
        return view;
    }

    /** 查职工转移状态（状态 / 在途转移编号 / 参保城市）。 */
    public Map<String, Object> getTransferState(String employee) {
        Map<String, Object> view = ChainViewMapper.map(chain.call(CORE, "getTransferState",
                ChainParams.address(employee, "职工地址")), ChainViewMapper.TRANSFER_STATE);
        Object status = view.get("status");
        view.put("statusName", ChainViewMapper.employeeStatusName(status));
        return view;
    }

    /** 查转移在途托管金额。 */
    public Map<String, Object> getEscrow(String employee) {
        return ChainViewMapper.map(chain.call(CORE, "getEscrow",
                ChainParams.address(employee, "职工地址")), ChainViewMapper.ESCROW);
    }

    /** 职工本人发起跨城市转移申请（转入地由目标城市的社保局绑定关系推导）。 */
    public SendResult applyForTransfer(String toCity) {
        return chain.send(chain.currentInstitutionCode(), TRANSFER, "applyForTransfer", toCity);
    }

    private Map<String, Object> listView(CallResult result, String fieldName) {
        Map<String, Object> view = new LinkedHashMap<>();
        List<Object> values = result.getValues();
        view.put(fieldName, values != null && !values.isEmpty() ? values.get(0) : null);
        view.put("total", values != null && values.size() > 1 ? values.get(1) : null);
        view.put("nextOffset", values != null && values.size() > 2 ? values.get(2) : null);
        return view;
    }
}
