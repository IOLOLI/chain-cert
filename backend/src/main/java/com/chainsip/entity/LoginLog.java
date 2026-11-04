package com.chainsip.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 登录日志（链下）。认证在链下，登录成功/失败都需要留痕。
 */
@Data
@TableName("cs_login_log")
public class LoginLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long accountId;

    private String username;

    private String address;

    private String clientIp;

    private String userAgent;

    private Boolean success;

    private String message;

    private LocalDateTime createdAt;
}
