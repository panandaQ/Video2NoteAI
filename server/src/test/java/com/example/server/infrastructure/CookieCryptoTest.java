package com.example.server.infrastructure;

import com.example.server.config.BilibiliAccessProperties;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Cookie 加密契约：AES-GCM 加解密往返、每次加密用新 nonce、缺密钥/密钥非法时不可用、
 * 密文损坏一律返回 {@code null} 而非抛异常。
 */
class CookieCryptoTest {

    private static String validKey() {
        return Base64.getEncoder().encodeToString(new byte[32]);
    }

    private static CookieCrypto crypto(String key) {
        BilibiliAccessProperties props = new BilibiliAccessProperties();
        props.setCookieKey(key);
        return new CookieCrypto(props);
    }

    @Test
    void encryptThenDecryptRoundTrips() {
        CookieCrypto crypto = crypto(validKey());
        String ciphertext = crypto.encrypt("SESSDATA=abc; bili_jct=xyz");
        assertEquals("SESSDATA=abc; bili_jct=xyz", crypto.decrypt(ciphertext));
    }

    @Test
    void encryptUsesFreshNonceEachTime() {
        CookieCrypto crypto = crypto(validKey());
        assertNotEquals(crypto.encrypt("a=b"), crypto.encrypt("a=b"));
    }

    @Test
    void blankKeyIsUnavailable() {
        CookieCrypto crypto = crypto("");
        assertFalse(crypto.isAvailable());
        assertNull(crypto.decrypt("anything"));
        assertThrows(IllegalStateException.class, () -> crypto.encrypt("a=b"));
    }

    @Test
    void malformedOrWrongLengthKeyIsUnavailable() {
        assertFalse(crypto("not-base64!!").isAvailable());
        assertFalse(crypto(Base64.getEncoder().encodeToString(new byte[16])).isAvailable());
    }

    @Test
    void corruptOrMissingCiphertextReturnsNull() {
        CookieCrypto crypto = crypto(validKey());
        assertNull(crypto.decrypt("not-a-valid-ciphertext"));
        assertNull(crypto.decrypt(null));
        assertNull(crypto.decrypt(""));
    }
}
