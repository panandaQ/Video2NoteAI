package com.example.server.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * WBI 签名（计划 §4.3）：参数按 key 排序 → 过滤 {@code !'()*} → URL 编码 →
 * 追加 mixinKey → MD5 得到 {@code w_rid}。
 *
 * <p>纯函数实现，便于用固定 fixture 逐字节钉住签名结果（§7.1）；mixinKey 由
 * player 页面导航接口返回的 img_key/sub_key 按社区公开的索引表推导。
 */
public final class BilibiliWbiSigner {

    /** mixinKey 推导索引表（社区公开算法）。 */
    private static final int[] MIXIN_KEY_INDICES = {
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
            33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61,
            26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36,
            20, 34, 44, 52};

    private BilibiliWbiSigner() {
    }

    /** 由 img_key + sub_key 推导 64 位 mixinKey。 */
    public static String deriveMixinKey(String imgKey, String subKey) {
        if (imgKey == null || subKey == null) {
            throw new IllegalArgumentException("imgKey 与 subKey 不能为空");
        }
        String combined = imgKey + subKey;
        StringBuilder key = new StringBuilder(MIXIN_KEY_INDICES.length);
        for (int index : MIXIN_KEY_INDICES) {
            if (index >= combined.length()) {
                throw new IllegalArgumentException("img/sub key 过短，无法推导 mixinKey");
            }
            key.append(combined.charAt(index));
        }
        return key.toString();
    }

    /** 对参数表签名；{@code w_rid}/{@code wts} 自身不参与签名。 */
    public static String sign(Map<String, String> params, String mixinKey) {
        TreeMap<String, String> sorted = new TreeMap<>();
        params.forEach((key, value) -> {
            if (value == null || "w_rid".equals(key) || "wts".equals(key)) return;
            sorted.put(key, filterChars(value));
        });
        StringBuilder query = new StringBuilder();
        sorted.forEach((key, value) -> {
            if (query.length() > 0) query.append('&');
            query.append(percentEncode(key)).append('=').append(percentEncode(value));
        });
        return md5(query + mixinKey);
    }

    /** WBI 规则：过滤值中的 {@code !'()*} 五个字符后再编码。 */
    static String filterChars(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '!' || c == '\'' || c == '(' || c == ')' || c == '*') continue;
            out.append(c);
        }
        return out.toString();
    }

    /** RFC 3986 不保留字符之外的字节按 %XX 编码。 */
    static String percentEncode(String value) {
        StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                out.append((char) c);
            } else {
                out.append('%').append(String.format("%02X", c));
            }
        }
        return out.toString();
    }

    static String md5(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is not available", e);
        }
    }
}
