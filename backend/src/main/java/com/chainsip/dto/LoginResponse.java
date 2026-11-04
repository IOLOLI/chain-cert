package com.chainsip.dto;

import lombok.Data;

import java.util.List;

@Data
public class LoginResponse {

    private String token;

    private long expiresInSeconds;

    private AccountView account;

    /** 该账号已绑定的地址（未验签的也会列出，但 verified=false） */
    private List<AccountView.WalletView> wallets;
}
