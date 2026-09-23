package com.example.server.utils;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 客户端来源地址解析。
 *
 * <p>为什么需要"可信开关"而不是直接用 {@code X-Forwarded-For}：该请求头由客户端随意伪造，
 * 无条件信任等于把 IP 维度的限流交给攻击者决定（换一个假 IP 就重置额度）。因此：
 * <ul>
 *   <li>默认只用 {@code request.getRemoteAddr()}（直连场景下不可伪造）；</li>
 *   <li>只有部署在可信反向代理之后、且显式打开 {@code app.security.trust-forwarded-for=true} 时，
 *       才取 {@code X-Forwarded-For} 的**第一跳**（最靠近客户端的那一段）。</li>
 * </ul>
 *
 * <p>返回值的用途是限流的 Key 片段，因此必须是受控短字符串：长度截断、去掉空白与逗号，
 * 解析不出任何内容时回退为 {@code unknown}（让所有"来源未知"的请求共享一个额度，而不是各自无限）。
 */
public final class ClientIpUtils {

    /** 请求属性名：拦截器写入，Controller 读取。 */
    public static final String REQUEST_CLIENT_IP = "clientIp";

    private static final String UNKNOWN = "unknown";
    private static final int MAX_LENGTH = 64;

    private ClientIpUtils() {
    }

    /**
     * 解析客户端 IP。
     *
     * @param trustForwardedFor 是否信任反向代理写入的 {@code X-Forwarded-For}
     */
    public static String resolve(HttpServletRequest request, boolean trustForwardedFor) {
        if (request == null) {
            return UNKNOWN;
        }
        if (trustForwardedFor) {
            String forwarded = firstHop(request.getHeader("X-Forwarded-For"));
            if (forwarded != null) {
                return forwarded;
            }
        }
        return sanitize(request.getRemoteAddr());
    }

    /** {@code X-Forwarded-For} 的第一跳；为空或全是空白时返回 {@code null}。 */
    private static String firstHop(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        for (String part : header.split(",")) {
            String candidate = sanitize(part);
            if (!UNKNOWN.equals(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static String sanitize(String value) {
        if (value == null) {
            return UNKNOWN;
        }
        String trimmed = value.trim().replace(",", "");
        if (trimmed.isEmpty()) {
            return UNKNOWN;
        }
        return trimmed.length() > MAX_LENGTH ? trimmed.substring(0, MAX_LENGTH) : trimmed;
    }
}
