package com.example.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 保存用户 B 站 Cookie 的请求体。
 *
 * <p>只接收原始 {@code Cookie:} 头字符串（{@code SESSDATA=...; bili_jct=...}），
 * 服务端负责校验、加密与落库；明文绝不回传。
 */
public record BilibiliCookieRequest(
        @NotBlank(message = "Cookie 不能为空")
        @Size(max = 8192, message = "Cookie 过长")
        String cookie
) {
}
