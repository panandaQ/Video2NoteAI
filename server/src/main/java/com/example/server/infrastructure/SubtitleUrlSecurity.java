package com.example.server.infrastructure;

import com.example.server.utils.IpSafety;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Set;

/**
 * 字幕 CDN URL 安全校验（计划 §4.3）：只接受 HTTPS + 配置白名单内的 CDN Host，
 * 解析协议相对 URL 后校验 DNS/IP，禁止回环、内网、链路本地和云元数据地址。
 * 重定向由调用方逐跳复验；本类不发起网络请求之外的任何副作用，绝不携带 Cookie。
 */
public final class SubtitleUrlSecurity {

    private SubtitleUrlSecurity() {
    }

    /**
     * 校验并返回规范化后的绝对 URL。
     *
     * @throws IllegalStateException 协议、Host 或解析结果不合规
     */
    public static String validate(String url, Set<String> allowedHosts) {
        String normalized = normalize(url);
        URI uri = URI.create(normalized);
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalStateException("字幕 URL 只允许 HTTPS");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalStateException("字幕 URL 缺少主机");
        }
        if (!allowedHosts.contains(host)) {
            throw new IllegalStateException("字幕 CDN Host 不在白名单");
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (IpSafety.isDisallowedAddress(address)) {
                    throw new IllegalStateException("字幕 CDN 解析到受限地址");
                }
            }
        } catch (UnknownHostException e) {
            throw new IllegalStateException("字幕 CDN 主机无法解析", e);
        }
        return normalized;
    }

    /** 协议相对 URL（{@code //host/path}）补全为 HTTPS 绝对地址。 */
    static String normalize(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("字幕 URL 为空");
        }
        String trimmed = url.trim();
        if (trimmed.startsWith("//")) {
            return "https:" + trimmed;
        }
        return trimmed;
    }

    /** 按当前请求 URL 解析重定向目标（供逐跳复验）。 */
    public static String resolveRelative(String baseUrl, String location) {
        return URI.create(baseUrl).resolve(location).toString();
    }
}
