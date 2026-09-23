package com.example.server.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户级 B 站 Cookie 的加密存储行。
 *
 * <p>每个用户至多一条（{@code user_id} 主键），保存即覆盖。{@code cookie_encrypted} 是
 * AES-GCM 密文，明文从不进入数据库、日志或 MQ 消息。
 */
@Data
@TableName("user_bilibili_credentials")
public class UserBilibiliCredential {

    @TableId
    private Long userId;

    private String cookieEncrypted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
