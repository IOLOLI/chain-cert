package com.chainsip.dto;

import lombok.Data;

/**
 * 缴费单位视角的「职工明细」：地址 + 姓名 + 参保状态 + 账户余额等，供前端在缴费管理页
 * 按姓名下拉选人、并直接渲染职工列表表格。
 *
 * <p>与 {@code GET /api/sponsor/employees}（只返回地址列表）的区别：这里逐行带上职工姓名与账户数据，
 * 因此单位用户不需要再手输 0x 地址。
 *
 * <p>{@code address} 仍是缴费接口（{@code POST /api/sponsor/payments}）接受的参数，
 * 下拉选人后传这个地址即可，缴费接口本身没有改成收姓名。
 *
 * <p><b>缺字段兜底</b>：所有数值字段缺失时返回 {@code 0}、字符串字段缺失时返回空串，
 * 前端不必判空；该行整体读取失败时 {@code status=-1} / {@code statusName=读取失败}，其余字段仍是这套默认值。
 */
@Data
public class EmployeeDetailView {

    /** 职工链上地址（下拉项的 value）。 */
    private String address;

    /** 职工姓名（链上 InsuranceAccount.name）；该行读取失败或缺失时为空串。 */
    private String name;

    /**
     * 身份证号。
     *
     * <p>保持与项目既有查询接口一致：{@code GET /api/sponsor/employees/account} 与
     * {@code GET /api/employee/account} 已经把 {@code getInsuranceAccount} 的 {@code idNumber}
     * 原样返回，本接口同样原样返回，不另做掩码。
     */
    private String idNumber;

    /**
     * 参保状态编号（合约 EmployeeStatus）：0 未参保 / 1 正常参保 / 2 已停缴 / 3 已转出 / 4 已注销 / 5 已退休；
     * 该行读取失败时为 -1。
     */
    private Integer status;

    /** 参保状态中文文案（正常参保 / 已停缴 / 已转出 / 已注销 / 已退休 / 未参保）；该行读取失败时为「读取失败」。 */
    private String statusName;

    /** 参保城市；缺失时为空串。 */
    private String city;

    /** 月工资（链上 uint256，原样返回数字）；缺失时为 0。 */
    private Object salary;

    /** 缴费基数（链上 uint256，原样返回数字）；缺失时为 0。 */
    private Object paymentBase;

    /** 个人账户余额；缺失时为 0。 */
    private Object personalBalance;

    /** 统筹账户权益合计；缺失时为 0。 */
    private Object overallBalance;

    /** 累计缴费月数；缺失时为 0。 */
    private Object paidMonths;

    /** 逾期缴费次数；缺失时为 0。 */
    private Object overdueCount;

    /**
     * 最近一次缴费时间（链上 {@code lastPaymentAt}，毫秒时间戳）；缺失时为 0。
     *
     * <p>与 {@link #lastPaidAt} 同值：{@link #lastPaidAt} 是本接口早期的字段名，现统一叫
     * {@code lastPaymentAt}（与链上字段名一致），{@code lastPaidAt} 作为<b>兼容别名</b>保留。
     */
    private Object lastPaymentAt;

    /** {@link #lastPaymentAt} 的兼容别名（两者始终同值）。 */
    private Object lastPaidAt;

    /** 是否已停缴，等价于 {@code status == 2}。 */
    private Boolean suspended;
}
