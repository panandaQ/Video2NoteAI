package com.example.server.infrastructure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 解析 Netscape 格式 cookies.txt（计划 §4.2）。
 *
 * <p>规则：普通 {@code #} 注释行跳过，但 {@code #HttpOnly_} 前缀必须去前缀后继续解析；
 * 只接受 B 站域、未过期、格式合法的条目；重复键按最后一条有效记录处理。
 * 解析结果只包含 name → value，供网关拼接 Cookie 头，不落库、不进日志。
 */
public final class CookieFileParser {

    private static final String DOMAIN_SUFFIX = ".bilibili.com";

    private CookieFileParser() {
    }

    public static Map<String, String> parse(Path file) throws IOException {
        return parseNetscape(Files.readString(file));
    }

    /**
     * 解析 Netscape 格式 cookies.txt 文本（与 {@link #parse} 同一套规则，只是输入为字符串）。
     */
    public static Map<String, String> parseNetscape(String content) {
        Map<String, String> cookies = new LinkedHashMap<>();
        for (String rawLine : content.split("\\R")) {
            String line = rawLine;
            if (line.startsWith("#HttpOnly_")) {
                line = line.substring("#HttpOnly_".length());
            } else if (line.startsWith("#")) {
                continue;
            }
            if (line.isBlank()) continue;

            String[] parts = line.split("\\t");
            if (parts.length < 7) {
                // 部分导出工具用连续空格代替制表符，做一次兼容回退
                parts = line.trim().split("\\s+");
            }
            if (parts.length < 7) continue;

            String domain = parts[0].trim();
            String name = parts[5].trim();
            String value = parts[6];
            long expiry;
            try {
                expiry = Long.parseLong(parts[4].trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (name.isEmpty() || value == null) continue;
            if (!isBilibiliDomain(domain)) continue;
            // expiry=0 表示会话 Cookie；expiry>0 且已过期则丢弃
            if (expiry > 0 && expiry < System.currentTimeMillis() / 1000) continue;
            cookies.put(name, value);
        }
        return cookies;
    }

    /**
     * 归一化任意粘贴内容为 name→value：自动识别原始 Cookie 头串（{@code k=v; k2=v2}）或
     * Netscape cookies.txt 文本（含制表符的 7 列表格）。无法识别时返回空 Map。
     */
    public static Map<String, String> parseAny(String input) {
        if (input == null || input.isBlank()) {
            return new LinkedHashMap<>();
        }
        return input.contains("\t") ? parseNetscape(input) : parseHeader(input);
    }

    public static boolean isBilibiliDomain(String domain) {
        if (domain == null) return false;
        String normalized = domain.trim();
        return normalized.equals("bilibili.com") || normalized.endsWith(DOMAIN_SUFFIX);
    }

    /** 拼 Cookie 请求头；结果只出现在出站请求头里，禁止写入日志。 */
    public static String toCookieHeader(Map<String, String> cookies) {
        return cookies.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("; "));
    }

    /**
     * 解析原始 {@code Cookie:} 头字符串（{@code SESSDATA=...; bili_jct=...}）为 name → value。
     *
     * <p>按 {@code ;} 分段、按第一个 {@code =} 拆键值；空键或空值条目跳过，重复键按最后一条有效记录处理。
     * 与 {@link #parse} 只差输入形态（这里是浏览器 DevTools 复制的头串，不是 cookies.txt）。
     */
    public static Map<String, String> parseHeader(String header) {
        Map<String, String> cookies = new LinkedHashMap<>();
        if (header == null || header.isBlank()) {
            return cookies;
        }
        for (String pair : header.split(";")) {
            int separator = pair.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String name = pair.substring(0, separator).trim();
            String value = pair.substring(separator + 1).trim();
            if (name.isEmpty() || value.isEmpty()) {
                continue;
            }
            cookies.put(name, value);
        }
        return cookies;
    }

    /**
     * 把 name → value 写成 Netscape cookies.txt 文本，供 yt-dlp {@code --cookies} 读取。
     *
     * <p>域统一写 {@code .bilibili.com}（含子域）、路径 {@code /}、过期 0（会话 Cookie）。
     * 结果只用于写出临时文件，禁止写入日志。
     */
    public static String toNetscape(Map<String, String> cookies) {
        StringBuilder builder = new StringBuilder("# Netscape HTTP Cookie File\n");
        for (Map.Entry<String, String> entry : cookies.entrySet()) {
            builder.append(".bilibili.com\tTRUE\t/\tFALSE\t0\t")
                    .append(entry.getKey()).append('\t')
                    .append(entry.getValue()).append('\n');
        }
        return builder.toString();
    }
}
