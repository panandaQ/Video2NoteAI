package com.example.server.infrastructure;

import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 用户级 B 站 Cookie 的 AES-GCM-256 加解密。
 *
 * <p>密钥只经环境变量（{@code video.import.bilibili.cookie-key}）进入，base64 编码的 32 字节；
 * 密钥空或非法时 {@link #isAvailable()} 为 {@code false}，调用方据此优雅降级（保存报错、读取返回空），
 * 不阻断应用启动。密文格式为 {@code base64(nonce) : base64(ciphertext)}，其中 nonce 为 12 字节。
 *
 * <p>红线：本类绝不把明文、密钥或密文写入日志。
 */
@Component
public class CookieCrypto {

    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom secureRandom = new SecureRandom();

    public CookieCrypto(com.example.server.config.BilibiliAccessProperties properties) {
        this.key = resolveKey(properties.getCookieKey());
    }

    /** 密钥是否可用；不可用时保存功能应拒绝写入而非静默明文落库。 */
    public boolean isAvailable() {
        return key != null;
    }

    /** 加密；密钥不可用时抛出明确异常，绝不在缺密钥时落明文。 */
    public String encrypt(String plaintext) {
        requireAvailable();
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            secureRandom.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(nonce) + ":"
                    + Base64.getEncoder().encodeToString(ciphertext);
        } catch (Exception e) {
            throw new IllegalStateException("Cookie 加密失败", e);
        }
    }

    /** 解密；密钥不可用或密文非法时返回 {@code null}（调用方按“无 Cookie”降级）。 */
    public String decrypt(String ciphertext) {
        if (!isAvailable() || ciphertext == null || ciphertext.isBlank()) {
            return null;
        }
        try {
            int separator = ciphertext.indexOf(':');
            if (separator <= 0) {
                return null;
            }
            byte[] nonce = Base64.getDecoder().decode(ciphertext.substring(0, separator));
            byte[] encrypted = Base64.getDecoder().decode(ciphertext.substring(separator + 1));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private void requireAvailable() {
        if (key == null) {
            throw new IllegalStateException("未配置 B 站 Cookie 加密密钥，无法保存 Cookie");
        }
    }

    /** 解析 base64 密钥；空、长度不符或解码失败返回 {@code null}。 */
    private static SecretKeySpec resolveKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            return null;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(base64Key.trim());
            if (bytes.length != KEY_BYTES) {
                return null;
            }
            return new SecretKeySpec(bytes, "AES");
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
