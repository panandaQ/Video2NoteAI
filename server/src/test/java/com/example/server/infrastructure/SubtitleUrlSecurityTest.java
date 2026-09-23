package com.example.server.infrastructure;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 字幕 URL 安全契约（计划 §4.3 / §7.1）：HTTPS-only、Host 白名单、DNS 私网/回环/云元数据阻断。
 */
class SubtitleUrlSecurityTest {

    /** 白名单里故意放进受限 IP，验证 DNS 校验在白名单之后仍然生效。 */
    private static final Set<String> HOSTS = Set.of(
            "aisubtitle.hdslb.com", "127.0.0.1", "169.254.169.254");

    @Test
    void httpsOnlyRejected() {
        assertThrows(IllegalStateException.class, () ->
                SubtitleUrlSecurity.validate("http://aisubtitle.hdslb.com/a.json", HOSTS));
    }

    @Test
    void protocolRelativeNormalizedToHttps() {
        assertEquals("https://aisubtitle.hdslb.com/bfs/subtitle/a.json",
                SubtitleUrlSecurity.validate("//aisubtitle.hdslb.com/bfs/subtitle/a.json", HOSTS));
    }

    @Test
    void hostOutsideWhitelistRejected() {
        assertThrows(IllegalStateException.class, () ->
                SubtitleUrlSecurity.validate("https://evil.example.com/a.json", HOSTS));
        assertThrows(IllegalStateException.class, () ->
                SubtitleUrlSecurity.validate("https://aisubtitle.hdslb.com.evil.com/a.json", HOSTS));
    }

    @Test
    void loopbackAndMetadataAddressesRejectedAfterDnsCheck() {
        assertThrows(IllegalStateException.class, () ->
                SubtitleUrlSecurity.validate("https://127.0.0.1/a.json", HOSTS));
        assertThrows(IllegalStateException.class, () ->
                SubtitleUrlSecurity.validate("https://169.254.169.254/a.json", HOSTS));
    }

    @Test
    void resolveRelativeJoinsLocation() {
        assertEquals("https://aisubtitle.hdslb.com/next.json",
                SubtitleUrlSecurity.resolveRelative(
                        "https://aisubtitle.hdslb.com/bfs/a.json", "/next.json"));
    }

    @Test
    void blankUrlRejected() {
        assertThrows(IllegalStateException.class, () ->
                SubtitleUrlSecurity.validate("  ", HOSTS));
    }
}
