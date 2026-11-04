package com.chainsip.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 账号视图。{@code onChainRole} 是"链上实际角色"——与 {@code role}（链下登记角色）可能不一致，
 * 不一致时以链上为准：链下再怎么标 ADMIN，合约不认就发不出交易。
 */
@Data
public class AccountView {

    private Long id;
    private String username;
    private String displayName;
    private String role;
    private String institutionCode;
    private String status;
    private LocalDateTime createdAt;

    /**
     * <b>账号自己的链上地址</b>：该账号 {@code institutionCode} 对应机构密钥的地址。
     *
     * <p>ADMIN / BUREAU / SPONSOR / REGULATOR / EMPLOYEE 都适用——职工账号在创建时也会
     * 自动生成一把属于自己的链上密钥（编码形如 {@code EMP_AB12CD}），因此职工同样有地址，
     * <b>不需要用户手动绑定钱包、也不需要手输地址</b>。
     *
     * <p>没有 {@code institutionCode}（历史数据）或解析不到机构密钥时返回<b>空串</b>（不是 null），
     * 保证字段始终存在，前端不必做 undefined 判断。想改用自己的浏览器钱包地址属于可选操作，
     * 见 {@link #primaryAddress}。
     */
    private String chainAddress;

    /**
     * 该账号<b>已验签的钱包主地址</b>（{@code cs_wallet} 里第一个 verified 记录），没有则为空串。
     *
     * <p>兼容字段：这是"钱包绑定"这条可选路径的地址。它一直存在于前端 store（由 wallets 推导），
     * 现在后端也显式返回一份，便于对照 {@link #chainAddress} 与钱包绑定地址。
     */
    private String primaryAddress;

    /** 链上角色（roleOf 的结果），链未就绪时为 null */
    private String onChainRole;

    private List<WalletView> wallets;

    @Data
    public static class WalletView {
        private String address;
        private Boolean verified;
        private Boolean primaryWallet;
        private LocalDateTime boundAt;
        /** 该地址在链上的角色 */
        private String onChainRole;
    }
}
