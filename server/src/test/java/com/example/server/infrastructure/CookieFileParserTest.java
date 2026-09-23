package com.example.server.infrastructure;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Netscape cookies.txt 解析契约（计划 §4.2 / §7.1）：注释、HttpOnly 前缀、过期项、
 * 非 B 站域、重复键与损坏文件；输出不含敏感值以外的噪声。
 */
class CookieFileParserTest {

    private static Path writeFile(String content) throws IOException {
        Path file = Files.createTempFile("bilibili-cookies-", ".txt");
        Files.writeString(file, content);
        return file;
    }

    @Test
    void parsesNetscapeFormatAndHttpOnlyPrefix() throws IOException {
        Path file = writeFile("""
                # Netscape HTTP Cookie File
                .bilibili.com	TRUE	/	TRUE	2000000000	SESSDATA	abc123
                #HttpOnly_.bilibili.com	TRUE	/	FALSE	2000000000	bili_jct	xyz789
                """);
        Map<String, String> cookies = CookieFileParser.parse(file);
        assertEquals("abc123", cookies.get("SESSDATA"));
        assertEquals("xyz789", cookies.get("bili_jct"));
    }

    @Test
    void skipsCommentsExpiredNonBilibiliAndMalformed() throws IOException {
        long expired = System.currentTimeMillis() / 1000 - 60;
        Path file = writeFile("""
                # comment line
                .bilibili.com	TRUE	/	TRUE	2000000000	SESSDATA	ok
                .bilibili.com	TRUE	/	TRUE	%EXPD%	EXPIRED	cookie	value
                .evil.com	TRUE	/	TRUE	2000000000	SESSDATA	stolen
                .bilibili.com	TRUE	/	TRUE	not-a-number	bad	value
                .bilibili.com	TRUE	/	TRUE	2000000000		novalue
                """.replace("%EXPD%", String.valueOf(expired)));
        Map<String, String> cookies = CookieFileParser.parse(file);
        assertEquals(1, cookies.size());
        assertEquals("ok", cookies.get("SESSDATA"));
    }

    @Test
    void lastDuplicateWins() throws IOException {
        Path file = writeFile("""
                .bilibili.com	TRUE	/	TRUE	2000000000	SESSDATA	first
                .bilibili.com	TRUE	/	TRUE	2000000000	SESSDATA	second
                """);
        assertEquals("second", CookieFileParser.parse(file).get("SESSDATA"));
    }

    @Test
    void sessionCookiesWithZeroExpiryAreKept() throws IOException {
        Path file = writeFile(".bilibili.com\tTRUE\t/\tTRUE\t0\tsession\tsv\n");
        assertEquals("sv", CookieFileParser.parse(file).get("session"));
    }

    @Test
    void missingFileThrows() {
        assertThrows(IOException.class, () ->
                CookieFileParser.parse(Path.of("Z:\\no-such-dir\\missing.txt")));
    }

    @Test
    void domainCheck() {
        assertTrue(CookieFileParser.isBilibiliDomain(".bilibili.com"));
        assertTrue(CookieFileParser.isBilibiliDomain("bilibili.com"));
        assertFalse(CookieFileParser.isBilibiliDomain("bilibili.com.evil.com"));
        assertFalse(CookieFileParser.isBilibiliDomain(null));
    }

    @Test
    void cookieHeaderJoinsWithoutSensitiveNoise() {
        Map<String, String> ordered = new java.util.LinkedHashMap<>();
        ordered.put("SESSDATA", "a");
        ordered.put("bili_jct", "b");
        assertEquals("SESSDATA=a; bili_jct=b", CookieFileParser.toCookieHeader(ordered));
    }

    @Test
    void parseHeaderSplitsPairsOnFirstEqualsSign() {
        Map<String, String> cookies = CookieFileParser.parseHeader("SESSDATA=abc; bili_jct=xyz; buvid3=1==2");
        assertEquals("abc", cookies.get("SESSDATA"));
        assertEquals("xyz", cookies.get("bili_jct"));
        assertEquals("1==2", cookies.get("buvid3"));
    }

    @Test
    void parseHeaderSkipsEmptyAndMalformedPairs() {
        Map<String, String> cookies = CookieFileParser.parseHeader("a=b; ; =novalue; c=d; e");
        assertEquals(2, cookies.size());
        assertEquals("b", cookies.get("a"));
        assertEquals("d", cookies.get("c"));
    }

    @Test
    void parseHeaderBlankOrNullReturnsEmpty() {
        assertTrue(CookieFileParser.parseHeader(null).isEmpty());
        assertTrue(CookieFileParser.parseHeader("   ").isEmpty());
    }

    @Test
    void toNetscapeWritesExpectedFormat() {
        Map<String, String> ordered = new java.util.LinkedHashMap<>();
        ordered.put("SESSDATA", "abc");
        String netscape = CookieFileParser.toNetscape(ordered);
        assertTrue(netscape.startsWith("# Netscape HTTP Cookie File\n"));
        assertTrue(netscape.contains(".bilibili.com\tTRUE\t/\tFALSE\t0\tSESSDATA\tabc\n"));
    }

    @Test
    void parseAnyDetectsNetscapeFormat() {
        String netscape = "# Netscape HTTP Cookie File\n"
                + ".bilibili.com\tTRUE\t/\tTRUE\t2000000000\tSESSDATA\tabc123\n"
                + "#HttpOnly_.bilibili.com\tTRUE\t/\tFALSE\t2000000000\tbili_jct\txyz789\n";
        Map<String, String> cookies = CookieFileParser.parseAny(netscape);
        assertEquals("abc123", cookies.get("SESSDATA"));
        assertEquals("xyz789", cookies.get("bili_jct"));
    }

    @Test
    void parseAnyFallsBackToHeaderFormat() {
        Map<String, String> cookies = CookieFileParser.parseAny("SESSDATA=abc; bili_jct=xyz");
        assertEquals("abc", cookies.get("SESSDATA"));
        assertEquals("xyz", cookies.get("bili_jct"));
    }

    @Test
    void parseAnyReturnsEmptyForUnrecognizableInput() {
        assertTrue(CookieFileParser.parseAny("not a cookie").isEmpty());
        assertTrue(CookieFileParser.parseAny(null).isEmpty());
    }
}
