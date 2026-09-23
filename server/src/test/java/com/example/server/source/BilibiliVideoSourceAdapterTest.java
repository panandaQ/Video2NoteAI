package com.example.server.source;

import com.example.server.dto.VideoImportErrorCode;
import com.example.server.infrastructure.BilibiliApiException;
import com.example.server.infrastructure.BilibiliMetadataClient;
import com.example.server.infrastructure.BilibiliVideoMetadata;
import com.example.server.utils.YtDlpUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B 站单 P Adapter 契约测试，不访问公网：元数据由固定结果提供，媒体下载被替换为临时文件。
 *
 * <p>覆盖：Host 白名单、BVID/CID 身份映射、av 转 aid 交给平台归一、多分 P 的 S1 边界、
 * 平台错误码到契约错误码的翻译，以及获取路径继续复用现有下载能力。
 */
class BilibiliVideoSourceAdapterTest {

    private static final String VIDEO_URL = "https://www.bilibili.com/video/BV1xx411c7mD";
    private static final String AV_URL = "https://www.bilibili.com/video/av170001";
    private static final String COVER_URL = "https://i0.hdslb.com/bfs/archive/cover.jpg";

    @TempDir
    Path tempDir;

    private final YtDlpUtils ytDlpUtils = mock(YtDlpUtils.class);
    private final BilibiliMetadataClient metadataClient = mock(BilibiliMetadataClient.class);
    private final BilibiliVideoSourceAdapter adapter =
            new BilibiliVideoSourceAdapter(ytDlpUtils, metadataClient);

    @Test
    void supportsOnlyBilibiliHosts() {
        assertTrue(adapter.supports(URI.create(VIDEO_URL)));
        assertTrue(adapter.supports(URI.create("https://bilibili.com/video/BV1xx411c7mD")));
        assertTrue(adapter.supports(URI.create("https://m.bilibili.com/video/BV1xx411c7mD")));
        // 分享短链由本 Adapter 负责展开（契约 §5.4）。
        assertTrue(adapter.supports(URI.create("https://b23.tv/BV1GJ411x7h7")));
        assertFalse(adapter.supports(URI.create("https://www.youtube.com/watch?v=1")));
        assertFalse(adapter.supports(null));
    }

    /** 短链展开后走完全相同的识别路径：一跳落到正式地址，身份与直接提交正式链接一致。 */
    @Test
    void expandsShortLinkThenResolvesLikeCanonicalUrl() {
        when(metadataClient.fetchRedirectLocation("https://b23.tv/BV1xx411c7mD"))
                .thenReturn("https://www.bilibili.com/video/BV1xx411c7mD");
        when(metadataClient.fetchVideo("BV1xx411c7mD", null, null)).thenReturn(singlePage());

        VideoImportPlan plan = adapter.resolve(URI.create("https://b23.tv/BV1xx411c7mD"));

        assertEquals(ImportTargetType.SINGLE, plan.targetType());
        assertEquals("41820686637", plan.units().get(0).externalUnitId());
        // 规范地址永远是正式域名：短链不会被持久化成媒体身份。
        assertEquals(VIDEO_URL, plan.units().get(0).canonicalUrl());
    }

    /** 普通正式链接不做任何展开请求，避免给每个导入都增加一次网络往返。 */
    @Test
    void canonicalUrlSkipsShortLinkExpansion() {
        when(metadataClient.fetchVideo("BV1xx411c7mD", null, null)).thenReturn(singlePage());

        adapter.resolve(URI.create(VIDEO_URL));

        verify(metadataClient, never()).fetchRedirectLocation(anyString());
    }

    /** 展开后不是普通投稿（番剧、直播、动态等）：沿用既有边界，不做猜测。 */
    @Test
    void shortLinkLandingOnNonVideoPageIsUnsupported() {
        when(metadataClient.fetchRedirectLocation("https://b23.tv/abc1234"))
                .thenReturn("https://www.bilibili.com/bangumi/play/ep123456");

        VideoSourceException error = assertThrows(VideoSourceException.class,
                () -> adapter.resolve(URI.create("https://b23.tv/abc1234")));

        assertEquals(VideoImportErrorCode.SOURCE_UNSUPPORTED, error.errorCode());
        assertFalse(error.retryable());
        verify(metadataClient, never()).fetchVideo(anyString(), any(), any());
    }

    /** 跳到平台之外一律拒绝：跟随重定向不能变成任意出站请求。 */
    @Test
    void shortLinkRedirectingOffPlatformIsRejected() {
        when(metadataClient.fetchRedirectLocation("https://b23.tv/abc1234"))
                .thenReturn("http://169.254.169.254/latest/meta-data/");

        VideoSourceException error = assertThrows(VideoSourceException.class,
                () -> adapter.resolve(URI.create("https://b23.tv/abc1234")));

        assertEquals(VideoImportErrorCode.SOURCE_UNSUPPORTED, error.errorCode());
        assertFalse(error.retryable());
    }

    /** 实测：无效短链码返回 200 落地页、没有 Location —— 判为不可重试的"短链已失效"。 */
    @Test
    void shortLinkWithoutRedirectIsNonRetryableNotFound() {
        when(metadataClient.fetchRedirectLocation("https://b23.tv/aBcDeFg")).thenReturn(null);

        VideoSourceException error = assertThrows(VideoSourceException.class,
                () -> adapter.resolve(URI.create("https://b23.tv/aBcDeFg")));

        assertEquals(VideoImportErrorCode.SOURCE_NOT_FOUND, error.errorCode());
        assertFalse(error.retryable());
    }

    /** 网络失败与跳数超限都是可重试的：交给 MQ 有限重投与恢复扫描。 */
    @Test
    void shortLinkTransportFailureIsRetryable() {
        when(metadataClient.fetchRedirectLocation("https://b23.tv/abc1234"))
                .thenThrow(new IllegalStateException("短链接跳转读取失败"));

        VideoSourceException error = assertThrows(VideoSourceException.class,
                () -> adapter.resolve(URI.create("https://b23.tv/abc1234")));

        assertEquals(VideoImportErrorCode.SOURCE_TEMPORARY_UNAVAILABLE, error.errorCode());
        assertTrue(error.retryable());
    }

    @Test
    void shortLinkRedirectLoopIsBoundedAndRetryable() {
        // 每一跳都回到短链域名：必须在有限跳数内停止，不能无限跟随。
        when(metadataClient.fetchRedirectLocation(anyString()))
                .thenReturn("https://b23.tv/abc1234");

        VideoSourceException error = assertThrows(VideoSourceException.class,
                () -> adapter.resolve(URI.create("https://b23.tv/abc1234")));

        assertEquals(VideoImportErrorCode.SOURCE_TEMPORARY_UNAVAILABLE, error.errorCode());
        assertTrue(error.retryable());
        verify(metadataClient, org.mockito.Mockito.times(3)).fetchRedirectLocation(anyString());
    }

    @Test
    void resolvesSinglePageIntoUnitWithPlatformIdentity() {
        when(metadataClient.fetchVideo("BV1xx411c7mD", null, null)).thenReturn(singlePage());

        VideoImportPlan plan = adapter.resolve(URI.create(VIDEO_URL));

        assertEquals(ImportTargetType.SINGLE, plan.targetType());
        assertEquals(VideoPlatform.BILIBILI, plan.platform());
        assertNull(plan.containerId());
        assertEquals(1, plan.units().size());

        VideoSourceUnit unit = plan.units().get(0);
        assertEquals("UGC_VIDEO", unit.resourceType());
        assertEquals("BV1xx411c7mD", unit.externalResourceId());
        assertEquals("41820686637", unit.externalUnitId());
        assertEquals("谁说傲娇退环境了", unit.title());
        assertEquals("小西饱饱-", unit.author());
        assertEquals(12_260L, unit.durationMs());
        // 封面地址随单元下传：获取阶段会把它转存为受管对象，本地库条目才有缩略图。
        assertEquals(COVER_URL, unit.coverUrl());
        assertEquals(1, unit.itemOrder());
        assertEquals(VIDEO_URL, unit.canonicalUrl());
        assertEquals("BILIBILI:UGC_VIDEO:BV1xx411c7mD:41820686637", unit.sourceKey());
    }

    @Test
    void avInputIsSentAsAidSoPlatformNormalizesItToBvid() {
        when(metadataClient.fetchVideo(null, 170001L, null)).thenReturn(singlePage());

        VideoImportPlan plan = adapter.resolve(URI.create(AV_URL));

        assertEquals("BV1xx411c7mD", plan.units().get(0).externalResourceId());
        verify(metadataClient).fetchVideo(null, 170001L, null);
    }

    /**
     * D-067：多分 P 稿件未指定 `p` 时不可重试失败。
     *
     * <p>两条被明确禁止的替代行为：静默取第一 P（用户以为整稿都在库里）、自动展开全部单元
     * （一次提交变成几十个处理任务）。用户要的是"这一个视频"，就让他补一个 `?p=N`。
     */
    @Test
    void bareMultiPageWithoutPageParameterIsRejectedAsUnitRequired() {
        when(metadataClient.fetchVideo("BV1xx411c7mD", null, null)).thenReturn(multiPage());

        VideoSourceException error = assertThrows(VideoSourceException.class,
                () -> adapter.resolve(URI.create(VIDEO_URL)));

        assertEquals(VideoImportErrorCode.SOURCE_UNIT_REQUIRED, error.errorCode());
        assertFalse(error.retryable(), "补 p 参数才能解决，重试没有意义");
        // 文案必须告诉用户怎么改，且不能泄漏平台原始响应。
        assertTrue(error.getMessage().contains("2 个分 P"), error.getMessage());
    }

    @Test
    void explicitPageSelectsOnlyThatUnitAndKeepsPageQuery() {
        when(metadataClient.fetchVideo("BV1xx411c7mD", null, null)).thenReturn(multiPage());

        VideoImportPlan plan = adapter.resolve(URI.create(VIDEO_URL + "?p=2"));

        assertEquals(ImportTargetType.SINGLE, plan.targetType());
        assertNull(plan.containerId());
        assertEquals(1, plan.units().size());
        assertEquals("30000002", plan.units().get(0).externalUnitId());
        assertEquals(VIDEO_URL + "?p=2", plan.units().get(0).canonicalUrl());
    }

    /** 指定分 P 时仍要整单校验身份：缺 CID 的那一集不能被"降级导入"。 */
    @Test
    void pageWithoutCidFailsResolveForExplicitPage() {
        BilibiliVideoMetadata broken = new BilibiliVideoMetadata("BV1xx411c7mD", 170001L, "多分 P 稿件", "某科普UP", COVER_URL,
                60_000L, List.of(
                new BilibiliVideoMetadata.Page("30000001", 1, "第一集", 30_000L),
                new BilibiliVideoMetadata.Page(null, 2, "缺少 cid 的一集", 30_000L)));
        when(metadataClient.fetchVideo("BV1xx411c7mD", null, null)).thenReturn(broken);

        VideoSourceException error = assertThrows(VideoSourceException.class,
                () -> adapter.resolve(URI.create(VIDEO_URL + "?p=2")));

        // 没有稳定单元身份就不能建档：这不是"内容不受支持"，而是元数据不完整。
        assertEquals(VideoImportErrorCode.SOURCE_METADATA_INVALID, error.errorCode());
        assertFalse(error.retryable());
    }

    @Test
    void explicitPageOutsideRangeIsNonRetryableNotFound() {
        when(metadataClient.fetchVideo("BV1xx411c7mD", null, null)).thenReturn(multiPage());

        VideoSourceException error = assertThrows(VideoSourceException.class,
                () -> adapter.resolve(URI.create(VIDEO_URL + "?p=9")));

        assertEquals(VideoImportErrorCode.SOURCE_NOT_FOUND, error.errorCode());
        assertFalse(error.retryable());
    }

    @Test
    void explicitPageOneOnSinglePageVideoKeepsRequestedQueryAndVideoTitle() {
        when(metadataClient.fetchVideo("BV1xx411c7mD", null, null)).thenReturn(singlePage());

        VideoImportPlan plan = adapter.resolve(URI.create(VIDEO_URL + "?p=1"));

        assertEquals(ImportTargetType.SINGLE, plan.targetType());
        // 单 P 稿件的分 P 名常为“正片”，此时用稿件标题更有信息量。
        assertEquals("谁说傲娇退环境了", plan.units().get(0).title());
        assertEquals(VIDEO_URL + "?p=1", plan.units().get(0).canonicalUrl());
    }

    @Test
    void rejectsEmptyPageListAsInvalidMetadata() {
        when(metadataClient.fetchVideo("BV1xx411c7mD", null, null))
                .thenReturn(new BilibiliVideoMetadata("BV1xx411c7mD", 1L, "无分 P", "作者", null, null, List.of()));

        VideoSourceException error = assertThrows(VideoSourceException.class,
                () -> adapter.resolve(URI.create(VIDEO_URL)));

        assertEquals(VideoImportErrorCode.SOURCE_METADATA_INVALID, error.errorCode());
    }

    @Test
    void urlWithoutVideoIdentityIsUnsupported() {
        VideoSourceException error = assertThrows(VideoSourceException.class,
                () -> adapter.resolve(URI.create("https://www.bilibili.com/bangumi/play/ep123")));

        assertEquals(VideoImportErrorCode.SOURCE_UNSUPPORTED, error.errorCode());
        verify(metadataClient, never()).fetchVideo(anyString(), any(), any());
    }

    /**
     * 只认 {@code /video/BV...} 路径形态：查询串里出现 BV 不能当作视频身份。
     *
     * <p>实测过的反例：{@code medialist/play/...?bvid=BV...} 这种合集播放页在旧实现里会被当成该视频导入，
     * 而查询串里的 BV 未必是用户想导入的那一个。
     */
    @Test
    void bvOnlyInQueryStringIsNotAcceptedAsVideoIdentity() {
        for (String url : List.of(
                "https://www.bilibili.com/medialist/play/999999?bvid=BV1xx411c7mD",
                "https://www.bilibili.com/list/watchlater?bvid=BV1xx411c7mD",
                "https://www.bilibili.com/bangumi/play/ep123?bvid=BV1xx411c7mD")) {
            VideoSourceException error = assertThrows(VideoSourceException.class,
                    () -> adapter.resolve(URI.create(url)), url);

            assertEquals(VideoImportErrorCode.SOURCE_UNSUPPORTED, error.errorCode(), url);
            assertFalse(error.retryable(), url);
        }
        verify(metadataClient, never()).fetchVideo(anyString(), any(), any());
        verify(metadataClient, never()).fetchRedirectLocation(anyString());
    }

    /** 尾斜杠是平台真实存在的形态（实测跳转落点就带尾斜杠），必须接受。 */
    @Test
    void trailingSlashOnVideoPathIsAccepted() {
        when(metadataClient.fetchVideo("BV1xx411c7mD", null, null)).thenReturn(singlePage());

        VideoImportPlan plan = adapter.resolve(URI.create(VIDEO_URL + "/"));

        assertEquals("41820686637", plan.units().get(0).externalUnitId());
    }

    /** av 前缀接受大小写混写（平台历史链接存在 AV 形态），但 BV 号本身大小写敏感。 */
    @Test
    void avPrefixIsCaseInsensitiveButBvIsNot() {
        when(metadataClient.fetchVideo(null, 170001L, null)).thenReturn(singlePage());

        VideoImportPlan plan = adapter.resolve(URI.create("https://www.bilibili.com/video/AV170001"));

        assertEquals("41820686637", plan.units().get(0).externalUnitId());

        VideoSourceException error = assertThrows(VideoSourceException.class,
                () -> adapter.resolve(URI.create(VIDEO_URL.replace("BV1xx", "bv1xx"))));
        assertEquals(VideoImportErrorCode.SOURCE_UNSUPPORTED, error.errorCode());
    }

    @Test
    void mapsPlatformNotFoundToNonRetryableSourceNotFound() {
        when(metadataClient.fetchVideo("BV1xx411c7mD", null, null))
                .thenThrow(new BilibiliApiException(BilibiliApiException.NOT_FOUND, "啥都木有"));

        VideoSourceException error = assertThrows(VideoSourceException.class,
                () -> adapter.resolve(URI.create(VIDEO_URL)));

        assertEquals(VideoImportErrorCode.SOURCE_NOT_FOUND, error.errorCode());
        assertFalse(error.retryable());
    }

    @Test
    void mapsPermissionFailureToNonRetryableAccessDenied() {
        when(metadataClient.fetchVideo("BV1xx411c7mD", null, null))
                .thenThrow(new BilibiliApiException(BilibiliApiException.VIDEO_NOT_VISIBLE, "稿件不可见"));

        VideoSourceException error = assertThrows(VideoSourceException.class,
                () -> adapter.resolve(URI.create(VIDEO_URL)));

        assertEquals(VideoImportErrorCode.SOURCE_ACCESS_DENIED, error.errorCode());
        assertFalse(error.retryable());
    }

    @Test
    void mapsTransportFailureToRetryableTemporaryUnavailable() {
        when(metadataClient.fetchVideo("BV1xx411c7mD", null, null))
                .thenThrow(new IllegalStateException("B 站元数据接口调用失败"));

        VideoSourceException error = assertThrows(VideoSourceException.class,
                () -> adapter.resolve(URI.create(VIDEO_URL)));

        assertEquals(VideoImportErrorCode.SOURCE_TEMPORARY_UNAVAILABLE, error.errorCode());
        assertTrue(error.retryable());
    }

    @Test
    void acquireReusesExistingDownloaderAndReturnsManagedTempFile() throws Exception {
        Path downloaded = Files.writeString(tempDir.resolve("unit.mp4"), "video-bytes");
        VideoSourceUnit unit = new VideoSourceUnit(VideoPlatform.BILIBILI, "UGC_VIDEO",
                "BV1xx411c7mD", "41820686637", VIDEO_URL, "标题", "作者", 1_000L, COVER_URL, 1);
        when(ytDlpUtils.downloadVideo(VIDEO_URL, null, null)).thenReturn(downloaded.toFile());

        AcquiredMedia media = adapter.acquire(unit);

        assertEquals(downloaded.toFile(), media.file());
        assertEquals("video/mp4", media.contentType());
        assertEquals(11L, media.sizeBytes());
        verify(metadataClient, never()).fetchVideo(any(), any(), any());
    }

    @Test
    void downloadFailureIsClassifiedAsRetryableByDefault() throws Exception {
        VideoSourceUnit unit = new VideoSourceUnit(VideoPlatform.BILIBILI, "UGC_VIDEO",
                "BV1xx411c7mD", "41820686637", VIDEO_URL, "标题", "作者", 1_000L, COVER_URL, 1);
        when(ytDlpUtils.downloadVideo(anyString(), any(), any())).thenThrow(new IllegalStateException("视频链接下载超时"));

        VideoSourceException error = assertThrows(VideoSourceException.class, () -> adapter.acquire(unit));

        assertEquals(VideoImportErrorCode.SOURCE_TEMPORARY_UNAVAILABLE, error.errorCode());
        assertTrue(error.retryable());
    }

    /** 封面交给平台网关抓取（UA/Referer 与尺寸上限在那里），Adapter 只做领域类型转换。 */
    @Test
    void coverFetchIsDelegatedToPlatformGateway() {
        when(metadataClient.fetchCover(COVER_URL)).thenReturn(
                new BilibiliMetadataClient.FetchedCover(new byte[]{1, 2, 3}, "image/webp"));

        VideoSourceAdapter.AcquiredCover cover = adapter.fetchCover(COVER_URL);

        assertEquals(3, cover.bytes().length);
        assertEquals("image/webp", cover.contentType());
        assertEquals(".webp", cover.suffix());
    }

    /** 平台没有封面时不得抛错，只返回 null 让导入继续。 */
    @Test
    void missingCoverYieldsNull() {
        when(metadataClient.fetchCover(null)).thenReturn(null);

        assertNull(adapter.fetchCover(null));
    }

    /** 用户级 Cookie 必须原样传给元数据网关，登录可见稿件才能解析。 */
    @Test
    void resolvePassesUserCookieToMetadataClient() {
        when(metadataClient.fetchVideo("BV1xx411c7mD", null, "SESSDATA=abc"))
                .thenReturn(singlePage());

        adapter.resolve(URI.create(VIDEO_URL), "SESSDATA=abc");

        verify(metadataClient).fetchVideo("BV1xx411c7mD", null, "SESSDATA=abc");
    }

    /** 强制登录：Cookie 有效性交给平台 nav 接口的登录态校验。 */
    @Test
    void cookieValidityIsDelegatedToPlatformLoginCheck() {
        when(metadataClient.checkLogin("SESSDATA=valid"))
                .thenReturn(new BilibiliMetadataClient.LoginStatus(true, 1L, "u"));
        when(metadataClient.checkLogin("SESSDATA=expired"))
                .thenReturn(BilibiliMetadataClient.LoginStatus.loggedOut());

        assertTrue(adapter.isCookieValid("SESSDATA=valid"));
        assertFalse(adapter.isCookieValid("SESSDATA=expired"));
    }

    /** 清晰度与用户级 Cookie 必须原样传给下载器。 */
    @Test
    void acquirePassesHeightAndCookieToDownloader() throws Exception {
        Path downloaded = Files.writeString(tempDir.resolve("unit.mp4"), "video-bytes");
        VideoSourceUnit unit = new VideoSourceUnit(VideoPlatform.BILIBILI, "UGC_VIDEO",
                "BV1xx411c7mD", "41820686637", VIDEO_URL, "标题", "作者", 1_000L, COVER_URL, 1);
        when(ytDlpUtils.downloadVideo(VIDEO_URL, 720, "SESSDATA=abc")).thenReturn(downloaded.toFile());

        adapter.acquire(unit, 720, "SESSDATA=abc");

        verify(ytDlpUtils).downloadVideo(VIDEO_URL, 720, "SESSDATA=abc");
    }

    private BilibiliVideoMetadata singlePage() {
        return new BilibiliVideoMetadata("BV1xx411c7mD", 170001L, "谁说傲娇退环境了", "小西饱饱-", COVER_URL,
                12_260L, List.of(new BilibiliVideoMetadata.Page("41820686637", 1, "正片", 12_260L)));
    }

    private BilibiliVideoMetadata multiPage() {
        return new BilibiliVideoMetadata("BV1xx411c7mD", 170001L, "多分 P 稿件", "某科普UP", COVER_URL, 60_000L,
                List.of(new BilibiliVideoMetadata.Page("30000001", 1, "第一集", 30_000L),
                        new BilibiliVideoMetadata.Page("30000002", 2, "第二集", 30_000L)));
    }
}
