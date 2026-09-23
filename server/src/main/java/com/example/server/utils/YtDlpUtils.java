package com.example.server.utils;

import com.example.server.config.BilibiliAccessProperties;
import com.example.server.config.VideoImportProperties;
import com.example.server.infrastructure.CookieFileParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class YtDlpUtils {

    private static final Logger log = LoggerFactory.getLogger(YtDlpUtils.class);

    private final String ytDlpPath;
    private final String ffmpegDir;
    private final String formatSelector;
    private final String cookieFile;

    public YtDlpUtils(@Value("${tool.ytdlp.path}") String ytDlpPath,
                      @Value("${tool.ffmpeg.dir}") String ffmpegDir,
                      VideoImportProperties importProperties,
                      BilibiliAccessProperties bilibiliProperties) {
        this.ytDlpPath = ytDlpPath;
        this.ffmpegDir = ffmpegDir;
        this.formatSelector = importProperties.getFormatSelector();
        this.cookieFile = bilibiliProperties.getCookieFile();
    }

    public File downloadVideo(String url) throws Exception {
        return downloadVideo(url, null, null);
    }

    /**
     * 下载视频。
     *
     * @param height 可选目标高度（像素）；非空时用同构模板生成画质选择器，否则沿用配置默认
     * @param cookie 可选用户级 Cookie 头串；非空时写成临时 cookies.txt 供 yt-dlp 使用
     */
    public File downloadVideo(String url, Integer height, String cookie) throws Exception {
        validatePublicHttpUrl(url);
        Path outputPath = Path.of(System.getProperty("java.io.tmpdir"), UUID.randomUUID() + ".mp4");
        Path logPath = Files.createTempFile("yt-dlp-", ".log");
        Path cookiePath = writeCookieFile(cookie);
        List<String> command = buildCommand(url, outputPath, height, cookiePath);

        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(logPath.toFile())
                    .start();
            if (!process.waitFor(30, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IllegalStateException("视频链接下载超时");
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(outputPath)) {
                String logs = Files.readString(logPath);
                throw new IllegalStateException("yt-dlp 下载失败: " + tail(logs, 2_000));
            }
            log.info("url_video_downloaded host={} bytes={}", URI.create(url).getHost(), Files.size(outputPath));
            return outputPath.toFile();
        } catch (Exception e) {
            Files.deleteIfExists(outputPath);
            throw e;
        } finally {
            Files.deleteIfExists(logPath);
            if (cookiePath != null) {
                Files.deleteIfExists(cookiePath);
            }
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    /**
     * 把原始 Cookie 头串写成临时 Netscape cookies.txt。
     *
     * <p>只用于下载子进程；文件在下载结束后删除，内容绝不写日志。空串或无有效键值对时返回 {@code null}，
     * 调用方回退到配置的全局 {@code cookie-file}。
     */
    private Path writeCookieFile(String cookie) throws Exception {
        if (cookie == null || cookie.isBlank()) {
            return null;
        }
        Map<String, String> cookies = CookieFileParser.parseHeader(cookie);
        if (cookies.isEmpty()) {
            return null;
        }
        Path file = Files.createTempFile("bilibili-cookie-", ".txt");
        Files.writeString(file, CookieFileParser.toNetscape(cookies));
        return file;
    }

    /** 默认画质入口：沿用配置的 formatSelector 与全局 cookie-file，兼容旧调用方。 */
    List<String> buildCommand(String url, Path outputPath) throws Exception {
        return buildCommand(url, outputPath, null, null);
    }

    /**
     * 组装 yt-dlp 命令（包级可见，便于单测覆盖命令形态而不依赖公网）。
     *
     * <p>Cookie 优先级：用户级临时 cookies.txt > 配置的全局 cookie-file > 匿名；
     * 日志不输出路径与 Cookie 内容（计划 §4.1/§4.2）。{@code height} 非空时覆盖配置画质。
     */
    List<String> buildCommand(String url, Path outputPath, Integer height, Path cookiePath) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(ytDlpPath);
        command.add("--no-playlist");
        command.add("--socket-timeout");
        command.add("30");
        command.add("--retries");
        command.add("3");
        command.add("--max-filesize");
        command.add("2048M");
        String effectiveCookiePath = cookiePath != null ? cookiePath.toString() : resolveCookieFile();
        if (effectiveCookiePath != null) {
            command.add("--cookies");
            command.add(effectiveCookiePath);
        }
        // Prefer the broadly supported H.264/AVC + AAC combination for imported
        // videos. Merely changing an AV1 file's container to MP4 does not make it
        // playable in Safari on every macOS and hardware combination.
        //
        // 高度上限是实测结论，不是保守取值：未登录时 1080P 属大会员画质，且高码率流由 PCDN 分发，
        // 同一台机器上 1080P 约 40KB/s、720P 约 17KB/s，而 480P 可跑满带宽（2MB/4.3s）。
        // 默认 480P 保持不变（D-045），画质改由 video.import.format-selector 配置（D-072）。
        command.add("-f");
        command.add(height != null ? VideoImportProperties.formatSelectorForHeight(height) : formatSelector);
        command.add("--merge-output-format");
        command.add("mp4");
        command.add("--recode-video");
        command.add("mp4");
        if (ffmpegDir != null && !ffmpegDir.isBlank()) {
            command.add("--ffmpeg-location");
            command.add(ffmpegDir);
        }
        command.add("-o");
        command.add(outputPath.toString());
        command.add(url);
        return command;
    }

    private String resolveCookieFile() {
        if (cookieFile == null || cookieFile.isBlank()) return null;
        Path path = Path.of(cookieFile);
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            log.warn("bilibili_cookie_unavailable reason=file_missing_or_unreadable");
            return null;
        }
        return path.toString();
    }

    private void validatePublicHttpUrl(String value) throws Exception {
        URI uri = URI.create(value);
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (host == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("仅支持合法的公网 HTTP/HTTPS 视频链接");
        }
        InetAddress[] resolved = InetAddress.getAllByName(host);
        if (resolved.length == 0) {
            throw new IllegalArgumentException("无法解析视频链接的主机地址");
        }
        for (InetAddress address : resolved) {
            if (IpSafety.isDisallowedAddress(address)) {
                throw new IllegalArgumentException("不允许访问本机、内网或保留网段地址");
            }
        }
        // 注意：这里只是应用层的尽力校验（defense-in-depth）。yt-dlp 子进程会对 host
        // 重新做一次 DNS 解析并可能跟随 302 重定向，存在 DNS rebinding / 跳转到内网的
        // TOCTOU 风险，单靠应用层字符串/首次解析校验无法彻底封堵。生产环境必须叠加网络层
        // 出口管控（egress 白名单、独立网络命名空间或出口代理），才能真正杜绝 SSRF。
    }

    private String tail(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(value.length() - maxLength);
    }
}
