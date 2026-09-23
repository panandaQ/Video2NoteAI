package com.example.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建导入任务的请求体。
 *
 * <p>只接收原始 URL 与可选清晰度：客户端不解析 BV、av、分 P 或合集身份，服务端 Adapter 是唯一身份真源。
 * {@code quality} 为高度像素白名单（360/480/720/1080），可空表示沿用默认；非法值由服务层拒绝。
 */
public record VideoImportRequest(
        @NotBlank(message = "视频链接不能为空")
        @Size(max = 2048, message = "视频链接不能超过 2048 个字符")
        String url,

        Integer quality
) {
}
