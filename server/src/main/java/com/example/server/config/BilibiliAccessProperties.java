package com.example.server.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * B 站登录态配置（计划 §4.1/§4.2）。
 *
 * <p>默认空 = 匿名模式，全链路行为与现状一致。Cookie 只经文件进入系统：路径配置但不可读时
 * 不阻止启动，运行时按匿名模式执行并记录 {@code cookieAvailable=false}；日志不输出路径、
 * Cookie 键名或键值（契约 §11 红线）。
 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "video.import.bilibili")
public class BilibiliAccessProperties {

    /** Netscape cookies.txt 文件路径；空 = 匿名模式。 */
    private String cookieFile = "";

    /**
     * 用户级 Cookie 的 AES-GCM 加密密钥（base64 编码的 32 字节）。
     *
     * <p>空 = 用户级 Cookie 保存功能不可用：保存返回明确错误、读取返回空，全链路回退到
     * {@link #cookieFile} 或匿名模式，不阻断启动。密钥只经环境变量进入，绝不落入配置仓库或日志。
     */
    private String cookieKey = "";

    /**
     * 允许携带 Cookie 的 B 站 API Host 白名单（逗号分隔）。
     *
     * <p>字幕 CDN（aisubtitle.hdslb.com）、封面（i0.hdslb.com 等）与任意重定向目标
     * 一律不携带 Cookie（交付约束 7）。
     */
    @NotBlank
    private String cookieAllowedHosts = "api.bilibili.com";

    /**
     * 允许下载的字幕 CDN Host 白名单（逗号分隔）。
     *
     * <p>字幕 CDN、封面、任意重定向目标不携带 Cookie（交付约束 7）；本白名单之外的
     * subtitle_url 一律拒绝下载。
     */
    @NotBlank
    private String subtitleAllowedHosts = "aisubtitle.hdslb.com";
}
