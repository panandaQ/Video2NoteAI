package com.example.server.source;

import com.example.server.dto.VideoImportErrorCode;
import com.example.server.infrastructure.BilibiliApiException;
import com.example.server.infrastructure.BilibiliMetadataClient;
import com.example.server.infrastructure.BilibiliVideoMetadata;
import com.example.server.utils.VideoImportKeys;
import com.example.server.utils.YtDlpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B 站来源实现：普通投稿单集、多分 P 与分享短链。
 *
 * <p>职责边界：把 URL 变成平台无关的 {@link VideoImportPlan}，再获取单个 Unit 的媒体。
 * 不依赖 MQ、Redis、数据库事务或 AI。
 *
 * <p>数据来源分工：
 * <ul>
 *   <li>单元身份（BVID + CID）、标题、作者、封面、分 P 结构来自平台 view 接口；</li>
 *   <li>分享短链（{@code b23.tv}）先由平台网关读一跳重定向展开成正式地址，再走同一套识别；</li>
 *   <li>媒体获取复用 {@link YtDlpUtils#downloadVideo(String)}，保留其中的 {@code --no-playlist} 保护。</li>
 * </ul>
 * 之所以不用进程探测取元数据：{@code yt-dlp --dump-single-json} 的输出里没有 {@code cid}，
 * 而 CID 是媒体唯一键的必要组成。
 *
 * <p>内容边界（D-050 / D-067）：只支持**一个明确的普通投稿单元**。公开合集、番剧/影视/课程 PGC、
 * 音频、直播一律返回不可重试的 {@code SOURCE_UNSUPPORTED}；多分 P 稿件必须在链接上带 {@code ?p=N}
 * （否则不可重试的 {@code SOURCE_UNIT_REQUIRED}），既不静默取第一 P，也不自动展开整稿。
 * 短链支持不改变这一点——它只把短链还原成正式地址。
 */
@Component
public class BilibiliVideoSourceAdapter implements VideoSourceAdapter {

    private static final Logger log = LoggerFactory.getLogger(BilibiliVideoSourceAdapter.class);

    /** 只允许 B 站自身域名与分享短链域名；其他平台 Adapter 必须声明自己的 Host 规则。 */
    private static final Set<String> SUPPORTED_HOSTS =
            Set.of("bilibili.com", "www.bilibili.com", "m.bilibili.com", "b23.tv");

    /**
     * 需要先展开的分享短链域名（契约 §5.4）。
     *
     * <p>展开后必须落到 {@link #SUPPORTED_HOSTS} 里的正式域名才算成功：跟随重定向不能变成任意出站请求。
     */
    private static final Set<String> SHORT_LINK_HOSTS = Set.of("b23.tv");

    /** 短链最大跳数；实测有效短链 1 跳即可落到正式域名。 */
    private static final int MAX_SHORT_LINK_HOPS = 3;

    /**
     * 投稿地址的**路径**形态：{@code /video/BV...} 或 {@code /video/av...}，允许一个尾斜杠。
     *
     * <p>只匹配 path，不再对整串 URL 做正则。原因（实测）：{@code identifier} 原先在整串里找 BV，
     * 于是 {@code https://www.bilibili.com/medialist/play/999999?bvid=BV1xx411c7mD} 这类"路径不是投稿页、
     * BV 只出现在查询串"的页面会被当成该视频导入——查询串里的 BV 未必是用户想导入的那一个。
     * BV 号大小写敏感，因此只有 {@code av} 前缀允许大小写混写。
     */
    private static final Pattern VIDEO_PATH_PATTERN =
            Pattern.compile("^/video/(?:(BV[0-9A-Za-z]{10})|[aA][vV](\\d+))/?$");

    /** 资源类型固定值，进入媒体唯一身份与 sourceKey。 */
    static final String RESOURCE_TYPE = "UGC_VIDEO";

    /**
     * yt-dlp 对平台侧失败使用同一个退出码，只能按输出归类；未命中任何特征词时按可重试处理，
     * 避免把临时网络问题误判成永久失败。
     */
    private static final List<String> NOT_FOUND_MARKERS =
            List.of("http error 404", "not found", "does not exist", "不存在", "已失效", "稿件不可见");
    private static final List<String> LOGIN_MARKERS =
            List.of("login", "sign in", "需要登录", "大会员", "仅限");
    private static final List<String> DENIED_MARKERS =
            List.of("private", "forbidden", "403", "付费", "无权限", "权限不足");

    private final YtDlpUtils ytDlpUtils;
    private final BilibiliMetadataClient metadataClient;

    public BilibiliVideoSourceAdapter(YtDlpUtils ytDlpUtils, BilibiliMetadataClient metadataClient) {
        this.ytDlpUtils = ytDlpUtils;
        this.metadataClient = metadataClient;
    }

    @Override
    public boolean supports(URI input) {
        if (input == null || input.getHost() == null) {
            return false;
        }
        return SUPPORTED_HOSTS.contains(input.getHost().toLowerCase(Locale.ROOT));
    }

    @Override
    public VideoImportPlan resolve(URI input, String cookie) {
        // 短链必须先展开成正式地址，再走同一套身份识别：短链不是第二种内容形态。
        URI resolvedInput = expandShortLink(input);
        VideoIdentifier identifier = identifier(resolvedInput);
        BilibiliVideoMetadata metadata;
        try {
            metadata = metadataClient.fetchVideo(identifier.bvid(), identifier.aid(), cookie);
        } catch (BilibiliApiException | IllegalStateException e) {
            throw translateMetadataFailure(e);
        }
        List<BilibiliVideoMetadata.Page> pages = metadata.pages();
        if (pages.isEmpty()) {
            throw new VideoSourceException(VideoImportErrorCode.SOURCE_METADATA_INVALID,
                    "平台未返回可播放单元");
        }

        // D-067：一次导入只接受一个明确的可播放单元。
        // 明确 p=N → 只取该分 P；单 P 稿件 → 直接取唯一单元；多分 P 且没给 p → 不可重试失败。
        // 为什么不猜：静默导入第一 P 会让用户以为"整个稿件都在库里"，自动展开又会把一次提交变成
        // 几十个单元的处理量——两者都超出"粘贴一个视频链接"的语义。让用户补一个 ?p=N 是唯一诚实的做法。
        Integer requestedPage = requestedPage(resolvedInput);
        if (requestedPage == null && pages.size() > 1) {
            throw new VideoSourceException(VideoImportErrorCode.SOURCE_UNIT_REQUIRED,
                    "该稿件有 " + pages.size() + " 个分 P，请在链接上指定 ?p=N 后再导入");
        }
        if (requestedPage != null) {
            BilibiliVideoMetadata.Page page = pages.stream()
                    .filter(candidate -> candidate.page() == requestedPage)
                    .findFirst()
                    .orElseThrow(() -> new VideoSourceException(VideoImportErrorCode.SOURCE_NOT_FOUND,
                            "指定的分 P 不存在"));
            return new VideoImportPlan(ImportTargetType.SINGLE, VideoPlatform.BILIBILI,
                    null, null, List.of(toSourceUnit(metadata, page, true)));
        }
        return new VideoImportPlan(ImportTargetType.SINGLE, VideoPlatform.BILIBILI,
                null, null, List.of(toSourceUnit(metadata, pages.get(0), false)));
    }

    private boolean isAllowedHost(URI uri) {
        return uri != null && uri.getHost() != null
                && SUPPORTED_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT));
    }

    private boolean isShortLinkHost(URI uri) {
        return uri != null && uri.getHost() != null
                && SHORT_LINK_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT));
    }

    /**
     * 展开分享短链（契约 §5.4：服务端解析最终地址后再应用普通链接规则）。
     *
     * <p>三条不可放宽的约束：
     * <ul>
     *   <li><b>逐跳校验主机</b>：每一跳的 {@code Location} 都必须落在 B 站正式域名内，跳到平台之外一律
     *       判 {@code SOURCE_UNSUPPORTED}，绝不让"跟随重定向"变成任意出站请求；</li>
     *   <li><b>只读一跳</b>：由平台网关用不跟随重定向的客户端返回 {@code Location}，策略留在 Adapter；</li>
     *   <li><b>不猜内容</b>：展开后不是普通投稿（番剧、直播、动态、专栏、个人空间等）仍然走既有的
     *       {@code SOURCE_UNSUPPORTED} 分支，短链支持不扩大内容边界。</li>
     * </ul>
     *
     * <p>失败分流：没有 {@code Location}（实测无效短链码返回 200 落地页）→ 不可重试的
     * {@code SOURCE_NOT_FOUND}；网络失败或跳数超限 → 可重试的 {@code SOURCE_TEMPORARY_UNAVAILABLE}，
     * 交给 MQ 有限重投与恢复扫描。
     *
     * @return 展开后的正式地址；输入本身不是短链时原样返回
     */
    private URI expandShortLink(URI input) {
        if (!isShortLinkHost(input)) {
            return input;
        }
        URI current = input;
        for (int hop = 1; hop <= MAX_SHORT_LINK_HOPS; hop++) {
            String location;
            try {
                location = metadataClient.fetchRedirectLocation(current.toString());
            } catch (IllegalStateException e) {
                throw new VideoSourceException(VideoImportErrorCode.SOURCE_TEMPORARY_UNAVAILABLE,
                        "短链接解析失败", e);
            }
            if (location == null || location.isBlank()) {
                throw new VideoSourceException(VideoImportErrorCode.SOURCE_NOT_FOUND, "短链接已失效");
            }
            URI next;
            try {
                next = URI.create(current.resolve(location).toString());
            } catch (IllegalArgumentException e) {
                throw new VideoSourceException(VideoImportErrorCode.SOURCE_UNSUPPORTED,
                        "短链接跳转地址非法", e);
            }
            if (!isAllowedHost(next)) {
                throw new VideoSourceException(VideoImportErrorCode.SOURCE_UNSUPPORTED,
                        "短链接跳转到了不受支持的地址");
            }
            current = next;
            if (!isShortLinkHost(current)) {
                log.info("bilibili_short_link_expanded hops={} host={}", hop, current.getHost());
                return current;
            }
        }
        throw new VideoSourceException(VideoImportErrorCode.SOURCE_TEMPORARY_UNAVAILABLE,
                "短链接跳转层数过多");
    }

    /** 读取 {@code ?p=N}；没有该参数、非数字或小于 1 时返回 {@code null}。 */
    private Integer requestedPage(URI input) {        String query = input.getRawQuery();
        if (query == null || query.isBlank()) {
            return null;
        }
        for (String pair : query.split("&")) {
            int separator = pair.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            if (!"p".equalsIgnoreCase(pair.substring(0, separator))) {
                continue;
            }
            try {
                int page = Integer.parseInt(pair.substring(separator + 1));
                return page >= 1 ? page : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    @Override
    public AcquiredMedia acquire(VideoSourceUnit source, Integer height, String cookie) {
        try {
            File file = ytDlpUtils.downloadVideo(source.canonicalUrl(), height, cookie);
            log.info("bilibili_unit_acquired sourceKeyHash={} bytes={}",
                    VideoImportKeys.sourceHash(source.sourceKey()), file.length());
            return new AcquiredMedia(file, "video/mp4", file.length());
        } catch (Exception e) {
            throw translateProcessFailure(e);
        }
    }

    @Override
    public boolean isCookieValid(String cookie) {
        return metadataClient.checkLogin(cookie).loggedIn();
    }

    /** 封面转存：平台网关已处理 UA/Referer 与尺寸上限，这里只做领域类型转换。 */
    @Override
    public AcquiredCover fetchCover(String coverUrl) {
        BilibiliMetadataClient.FetchedCover fetched = metadataClient.fetchCover(coverUrl);
        if (fetched == null) {
            return null;
        }
        return new AcquiredCover(fetched.bytes(), fetched.contentType(), fetched.suffix());
    }

    /**
     * 把平台分 P 映射为可播放单元。
     *
     * @param keepPageQuery 是否在规范 URL 上保留 {@code ?p=N}：多分 P 稿件与用户明确指定的分 P
     *                      必须保留，否则同一稿件的不同单元会得到相同规范地址
     */
    private VideoSourceUnit toSourceUnit(BilibiliVideoMetadata metadata,
                                         BilibiliVideoMetadata.Page page,
                                         boolean keepPageQuery) {
        String cid = page.cid();
        if (cid == null || cid.isBlank()) {
            // 缺少 CID 就无法区分同一稿件的不同分 P，属于契约定义的不可重试元数据错误。
            throw new VideoSourceException(VideoImportErrorCode.SOURCE_METADATA_INVALID,
                    "平台元数据缺少可播放单元 ID");
        }
        String title = unitTitle(metadata, page);
        Long durationMs = page.durationMs() != null ? page.durationMs() : metadata.durationMs();
        int pageNumber = page.page() <= 0 ? 1 : page.page();
        String canonicalUrl = "https://www.bilibili.com/video/" + metadata.bvid()
                + (keepPageQuery ? "?p=" + pageNumber : "");
        return new VideoSourceUnit(
                VideoPlatform.BILIBILI,
                RESOURCE_TYPE,
                metadata.bvid(),
                cid,
                canonicalUrl,
                title,
                metadata.author(),
                durationMs,
                metadata.coverUrl(),
                pageNumber);
    }

    /**
     * 单元标题：多分 P 稿件用分 P 名（更有信息量），单 P 稿件用稿件标题
     * （此时平台常把分 P 名固定写成“正片”，对用户没有意义）。
     */
    private String unitTitle(BilibiliVideoMetadata metadata, BilibiliVideoMetadata.Page page) {
        boolean multiPage = metadata.pages().size() > 1;
        String part = page.part();
        if (multiPage && part != null && !part.isBlank()) {
            return part;
        }
        String title = metadata.title();
        if (title != null && !title.isBlank()) {
            return title;
        }
        return metadata.bvid();
    }

    /**
     * 从 URL 路径提取稿件标识；av 交给平台归一为 BVID，客户端不做本地换算。
     *
     * <p>只接受 {@code /video/BV...} 与 {@code /video/av...}：其他页面形态（稍后再看、合集播放页、
     * 番剧、动态等）即使查询串里带着 BV 也一律不支持，不做猜测（D-050 边界）。
     */
    private VideoIdentifier identifier(URI input) {
        String path = input.getPath() == null ? "" : input.getPath();
        Matcher video = VIDEO_PATH_PATTERN.matcher(path);
        if (video.matches()) {
            // 两个分支各自一个捕获组：BV 号原样使用，av 号只取数字部分交给平台归一。
            String bvid = video.group(1);
            if (bvid != null) {
                return new VideoIdentifier(bvid, null);
            }
            return new VideoIdentifier(null, Long.valueOf(video.group(2)));
        }
        throw new VideoSourceException(VideoImportErrorCode.SOURCE_UNSUPPORTED,
                "仅支持 /video/BV... 或 /video/av... 形式的投稿地址");
    }

    private record VideoIdentifier(String bvid, Long aid) {
    }

    /** 平台接口失败翻译成契约错误码：确定性错误不可重试，其余按可重试处理。 */
    private VideoSourceException translateMetadataFailure(Exception error) {
        if (error instanceof BilibiliApiException api) {
            return switch (api.code()) {
                case BilibiliApiException.NOT_FOUND -> new VideoSourceException(
                        VideoImportErrorCode.SOURCE_NOT_FOUND, "视频不存在或已被删除", api);
                case BilibiliApiException.ACCESS_DENIED, BilibiliApiException.VIDEO_NOT_VISIBLE ->
                        new VideoSourceException(VideoImportErrorCode.SOURCE_ACCESS_DENIED,
                                "该视频不可访问", api);
                case BilibiliApiException.INVALID_REQUEST -> new VideoSourceException(
                        VideoImportErrorCode.SOURCE_METADATA_INVALID, "平台拒绝了该稿件查询", api);
                case BilibiliApiException.VIDEO_UNDER_REVIEW -> new VideoSourceException(
                        VideoImportErrorCode.SOURCE_TEMPORARY_UNAVAILABLE, "稿件正在审核中", api);
                default -> new VideoSourceException(VideoImportErrorCode.SOURCE_TEMPORARY_UNAVAILABLE,
                        "来源暂时不可用", api);
            };
        }
        String message = error.getMessage() == null ? "" : error.getMessage();
        if (message.contains("JSON") || message.contains("缺少稿件 ID")) {
            return new VideoSourceException(VideoImportErrorCode.SOURCE_METADATA_INVALID,
                    "平台元数据不可用", error);
        }
        return new VideoSourceException(VideoImportErrorCode.SOURCE_TEMPORARY_UNAVAILABLE,
                "来源暂时不可用", error);
    }

    /** 下载进程失败翻译：先看平台侧确定性特征，再回退到可重试。 */
    private VideoSourceException translateProcessFailure(Exception error) {
        if (error instanceof VideoSourceException sourceError) {
            return sourceError;
        }
        String message = error.getMessage() == null ? "" : error.getMessage().toLowerCase(Locale.ROOT);
        for (String marker : LOGIN_MARKERS) {
            if (message.contains(marker)) {
                return new VideoSourceException(VideoImportErrorCode.SOURCE_LOGIN_REQUIRED,
                        "该视频需要登录后才能访问", error);
            }
        }
        for (String marker : DENIED_MARKERS) {
            if (message.contains(marker)) {
                return new VideoSourceException(VideoImportErrorCode.SOURCE_ACCESS_DENIED,
                        "该视频不可访问", error);
            }
        }
        for (String marker : NOT_FOUND_MARKERS) {
            if (message.contains(marker)) {
                return new VideoSourceException(VideoImportErrorCode.SOURCE_NOT_FOUND,
                        "视频不存在或已被删除", error);
            }
        }
        return new VideoSourceException(VideoImportErrorCode.SOURCE_TEMPORARY_UNAVAILABLE,
                "来源暂时不可用", error);
    }
}
