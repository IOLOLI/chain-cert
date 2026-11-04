package com.chainsip.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.util.List;

/**
 * 各类小请求体的集合（用嵌套类组织，避免几十个一行文件）。
 */
public final class Requests {

    private Requests() {
    }

    @Data
    public static class AddressOnly {
        @NotBlank(message = "地址不能为空")
        private String address;
    }

    /**
     * 自助修改密码（登录用户改自己的密码）。
     *
     * <p>这里只用 {@code @NotBlank} 兜"没传"的情况；长度、与原密码是否相同等业务规则
     * 统一在 {@code AuthService.changePassword} 里判，好让错误 message 保持简洁可控
     * （不带上字段名前缀）。
     */
    @Data
    public static class ChangePassword {
        @NotBlank(message = "原密码不能为空")
        private String oldPassword;
        @NotBlank(message = "新密码不能为空")
        private String newPassword;
    }

    /**
     * 自助修改账号信息（登录用户改自己的用户名 / 显示名）。
     *
     * <p>只接受"改什么"，不接受"改谁"：账号由登录态（JWT）决定，请求体里没有账号标识。
     * 两个新字段留空即表示不改；无论改哪一项，都必须提供<b>当前密码</b>。
     */
    @Data
    public static class ChangeProfile {
        /** 新用户名（可选；留空 = 不改）。长度 3-32，只允许字母 / 数字 / 下划线。 */
        private String newUsername;
        /** 新显示名（可选；留空 = 不改）。 */
        private String newDisplayName;
        @NotBlank(message = "当前密码不能为空")
        private String password;
    }

    /** 注册缴费单位 */
    @Data
    public static class SponsorRegister {
        @NotBlank(message = "公司地址不能为空")
        private String sponsor;
        @NotBlank(message = "公司名称不能为空")
        private String name;
        @NotBlank(message = "所在城市不能为空")
        private String city;
    }

    /** 给公司统筹账户注资 */
    @Data
    public static class Deposit {
        @NotBlank(message = "公司地址不能为空")
        private String sponsor;
        @NotBlank(message = "注资金额不能为空")
        private String amount;
    }

    /** 注册社保局 */
    @Data
    public static class BureauRegister {
        @NotBlank(message = "社保局地址不能为空")
        private String bureau;
        @NotBlank(message = "社保局名称不能为空")
        private String name;
        @NotBlank(message = "服务城市不能为空")
        private String city;
    }

    /** 切换社保局授权 */
    @Data
    public static class BureauAuthorize {
        @NotBlank(message = "社保局地址不能为空")
        private String bureau;
        @NotNull(message = "授权状态不能为空")
        private Boolean authorized;
    }

    /** 注册监管账户 */
    @Data
    public static class RegulatorRegister {
        @NotBlank(message = "监管地址不能为空")
        private String regulator;
        @NotBlank(message = "监管人/岗位不能为空")
        private String name;
        private String agency;
    }

    /** 调整工资 */
    @Data
    public static class Salary {
        @NotBlank(message = "职工地址不能为空")
        private String employee;
        @NotBlank(message = "月工资不能为空")
        private String salary;
    }

    /** 减员 */
    @Data
    public static class Suspend {
        @NotBlank(message = "职工地址不能为空")
        private String employee;
        private String reason;
    }

    /** 发起转移申请 */
    @Data
    public static class TransferApply {
        @NotBlank(message = "目标城市不能为空")
        private String toCity;

        public String getToCity() {
            return toCity;
        }
    }

    /** 放行 / 驳回转移申请 */
    @Data
    public static class TransferAction {
        @NotNull(message = "申请编号不能为空")
        private Long id;
        @NotNull(message = "是否通过不能为空")
        private Boolean approved;
        private String reason;
    }

    /** 平台账号开通（仅 ADMIN） */
    @Data
    public static class AccountCreate {
        @NotBlank(message = "用户名不能为空")
        private String username;
        @NotBlank(message = "密码不能为空")
        private String password;
        @NotBlank(message = "角色不能为空")
        private String role;
        private String displayName;
        /** 关联的机构链账户编码，决定该账号发交易时用哪把私钥 */
        private String institutionCode;
        /**
         * 链上注册用的机构名称（可选，不破坏既有调用）：BUREAU / SPONSOR / REGULATOR 用。
         * 留空时回退到 displayName；REGULATOR 的 agency 也用它。
         */
        private String name;
        /**
         * 链上注册用的城市（可选）：BUREAU / SPONSOR 必填，缺省则跳过自动注册、
         * 该机构落到「待上链注册」列表，由管理员后续补 city 再注册。
         */
        private String city;
    }

    /** 「待上链注册」逐条注册的请求体：只允许补一个城市 */
    @Data
    public static class InstitutionRegister {
        /** 城市：BUREAU / SPONSOR 必填；REGULATOR 可空 */
        private String city;
    }

    /**
     * 社保局创建缴费单位账号（BUREAU → SPONSOR）。
     *
     * <p>字段名与前端契约为准；{@code name} 是给链上注册（addSponsorAccount）用的，省略时取 displayName。
     *
     * <p><b>{@code city} 是可选的</b>：所在城市一律由服务端按链上该社保局的服务城市自动决定——
     * 社保局只有 1 个服务城市时直接用那一个（请求里传了别的城市也会被覆盖）；
     * 服务城市多于 1 个时这里才必须显式指定，且必须属于该社保局的服务城市；
     * 服务城市为空（未注册/未授权）或链读不通时，不带 city 会返回 400 并说明原因。
     * 前端可用 {@code GET /api/bureau/cities} 拉本局服务城市做下拉。
     */
    @Data
    public static class BureauAccountCreate {
        @NotBlank(message = "用户名不能为空")
        private String username;
        @NotBlank(message = "密码不能为空")
        private String password;
        private String displayName;
        /** 缴费单位名称（链上注册用；省略时取 displayName） */
        private String name;
        /**
         * 所在城市（可选）：默认自动取该社保局的服务城市；
         * 仅当该社保局有多个服务城市时必须显式指定，且必须是其中之一。
         */
        private String city;
        /** 省略（或不传）时自动生成 SPONSOR_<6位随机大写十六进制> 并随机生成一把机构私钥 */
        private String institutionCode;
    }

    /**
     * 缴费单位创建职工账号（SPONSOR → EMPLOYEE）。
     *
     * <p>刻意不提供 institutionCode 字段：职工的链上地址由服务端在开号时自动生成
     * （编码形如 {@code EMP_AB12CD}），调用方既不需要、也不允许指定——避免职工复用某个机构的
     * 链上身份。职工拿到该地址后，单位用它在链上 {@code addEmployee} 建档即可。
     */
    @Data
    public static class SponsorAccountCreate {
        @NotBlank(message = "用户名不能为空")
        private String username;
        @NotBlank(message = "密码不能为空")
        private String password;
        private String displayName;
    }

    /** 登记机构链账户 */
    @Data
    public static class InstitutionKeyCreate {
        @NotBlank(message = "机构编码不能为空")
        private String code;
        @NotBlank(message = "机构名称不能为空")
        private String name;
        @NotBlank(message = "角色不能为空")
        private String role;
        /** 留空则由服务端随机生成一把新私钥 */
        private String privateKeyHex;
        private String remark;
    }

    /** 通用链上调用 */
    @Data
    public static class ChainInvoke {
        @NotBlank(message = "合约名不能为空")
        private String contract;
        @NotBlank(message = "函数名不能为空")
        private String function;
        private List<Object> params;
    }

    /** 演示辅助：用指定私钥对消息签名（仅本地开发） */
    @Data
    public static class DemoSign {
        @NotBlank(message = "私钥不能为空")
        private String privateKey;
        @NotBlank(message = "消息不能为空")
        private String message;
    }

    /**
     * 办理退休。
     *
     * <p>{@code force} 留空 / false = 常规办理（须满 60 岁且累计缴费 180 个月）；
     * true = 特殊审批（病退 / 提前退休），跳过门槛并在链上留痕。
     */
    @Data
    public static class PensionRetire {
        @NotBlank(message = "职工地址不能为空")
        private String employee;
        private Boolean force;
    }

    /** 发放一个月养老金（链上 claimPension） */
    @Data
    public static class PensionClaim {
        @NotBlank(message = "职工地址不能为空")
        private String employee;
    }
}
