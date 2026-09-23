package com.example.server.utils;

import com.example.server.config.BilibiliAccessProperties;
import com.example.server.config.VideoImportProperties;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * yt-dlp 命令生成契约（计划 §4.2 / §7.1）：cookie 文件有/无、format-selector 配置生效
 * 且默认值与现状逐字符一致。
 */
class YtDlpUtilsTest {

    private static final String URL = "https://www.bilibili.com/video/BV1LtY968EcB";

    private static Path cookieFile() throws IOException {
        Path file = Files.createTempFile("bilibili-cookies-", ".txt");
        Files.writeString(file, ".bilibili.com\tTRUE\t/\tTRUE\t2000000000\tSESSDATA\tabc\n");
        return file;
    }

    private static YtDlpUtils utils(VideoImportProperties importProps,
                                    BilibiliAccessProperties accessProps) {
        return new YtDlpUtils("yt-dlp", "", importProps, accessProps);
    }

    @Test
    void defaultFormatSelectorMatchesCurrentBehaviorByteForByte() throws Exception {
        YtDlpUtils utils = utils(new VideoImportProperties(), new BilibiliAccessProperties());
        List<String> command = utils.buildCommand(URL, Path.of("out.mp4"));
        assertEquals(VideoImportProperties.DEFAULT_FORMAT_SELECTOR,
                command.get(command.indexOf("-f") + 1));
    }

    @Test
    void cookieFileAppendedWhenConfiguredAndReadable() throws Exception {
        Path cookie = cookieFile();
        BilibiliAccessProperties access = new BilibiliAccessProperties();
        access.setCookieFile(cookie.toString());
        List<String> command = utils(new VideoImportProperties(), access)
                .buildCommand(URL, Path.of("out.mp4"));
        int index = command.indexOf("--cookies");
        assertTrue(index >= 0);
        assertEquals(cookie.toString(), command.get(index + 1));
    }

    @Test
    void unreadableCookieFileFallsBackToAnonymous() throws Exception {
        BilibiliAccessProperties access = new BilibiliAccessProperties();
        access.setCookieFile("Z:\\no-such-dir\\missing-cookies.txt");
        List<String> command = utils(new VideoImportProperties(), access)
                .buildCommand(URL, Path.of("out.mp4"));
        assertFalse(command.contains("--cookies"));
    }

    @Test
    void blankCookieFileFallsBackToAnonymous() throws Exception {
        List<String> command = utils(new VideoImportProperties(), new BilibiliAccessProperties())
                .buildCommand(URL, Path.of("out.mp4"));
        assertFalse(command.contains("--cookies"));
    }

    @Test
    void formatSelectorIsConfigurable() throws Exception {
        VideoImportProperties props = new VideoImportProperties();
        props.setFormatSelector("bv*[height<=720]+ba/b");
        List<String> command = utils(props, new BilibiliAccessProperties())
                .buildCommand(URL, Path.of("out.mp4"));
        assertEquals("bv*[height<=720]+ba/b", command.get(command.indexOf("-f") + 1));
    }

    /** 用户选清晰度时，画质选择器由高度生成，优先于配置的 formatSelector。 */
    @Test
    void heightBuildsSelectorForThatHeight() throws Exception {
        YtDlpUtils utils = utils(new VideoImportProperties(), new BilibiliAccessProperties());
        List<String> command = utils.buildCommand(URL, Path.of("out.mp4"), 720, null);
        assertEquals(VideoImportProperties.formatSelectorForHeight(720),
                command.get(command.indexOf("-f") + 1));
    }

    /** 用户级 Cookie 的临时 cookies.txt 路径追加为 --cookies。 */
    @Test
    void userCookieTempFileAppendedAsCookiesArgument() throws Exception {
        Path cookie = Files.createTempFile("bilibili-cookie-", ".txt");
        List<String> command = utils(new VideoImportProperties(), new BilibiliAccessProperties())
                .buildCommand(URL, Path.of("out.mp4"), null, cookie);
        int index = command.indexOf("--cookies");
        assertTrue(index >= 0);
        assertEquals(cookie.toString(), command.get(index + 1));
    }
}
