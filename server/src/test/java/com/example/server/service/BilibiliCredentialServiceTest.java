package com.example.server.service;

import com.example.server.common.ErrorCode;
import com.example.server.config.BilibiliAccessProperties;
import com.example.server.entity.UserBilibiliCredential;
import com.example.server.exception.BusinessException;
import com.example.server.infrastructure.CookieCrypto;
import com.example.server.mapper.UserBilibiliCredentialMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

/**
 * 用户级 Cookie 保存/读取契约：加密落库、覆盖更新、缺密钥时保存拒绝且不落明文、
 * 读取返回空、明文从不回传。
 */
class BilibiliCredentialServiceTest {

    private static final Long USER_ID = 7L;

    private final UserBilibiliCredentialMapper mapper = mock(UserBilibiliCredentialMapper.class);
    private final CookieCrypto crypto = crypto(validKey());
    private final BilibiliCredentialService service = new BilibiliCredentialService(mapper, crypto);

    private static String validKey() {
        return Base64.getEncoder().encodeToString(new byte[32]);
    }

    private static CookieCrypto crypto(String key) {
        BilibiliAccessProperties props = new BilibiliAccessProperties();
        props.setCookieKey(key);
        return new CookieCrypto(props);
    }

    @Test
    void saveInsertsWhenAbsent() {
        when(mapper.selectById(USER_ID)).thenReturn(null);
        service.save(USER_ID, "SESSDATA=abc; bili_jct=xyz");
        verify(mapper).insert(any(UserBilibiliCredential.class));
        verify(mapper, never()).updateById(any(UserBilibiliCredential.class));
    }

    @Test
    void saveUpdatesWhenPresent() {
        when(mapper.selectById(USER_ID)).thenReturn(new UserBilibiliCredential());
        service.save(USER_ID, "SESSDATA=abc");
        verify(mapper).updateById(any(UserBilibiliCredential.class));
        verify(mapper, never()).insert(any(UserBilibiliCredential.class));
    }

    @Test
    void getDecryptsStoredCookie() {
        UserBilibiliCredential stored = new UserBilibiliCredential();
        stored.setCookieEncrypted(crypto.encrypt("SESSDATA=abc"));
        when(mapper.selectById(USER_ID)).thenReturn(stored);
        assertEquals("SESSDATA=abc", service.getCookie(USER_ID));
    }

    @Test
    void getReturnsNullWhenAbsent() {
        when(mapper.selectById(USER_ID)).thenReturn(null);
        assertNull(service.getCookie(USER_ID));
    }

    @Test
    void saveRejectsBlankCookie() {
        BusinessException error = assertThrows(BusinessException.class, () -> service.save(USER_ID, "  "));
        assertEquals(ErrorCode.INVALID_ARGUMENT, error.errorCode());
    }

    @Test
    void saveRejectsCookieWithoutEqualsSign() {
        BusinessException error = assertThrows(BusinessException.class, () -> service.save(USER_ID, "notacookie"));
        assertEquals(ErrorCode.INVALID_ARGUMENT, error.errorCode());
    }

    /** 用户粘贴 cookies.txt（Netscape 格式）也能保存，落库的是归一化后的头串。 */
    @Test
    void saveAcceptsNetscapeFormatAndNormalizesToHeader() {
        when(mapper.selectById(USER_ID)).thenReturn(null);
        String netscape = ".bilibili.com\tTRUE\t/\tTRUE\t2000000000\tSESSDATA\tabc123\n";

        service.save(USER_ID, netscape);

        ArgumentCaptor<UserBilibiliCredential> captor = ArgumentCaptor.forClass(UserBilibiliCredential.class);
        verify(mapper).insert(captor.capture());
        assertEquals("SESSDATA=abc123", crypto.decrypt(captor.getValue().getCookieEncrypted()));
    }

    @Test
    void saveUnavailableWhenNoKeyConfiguredAndWritesNothing() {
        BilibiliCredentialService noKey = new BilibiliCredentialService(mapper, crypto(""));
        BusinessException error = assertThrows(BusinessException.class, () -> noKey.save(USER_ID, "a=b"));
        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, error.errorCode());
        verify(mapper, never()).insert(any(UserBilibiliCredential.class));
        verify(mapper, never()).updateById(any(UserBilibiliCredential.class));
    }

    @Test
    void clearDeletesRow() {
        service.clear(USER_ID);
        verify(mapper).deleteById(USER_ID);
    }

    @Test
    void statusReportsPresenceAndTimestamp() {
        UserBilibiliCredential stored = new UserBilibiliCredential();
        stored.setUpdatedAt(LocalDateTime.now());
        when(mapper.selectById(USER_ID)).thenReturn(stored);
        assertTrue(service.status(USER_ID).hasCookie());
    }
}
