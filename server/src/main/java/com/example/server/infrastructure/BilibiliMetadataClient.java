package com.example.server.infrastructure;

import com.example.server.config.BilibiliAccessProperties;
import com.example.server.config.VideoImportProperties;
import com.example.server.dto.PlayerViewPoint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.HttpUrl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * B 站稿件元数据网关。
 *
 * <p>为什么必须有它：媒体唯一身份需要 CID，而 {@code yt-dlp --dump-single-json} 的输出里**没有 cid 字段**
 * （已用真实稿件验证），只靠进程探测拿不到可播放单元 ID。平台自身的 view 接口同时给出 BVID、标题、作者和
 * 分 P 列表，是单元身份的唯一可靠来源；yt-dlp 继续只负责获取媒体。
 *
 * <p>只做 HTTP 与 JSON 解析，不判断导入语义：网络与传输失败抛 {@link IllegalStateException}，
 * 平台业务错误抛 {@link BilibiliApiException}，由来源 Adapter 翻译成契约错误码。
 */
@Component
public class BilibiliMetadataClient {

    private static final Logger log = LoggerFactory.getLogger(BilibiliMetadataClient.class);
    private static final String VIEW_ENDPOINT = "https://api.bilibili.com/x/web-interface/view";
    private static final String PLAYER_ENDPOINT = "https://api.bilibili.com/x/player/v2";
    /** WBI 端点（计划 §4.3）：实测带登录态时普通 /x/player/v2 返回空字幕，而 yt-dlp 经 wbi/v2 能拿到 ai-zh。 */
    private static final String PLAYER_WBI_ENDPOINT = "https://api.bilibili.com/x/player/wbi/v2";
    /** WBI mixinKey 的 img/sub key 来源。 */
    private static final String NAV_ENDPOINT = "https://api.bilibili.com/x/web-interface/nav";
    /** 平台对缺少 UA 的请求会返回风控页，必须带常见 UA。 */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36";
    private static final int SUCCESS_CODE = 0;
    /** 字幕下载重定向逐跳复验的最大跳数。 */
    private static final int MAX_SUBTITLE_REDIRECT_HOPS = 3;
    /** 封面转存上限：超过这个尺寸的响应不是封面，拒绝写入，避免被异常响应打满对象存储。 */
    private static final long MAX_COVER_BYTES = 5L * 1024 * 1024;
    private static final String DEFAULT_COVER_CONTENT_TYPE = "image/jpeg";

    private final ObjectMapper objectMapper;
    private final VideoImportProperties properties;
    private final int readTimeoutSeconds;
    private final OkHttpClient client;
    private final BilibiliAccessProperties accessProperties;
    /** 启动时从配置的 cookies.txt 载入的 B 站 Cookie；空 = 匿名模式。 */
    private final Map<String, String> cookies;
    /**
     * 短链探测专用：**不跟随重定向**。
     *
     * <p>必须单独持有一个客户端：OkHttp 的重定向策略是客户端级配置，而展开短链时必须自己看每一跳的
     * {@code Location} 并逐跳校验主机——把跳转交给 HTTP 客户端等于放弃了这条出站请求的控制权。
     * 复用同一连接池与线程池，因此没有额外资源成本。
     */
    private final OkHttpClient redirectProbeClient;
    /**
     * 外部资源客户端（字幕 CDN）：**不跟随重定向、绝不携带 Cookie**。
     *
     * <p>与平台 API 客户端分离（交付约束 7）：字幕请求逐跳校验 Host，跟随交给客户端等于
     * 放弃出站控制权；Cookie 只允许出现在平台 API Host 上。
     */
    private final OkHttpClient resourceClient;

    public BilibiliMetadataClient(ObjectMapper objectMapper, VideoImportProperties properties,
                                  BilibiliAccessProperties accessProperties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.readTimeoutSeconds = Math.max(1, properties.getResolveTimeoutSeconds());
        this.accessProperties = accessProperties;
        this.cookies = loadCookies(accessProperties);
        log.info("bilibili_cookie_available={}", !cookies.isEmpty());
        this.client = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
                .build();
        this.redirectProbeClient = client.newBuilder().followRedirects(false).build();
        this.resourceClient = client.newBuilder().followRedirects(false).build();
    }

    /** 载入 Cookie 文件；配置但不可读/解析失败时按匿名模式继续，不阻止启动（计划 §4.1）。 */
    private static Map<String, String> loadCookies(BilibiliAccessProperties properties) {
        String configured = properties.getCookieFile();
        if (configured == null || configured.isBlank()) return Map.of();
        try {
            Map<String, String> parsed = CookieFileParser.parse(Path.of(configured));
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            log.warn("bilibili_cookie_load_failed");
            return Map.of();
        }
    }

    /**
     * 平台 API 请求构造：只在精确允许的 B 站 API Host 上附加 Cookie（交付约束 7）。
     * 封面、字幕 CDN、重定向探测等外部资源请求不得经过本方法。
     *
     * @param cookieHeader 用户级 Cookie 头串（可空）；非空时优先于启动期全局 Cookie
     */
    private Request.Builder platformRequest(String url, String cookieHeader) {
        Request.Builder builder = new Request.Builder()
                .url(url)
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("Referer", "https://www.bilibili.com/");
        String host = URI.create(url).getHost();
        if (!isCookieAllowedHost(host)) {
            return builder;
        }
        String header = (cookieHeader != null && !cookieHeader.isBlank())
                ? cookieHeader
                : (cookies.isEmpty() ? null : CookieFileParser.toCookieHeader(cookies));
        if (header != null) {
            builder.addHeader("Cookie", header);
        }
        return builder;
    }

    private boolean isCookieAllowedHost(String host) {
        if (host == null) return false;
        return Arrays.stream(accessProperties.getCookieAllowedHosts().split(","))
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .anyMatch(host::equals);
    }

    /**
     * 读取一次重定向目标，不跟随。
     *
     * <p>实测行为：有效的分享短链返回 {@code 302 + Location}；无效短链码返回 {@code 200} 落地页、
     * 没有 {@code Location}；根路径返回 {@code 404}。因此"没有 Location"是正常结果而不是异常，
     * 由来源 Adapter 判定为短链失效。
     *
     * @return {@code Location} 原始值；没有重定向时返回 {@code null}
     * @throws IllegalStateException 网络或传输失败（由 Adapter 翻译成可重试错误）
     */
    public String fetchRedirectLocation(String url) {
        if (url == null || url.isBlank()
                || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            return null;
        }
        Request request = new Request.Builder()
                .url(url)
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("Referer", "https://www.bilibili.com/")
                .get()
                .build();
        try (Response response = redirectProbeClient.newCall(request).execute()) {
            String location = response.header("Location");
            if (location == null || location.isBlank()) {
                log.info("bilibili_redirect_absent status={}", response.code());
                return null;
            }
            return location;
        } catch (Exception e) {
            throw new IllegalStateException("短链接跳转读取失败", e);
        }
    }

    /**
     * 读取稿件元数据。
     *
     * @param bvid 归一后的稿件 ID，与 {@code aid} 二选一
     * @param aid  数字稿件 ID
     */
    public BilibiliVideoMetadata fetchVideo(String bvid, Long aid) {
        return fetchVideo(bvid, aid, null);
    }

    /** 带用户级 Cookie 的读取（可空 = 回退全局/匿名）。 */
    public BilibiliVideoMetadata fetchVideo(String bvid, Long aid, String cookieHeader) {
        HttpUrl.Builder url = HttpUrl.parse(VIEW_ENDPOINT).newBuilder();
        if (bvid != null && !bvid.isBlank()) {
            url.addQueryParameter("bvid", bvid);
        } else if (aid != null) {
            url.addQueryParameter("aid", String.valueOf(aid));
        } else {
            throw new IllegalArgumentException("bvid 与 aid 至少需要一个");
        }
        Request request = platformRequest(url.build().toString(), cookieHeader).get().build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalStateException("B 站元数据接口 HTTP " + response.code());
            }
            return parse(response.body().string());
        } catch (BilibiliApiException | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("B 站元数据接口调用失败", e);
        }
    }

    /**
     * 读取播放器信息：字幕轨道、need_login_subtitle 与 View 章节（计划 §4.3）。
     *
     * <p>这不是稳定公开契约，必须用响应结构校验与降级/重试隔离变化。网络与传输失败抛
     * {@link IllegalStateException}（可重试），业务错误抛 {@link BilibiliApiException}。
     * {@code aid} 可空：player API 的 aid 与 bvid 二选一，媒体行只保存 BVID+CID。
     */
    public PlayerInfo fetchPlayerInfo(String bvid, Long aid, String cid) {
        return fetchPlayerInfo(bvid, aid, cid, null);
    }

    /** 带用户级 Cookie 的读取（可空 = 回退全局/匿名）。 */
    public PlayerInfo fetchPlayerInfo(String bvid, Long aid, String cid, String cookieHeader) {
        if (cid == null || cid.isBlank()) {
            throw new IllegalArgumentException("cid 不能为空");
        }
        // WBI 端点优先（2026-09-20 实测：带登录态时普通 /x/player/v2 返回空字幕，
        // 而 yt-dlp 经 wbi/v2 能拿到 ai-zh 字幕）；WBI 失败才回退普通端点。
        try {
            return fetchPlayerInfoWbi(bvid, aid, cid, cookieHeader);
        } catch (IllegalStateException wbiFailure) {
            log.warn("bilibili_player_wbi_fallback reason={}", wbiFailure.getMessage());
            return fetchPlayerInfoPlain(bvid, aid, cid, cookieHeader);
        }
    }

    private PlayerInfo fetchPlayerInfoWbi(String bvid, Long aid, String cid, String cookieHeader) {
        // 与 yt-dlp 实测请求对齐（--print-traffic，2026-09-20）：
        // GET /x/player/wbi/v2?aid=..&cid=.. —— 不携带 w_rid/wts 签名、不带 fnval/fnver。
        // yt-dlp 用的是 aid（非 bvid）：媒体行只存 BVID+CID，这里经 view 接口补一次 aid。
        if (aid == null && bvid != null && !bvid.isBlank()) {
            try {
                aid = fetchVideo(bvid, null, cookieHeader).aid();
            } catch (RuntimeException e) {
                log.warn("bilibili_player_aid_resolve_failed bvid={}", bvid);
            }
        }
        HttpUrl.Builder url = HttpUrl.parse(PLAYER_WBI_ENDPOINT).newBuilder();
        if (aid != null) {
            url.addQueryParameter("aid", String.valueOf(aid));
        } else if (bvid != null && !bvid.isBlank()) {
            url.addQueryParameter("bvid", bvid);
        } else {
            throw new IllegalArgumentException("bvid 与 aid 至少需要一个");
        }
        url.addQueryParameter("cid", cid);
        return executePlayerRequest(url.build().toString(), cookieHeader);
    }

    private PlayerInfo fetchPlayerInfoPlain(String bvid, Long aid, String cid, String cookieHeader) {
        HttpUrl.Builder url = HttpUrl.parse(PLAYER_ENDPOINT).newBuilder();
        if (bvid != null && !bvid.isBlank()) {
            url.addQueryParameter("bvid", bvid);
        } else if (aid != null) {
            url.addQueryParameter("aid", String.valueOf(aid));
        } else {
            throw new IllegalArgumentException("bvid 与 aid 至少需要一个");
        }
        url.addQueryParameter("cid", cid);
        return executePlayerRequest(url.build().toString(), cookieHeader);
    }

    private PlayerInfo executePlayerRequest(String url, String cookieHeader) {
        Request request = platformRequest(url, cookieHeader).get().build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalStateException("B 站播放器接口 HTTP " + response.code());
            }
            String body = response.body().string();
            PlayerInfo info = parsePlayer(body);
            log.info("bilibili_player_fetched endpoint={} subtitles={} viewPoints={} needLoginSubtitle={}",
                    URI.create(url).getPath(), info.subtitles().size(), info.viewPoints().size(),
                    info.needLoginSubtitle());
            return info;
        } catch (BilibiliApiException | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("B 站播放器接口调用失败", e);
        }
    }

    /**
     * 校验 B 站登录态（nav 接口）。
     *
     * <p>字幕是登录态资产：Cookie 过期时 player 接口会静默返回空字幕，把"登录失效"
     * 误判成"视频无字幕"。拉字幕前必须先校验登录态，未登录时下游跳过字幕并记录
     * {@code NEED_LOGIN}，而不是静默降级为 ABSENT。网络/解析失败按未登录返回——
     * 字幕是可降级资产，登录校验不应变成新的阻塞点。
     */
    public LoginStatus checkLogin() {
        return checkLogin(null);
    }

    /** 带用户级 Cookie 的登录态校验（可空 = 回退全局/匿名）。 */
    public LoginStatus checkLogin(String cookieHeader) {
        try {
            Request request = platformRequest(NAV_ENDPOINT, cookieHeader).get().build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    return LoginStatus.loggedOut();
                }
                return parseLoginStatus(response.body().string());
            }
        } catch (Exception e) {
            log.warn("bilibili_login_check_failed reason={}", e.getMessage());
            return LoginStatus.loggedOut();
        }
    }

    /** 解析 nav 响应的登录态；包级可见以便 fixture 测试（不依赖公网）。 */
    LoginStatus parseLoginStatus(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        if (root.path("code").asInt(-1) != SUCCESS_CODE) {
            return LoginStatus.loggedOut();
        }
        JsonNode data = root.path("data");
        if (!data.path("isLogin").asBoolean(false)) {
            return LoginStatus.loggedOut();
        }
        Long mid = data.path("mid").isNumber() ? data.path("mid").asLong() : null;
        String uname = data.path("uname").asText(null);
        return new LoginStatus(true, mid, uname);
    }

    /** 从 nav 接口取 wbi img/sub key 并推导 mixinKey。 */
    private String fetchMixinKey() {
        Request request = platformRequest(NAV_ENDPOINT, null).get().build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalStateException("B 站 nav 接口 HTTP " + response.code());
            }
            String[] keys = parseWbiKeys(response.body().string());
            return BilibiliWbiSigner.deriveMixinKey(keys[0], keys[1]);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("WBI mixinKey 获取失败", e);
        }
    }

    /** 解析 nav 响应的 wbi img/sub key；包级可见以便 fixture 测试。 */
    String[] parseWbiKeys(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        if (root.path("code").asInt(-1) != SUCCESS_CODE) {
            throw new IllegalStateException("B 站 nav 接口业务错误 code=" + root.path("code").asInt(-1));
        }
        JsonNode wbi = root.path("data").path("wbi_img");
        return new String[]{extractKeyFromUrl(wbi.path("img_url").asText()),
                extractKeyFromUrl(wbi.path("sub_url").asText())};
    }

    /** 从 wbi 资源 URL 提取文件名中的 key（如 {@code .../7cd084941338484aae1ad9425b84077c.png}）。 */
    static String extractKeyFromUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("wbi img/sub URL 缺失");
        }
        String name = url.substring(url.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** 解析 player 接口响应；包级可见以便 fixture 测试。 */
    PlayerInfo parsePlayer(String body) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("B 站播放器 JSON 无法解析", e);
        }
        int code = root.path("code").asInt(Integer.MIN_VALUE);
        if (code != SUCCESS_CODE) {
            throw new BilibiliApiException(code, root.path("message").asText(""));
        }
        JsonNode data = root.path("data");
        List<PlayerSubtitle> subtitles = new ArrayList<>();
        for (JsonNode item : data.path("subtitle").path("subtitles")) {
            subtitles.add(new PlayerSubtitle(
                    text(item, "lan"),
                    text(item, "lan_doc"),
                    text(item, "subtitle_url"),
                    item.path("ai_type").isNumber() ? item.path("ai_type").asInt() : null,
                    item.path("ai_status").isNumber() ? item.path("ai_status").asInt() : null,
                    item.path("is_lock").isBoolean() ? item.path("is_lock").asBoolean() : null));
        }
        boolean needLoginSubtitle = data.path("need_login_subtitle").asBoolean(false);
        List<PlayerViewPoint> viewPoints = new ArrayList<>();
        for (JsonNode item : data.path("view_points")) {
            viewPoints.add(new PlayerViewPoint(
                    text(item, "content"),
                    item.path("from").asDouble(Double.NaN),
                    item.path("to").asDouble(Double.NaN),
                    item.path("type").isNumber() ? item.path("type").asInt() : null));
        }
        return new PlayerInfo(subtitles, needLoginSubtitle, viewPoints);
    }

    /**
     * 下载字幕 CC JSON 正文（计划 §4.3）：只接受 HTTPS + 白名单 CDN Host，协议相对 URL 补全后
     * 校验 DNS/IP，关闭自动重定向并逐跳复验（最多 3 跳），超过大小上限立即中止，绝不携带 Cookie。
     */
    public String fetchSubtitleJson(String subtitleUrl) {
        String current = SubtitleUrlSecurity.validate(subtitleUrl, allowedSubtitleHosts());
        for (int hop = 0; hop < MAX_SUBTITLE_REDIRECT_HOPS; hop++) {
            Request request = new Request.Builder()
                    .url(current)
                    .addHeader("User-Agent", USER_AGENT)
                    .addHeader("Referer", "https://www.bilibili.com/")
                    .get()
                    .build();
            try (Response response = resourceClient.newCall(request).execute()) {
                int code = response.code();
                if (code >= 300 && code < 400) {
                    String location = response.header("Location");
                    if (location == null || location.isBlank()) {
                        throw new IllegalStateException("字幕重定向缺少 Location");
                    }
                    current = SubtitleUrlSecurity.validate(
                            SubtitleUrlSecurity.resolveRelative(current, location),
                            allowedSubtitleHosts());
                    continue;
                }
                if (!response.isSuccessful() || response.body() == null) {
                    throw new IllegalStateException("字幕下载 HTTP " + code);
                }
                byte[] bytes = response.body().bytes();
                if (bytes.length > properties.getSubtitleMaxBytes()) {
                    throw new IllegalStateException("字幕响应超过大小上限");
                }
                return new String(bytes, StandardCharsets.UTF_8);
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("字幕下载失败", e);
            }
        }
        throw new IllegalStateException("字幕重定向跳数超限");
    }

    private Set<String> allowedSubtitleHosts() {
        return Arrays.stream(accessProperties.getSubtitleAllowedHosts().split(","))
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .collect(Collectors.toSet());
    }

    /** 解析 view 接口响应；包级可见以便用固定 fixture 做单元测试，不依赖公网。 */
    BilibiliVideoMetadata parse(String body) {        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("B 站元数据 JSON 无法解析", e);
        }
        int code = root.path("code").asInt(Integer.MIN_VALUE);
        if (code != SUCCESS_CODE) {
            throw new BilibiliApiException(code, root.path("message").asText(""));
        }
        JsonNode data = root.path("data");
        String bvid = text(data, "bvid");
        long aid = data.path("aid").asLong(0L);
        String title = text(data, "title");
        String author = data.path("owner").path("name").asText(null);
        String coverUrl = text(data, "pic");
        Long durationMs = secondsToMillis(data.path("duration"));

        List<BilibiliVideoMetadata.Page> pages = new ArrayList<>();
        int index = 0;
        for (JsonNode page : data.path("pages")) {
            index++;
            // 缺少 cid 的条目**不在这里丢弃**：契约要求“任一条目缺少稳定 Unit ID 时整单失败”，
            // 静默跳过会让用户以为合集里少的那一集不存在。是否整单失败由来源 Adapter 判定。
            pages.add(new BilibiliVideoMetadata.Page(
                    text(page, "cid"),
                    page.path("page").asInt(index),
                    text(page, "part"),
                    secondsToMillis(page.path("duration"))));
        }
        if (bvid == null || bvid.isBlank()) {
            throw new IllegalStateException("B 站元数据缺少稿件 ID");
        }
        log.info("bilibili_metadata_fetched bvid={} pages={} aid={}", bvid, pages.size(), aid);
        return new BilibiliVideoMetadata(bvid, aid, title, author, coverUrl, durationMs, pages);
    }

    /**
     * 抓取稿件封面。
     *
     * <p>为什么必须服务端转存：B 站图片带 Referer 防盗链，浏览器直接 {@code <img src>} 会拿到 403，
     * 而且外链随时可能失效；本地库里的封面必须是受管对象。
     *
     * @return 图片字节与内容类型；地址为空、HTTP 失败或响应过大时返回 {@code null}（封面缺失不影响导入）
     */
    public FetchedCover fetchCover(String coverUrl) {
        if (coverUrl == null || coverUrl.isBlank()) {
            return null;
        }
        if (!coverUrl.startsWith("http://") && !coverUrl.startsWith("https://")) {
            log.warn("bilibili_cover_unsupported_scheme url={}", coverUrl);
            return null;
        }
        Request request = new Request.Builder()
                .url(coverUrl)
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("Referer", "https://www.bilibili.com/")
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                log.warn("bilibili_cover_http_failed status={}", response.code());
                return null;
            }
            byte[] bytes = response.body().bytes();
            if (bytes.length == 0 || bytes.length > MAX_COVER_BYTES) {
                log.warn("bilibili_cover_size_rejected bytes={}", bytes.length);
                return null;
            }
            String contentType = response.body().contentType() == null
                    ? DEFAULT_COVER_CONTENT_TYPE : response.body().contentType().toString();
            return new FetchedCover(bytes, contentType);
        } catch (Exception e) {
            log.warn("bilibili_cover_fetch_failed reason={}", e.getMessage());
            return null;
        }
    }

    /** 抓取到的封面内容。 */
    public record FetchedCover(byte[] bytes, String contentType) {
        public String suffix() {
            if (contentType == null) {
                return ".jpg";
            }
            String lower = contentType.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("png")) return ".png";
            if (lower.contains("webp")) return ".webp";
            if (lower.contains("gif")) return ".gif";
            return ".jpg";
        }
    }

    /** player API 返回的字幕轨道。 */
    public record PlayerSubtitle(String lan, String lanDoc, String url,
                                 Integer aiType, Integer aiStatus, Boolean locked) {
    }

    /** player API 解析结果：字幕轨道、登录提示与 View 章节。 */
    public record PlayerInfo(List<PlayerSubtitle> subtitles, boolean needLoginSubtitle,
                             List<PlayerViewPoint> viewPoints) {
        public PlayerInfo {
            subtitles = subtitles == null ? List.of() : List.copyOf(subtitles);
            viewPoints = viewPoints == null ? List.of() : List.copyOf(viewPoints);
        }
    }

    /** 登录态校验结果（nav 接口）。 */
    public record LoginStatus(boolean loggedIn, Long mid, String uname) {
        public static LoginStatus loggedOut() {
            return new LoginStatus(false, null, null);
        }
    }

    private Long secondsToMillis(JsonNode secondsNode) {
        if (!secondsNode.isNumber()) {
            return null;
        }
        double seconds = secondsNode.asDouble();
        return seconds <= 0 ? null : Math.round(seconds * 1000);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        return value.asText();
    }
}
