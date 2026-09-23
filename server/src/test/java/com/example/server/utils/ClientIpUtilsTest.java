package com.example.server.utils;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 客户端来源地址解析（IP 维度限流的输入）。
 *
 * <p>安全前提：{@code X-Forwarded-For} 由客户端随意伪造，**默认不信任**——否则攻击者换一个假 IP
 * 就重置了限流额度。只有显式打开可信代理开关时才取第一跳。
 */
class ClientIpUtilsTest {

    private final HttpServletRequest request = mock(HttpServletRequest.class);

    @Test
    void usesRemoteAddrByDefault() {
        when(request.getRemoteAddr()).thenReturn("203.0.113.9");
        when(request.getHeader("X-Forwarded-For")).thenReturn("198.51.100.7");

        assertEquals("203.0.113.9", ClientIpUtils.resolve(request, false));
        // 不信任时必须完全不读该头，避免"看一眼就上当"的实现。
        verify(request, never()).getHeader(anyString());
    }

    @Test
    void usesFirstHopWhenProxyIsTrusted() {
        when(request.getRemoteAddr()).thenReturn("10.0.0.5");
        when(request.getHeader("X-Forwarded-For"))
                .thenReturn(" 198.51.100.7 , 10.0.0.1, 10.0.0.2");

        assertEquals("198.51.100.7", ClientIpUtils.resolve(request, true));
    }

    /** 信任代理但该头缺失或全是空白时回退到直连地址。 */
    @Test
    void fallsBackToRemoteAddrWhenForwardedHeaderIsUnusable() {
        when(request.getRemoteAddr()).thenReturn("203.0.113.9");

        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        assertEquals("203.0.113.9", ClientIpUtils.resolve(request, true));

        when(request.getHeader("X-Forwarded-For")).thenReturn("   ,  ");
        assertEquals("203.0.113.9", ClientIpUtils.resolve(request, true));
    }

    /** 解析不出内容时统一为 unknown：所有来源未知的请求共享一个额度，而不是各自无限。 */
    @Test
    void unknownSourceIsNormalised() {
        when(request.getRemoteAddr()).thenReturn(null);
        assertEquals("unknown", ClientIpUtils.resolve(request, false));

        when(request.getRemoteAddr()).thenReturn("   ");
        assertEquals("unknown", ClientIpUtils.resolve(request, false));

        assertEquals("unknown", ClientIpUtils.resolve(null, true));
    }

    /** Key 片段必须受控：去掉逗号并截断，避免异常长的头把 Redis Key 撑大。 */
    @Test
    void valueIsSanitisedAndTruncated() {
        when(request.getRemoteAddr()).thenReturn("x".repeat(200));

        String resolved = ClientIpUtils.resolve(request, false);

        assertEquals(64, resolved.length());
        assertEquals(-1, resolved.indexOf(','));
    }
}
