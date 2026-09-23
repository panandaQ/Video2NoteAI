package com.example.server.service.ingest;

import com.example.server.config.VideoImportProperties;
import com.example.server.dto.AnalysisInputManifest;
import com.example.server.dto.VideoChapter;
import com.example.server.entity.MediaFile;
import com.example.server.infrastructure.BilibiliMetadataClient;
import com.example.server.infrastructure.BilibiliMetadataClient.PlayerInfo;
import com.example.server.infrastructure.BilibiliMetadataClient.PlayerSubtitle;
import com.example.server.service.BilibiliCredentialService;
import com.example.server.service.ChapterNormalizer;
import com.example.server.service.SubtitleTrackSelector;
import com.example.server.source.VideoPlatform;
import com.example.server.utils.MinioUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 统一补充资产服务（计划 §4.4）：新下载、已有对象、跨用户共享字节三条路径在提交默认笔记前
 * 都经过 {@link #ensureArtifacts(MediaFile)}，为同一份内容补齐版本化分析输入
 * （{@code analysis-input/v2/manifest.json}、{@code subtitle-zh.json}、{@code chapters.json}）。
 *
 * <p>关键语义：
 * <ul>
 *   <li>完整 manifest 命中 = 零探测复用；缺失时按 contentAsset 内容锁保证全系统只补一次（§6.1）；</li>
 *   <li>有效 {@code view_points} 是强约束：chapters.json 必须先原子写入，失败抛可重试异常，
 *       不提交默认笔记；空数组 → {@code ABSENT}；整份非法 → 显式 {@code INVALID}（D-074）；</li>
 *   <li>字幕是可降级资产：下载/解析失败记录 {@code FAILED}，后续走 ASR，不阻塞媒体；</li>
 *   <li>manifest 最后发布；同版本对象首写者胜出，禁止覆盖已发布资产。</li>
 * </ul>
 */
@Service
public class ContentArtifactEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(ContentArtifactEnrichmentService.class);
    private static final String JSON_CONTENT_TYPE = "application/json";
    private static final long ARTIFACT_LOCK_WAIT_SECONDS = 3;

    /** 分析输入闸门的显式结果：锁忙仍是未就绪，不能与成功共用普通返回。 */
    public enum EnrichmentResult {
        READY,
        IN_PROGRESS
    }

    private final MinioUtils minioUtils;
    private final BilibiliMetadataClient metadataClient;
    private final ImportUnitLock unitLock;
    private final VideoImportProperties properties;
    private final ObjectMapper objectMapper;
    private final BilibiliCredentialService credentialService;

    public ContentArtifactEnrichmentService(MinioUtils minioUtils,
                                            BilibiliMetadataClient metadataClient,
                                            ImportUnitLock unitLock,
                                            VideoImportProperties properties,
                                            ObjectMapper objectMapper,
                                            BilibiliCredentialService credentialService) {
        this.minioUtils = minioUtils;
        this.metadataClient = metadataClient;
        this.unitLock = unitLock;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.credentialService = credentialService;
    }

    public static String artifactPrefix(String contentHash) {
        return "video-import/content/" + contentHash + "/analysis-input/v2";
    }

    public static String manifestObject(String contentHash) {
        return artifactPrefix(contentHash) + "/manifest.json";
    }

    public static String subtitleObject(String contentHash) {
        return artifactPrefix(contentHash) + "/subtitle-zh.json";
    }

    public static String chaptersObject(String contentHash) {
        return artifactPrefix(contentHash) + "/chapters.json";
    }

    /**
     * 补充（或复用）媒体所属内容的 V2 分析输入资产。
     *
     * @return manifest 已就绪时返回 {@link EnrichmentResult#READY}；别的执行者仍持有内容锁且
     *         短等后 manifest 仍不存在时返回 {@link EnrichmentResult#IN_PROGRESS}
     * @throws ArtifactEnrichmentException 章节对象或 manifest 写入失败等可重试错误：
     *         调用方保持媒体可重试状态，禁止提交默认笔记（§4.4/§6.1）
     */
    public EnrichmentResult ensureArtifacts(MediaFile media) {
        if (media == null) {
            return EnrichmentResult.READY;
        }
        if (media.getContentHash() == null || media.getContentHash().isBlank()) {
            // 不能静默跳过：补充资产缺失在下游只表现为"没有字幕、没有章节"，
            // 不落痕时只能靠翻对象存储才发现（D-098 的现场正是这样被漏掉的）。
            // 调用方（submitNote 的补充闸门）仍按"补充完成"继续，不阻塞默认笔记。
            log.warn("video_import_artifact_enrichment_skipped mediaId={} reason=content_hash_absent",
                    media.getId());
            return EnrichmentResult.READY;
        }
        if (media.getPlatform() != null && !VideoPlatform.BILIBILI.equals(media.getPlatform())) {
            return EnrichmentResult.READY; // 补充资产只对 B 站来源有意义；其他平台走 ASR 兜底
        }
        String contentHash = media.getContentHash();
        String manifestName = manifestObject(contentHash);
        if (manifestExists(manifestName)) {
            return EnrichmentResult.READY; // 完整 manifest 零探测、零抢锁复用
        }
        try (ImportUnitLock.Handle lock = unitLock.tryLockContent(
                media.getContentAssetId(), ARTIFACT_LOCK_WAIT_SECONDS, TimeUnit.SECONDS)) {
            if (!lock.acquired()) {
                // 等锁期间对方可能已经完成发布但还没释放外层可重入内容锁；以 manifest 再判一次。
                if (manifestExists(manifestName)) {
                    return EnrichmentResult.READY;
                }
                log.info("video_import_artifact_lock_busy mediaId={} waitSeconds={}",
                        media.getId(), ARTIFACT_LOCK_WAIT_SECONDS);
                return EnrichmentResult.IN_PROGRESS;
            }
            // 锁外首次检查与成功获得锁之间，上一位写者也可能刚好完成。
            if (manifestExists(manifestName)) {
                return EnrichmentResult.READY;
            }

            String cookie = credentialService.getCookie(media.getUserId());
            PlayerInfo player = metadataClient.fetchPlayerInfo(
                    media.getExternalResourceId(), null, media.getExternalUnitId(), cookie);

            ChapterOutcome chapters = resolveChapters(media, player);
            SubtitleOutcome subtitle = resolveSubtitle(media, player, cookie);

            AnalysisInputManifest manifest = new AnalysisInputManifest(
                    1,
                    AnalysisInputManifest.MANIFEST_VERSION,
                    media.getPlatform() == null ? null : media.getPlatform().name(),
                    media.getResourceType(),
                    media.getExternalResourceId(),
                    media.getExternalUnitId(),
                    contentHash,
                    AnalysisInputManifest.PROBE_OK,
                    subtitle.status(),
                    subtitle.lan(),
                    subtitle.aiType(),
                    chapters.status(),
                    chapters.count(),
                    System.currentTimeMillis(),
                    subtitleObject(contentHash),
                    chaptersObject(contentHash));
            writeJsonObject(manifestName, manifest, true);
            log.info("video_import_artifacts_published contentHash={} subtitle={} chapters={}",
                    contentHash, subtitle.status(), chapters.status());
            return EnrichmentResult.READY;
        }
    }

    private boolean manifestExists(String manifestName) {
        return minioUtils.objectExists(minioUtils.objectUrl(manifestName));
    }

    /** 读取已发布的 manifest；不存在或损坏时返回 {@code null}（调用方决定重试还是降级）。 */
    public AnalysisInputManifest loadManifest(String contentHash) {
        if (contentHash == null || contentHash.isBlank()) return null;
        try {
            byte[] bytes = minioUtils.readObjectBytes(manifestObject(contentHash));
            return objectMapper.readValue(bytes, AnalysisInputManifest.class);
        } catch (Exception e) {
            log.warn("video_import_manifest_load_failed contentHash={}", contentHash);
            return null;
        }
    }

    private ChapterOutcome resolveChapters(MediaFile media, PlayerInfo player) {
        if (!properties.isChaptersEnabled()) {
            return new ChapterOutcome(AnalysisInputManifest.CHAPTER_ABSENT, 0);
        }
        if (player.viewPoints().isEmpty()) {
            return new ChapterOutcome(AnalysisInputManifest.CHAPTER_ABSENT, 0);
        }
        try {
            List<VideoChapter> chapters = ChapterNormalizer.normalize(
                    player.viewPoints(), media.getSourceDurationMs() == null
                            ? Long.MAX_VALUE : media.getSourceDurationMs());
            String json = objectMapper.writeValueAsString(chapters);
            writeJsonObject(chaptersObject(media.getContentHash()), json, true);
            return new ChapterOutcome(AnalysisInputManifest.CHAPTER_PRESENT, chapters.size());
        } catch (ChapterNormalizer.InvalidChapterDataException e) {
            // D-074：view_points 非空但整份校验失败——显式、可审计的降级，不无限重试、
            // 不静默丢章（manifest 记录 INVALID 与原因日志）。
            log.warn("video_import_chapters_invalid mediaId={} reason={}", media.getId(), e.getMessage());
            return new ChapterOutcome(AnalysisInputManifest.CHAPTER_INVALID, 0);
        } catch (ArtifactEnrichmentException e) {
            throw e; // 章节对象写入失败是强约束失败，向上抛可重试（§4.4 步 4）
        } catch (Exception e) {
            throw new ArtifactEnrichmentException("章节数据序列化失败", e);
        }
    }

    private SubtitleOutcome resolveSubtitle(MediaFile media, PlayerInfo player, String cookie) {
        if (!properties.isSubtitleEnabled()) {
            return SubtitleOutcome.absent();
        }
        // 拉字幕前先校验登录态：Cookie 过期时 player 接口会静默返回空字幕，
        // 把"登录失效"误判成"视频无字幕"。显式记录 NEED_LOGIN，避免静默降级为 ABSENT。
        if (!metadataClient.checkLogin(cookie).loggedIn()) {
            log.warn("video_import_subtitle_need_login mediaId={}", media.getId());
            return SubtitleOutcome.needLogin();
        }
        PlayerSubtitle track = SubtitleTrackSelector.select(player.subtitles());
        if (track == null) {
            return SubtitleOutcome.absent();
        }
        try {
            String body = metadataClient.fetchSubtitleJson(track.url());
            if (!isPlausibleSubtitleJson(body)) {
                log.warn("video_import_subtitle_body_invalid mediaId={}", media.getId());
                return SubtitleOutcome.failed();
            }
            writeJsonObject(subtitleObject(media.getContentHash()), body, true);
            return SubtitleOutcome.available(track);
        } catch (Exception e) {
            // 字幕是可降级资产：失败只影响是否走 ASR，不阻塞媒体与章节（§4.4 步 3）
            log.warn("video_import_subtitle_fetch_failed mediaId={}", media.getId());
            return SubtitleOutcome.failed();
        }
    }

    /** B 站 CC JSON 的最小组构校验：根对象含非空 body 数组。完整质量门槛在 Context 构建（§4.5）。 */
    private boolean isPlausibleSubtitleJson(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode items = root.path("body");
            return items.isArray() && !items.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private void writeJsonObject(String objectName, Object payload, boolean firstWriterWins) {
        String json = objectMapper.valueToTree(payload).toString();
        writeJsonObject(objectName, json, firstWriterWins);
    }

    private void writeJsonObject(String objectName, String json, boolean firstWriterWins) {
        if (firstWriterWins && minioUtils.objectExists(minioUtils.objectUrl(objectName))) {
            log.info("video_import_artifact_reused object={}", objectName);
            return;
        }
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        try {
            minioUtils.uploadObject(objectName,
                    new ByteArrayInputStream(bytes), bytes.length, JSON_CONTENT_TYPE);
        } catch (Exception e) {
            throw new ArtifactEnrichmentException("分析输入资产写入失败 object=" + objectName, e);
        }
    }

    public static class ArtifactEnrichmentException extends IllegalStateException {
        public ArtifactEnrichmentException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private record ChapterOutcome(String status, int count) {
    }

    private record SubtitleOutcome(String status, String lan, String aiType) {
        static SubtitleOutcome absent() {
            return new SubtitleOutcome(AnalysisInputManifest.SUBTITLE_ABSENT, null, null);
        }

        static SubtitleOutcome failed() {
            return new SubtitleOutcome(AnalysisInputManifest.SUBTITLE_FAILED, null, null);
        }

        static SubtitleOutcome needLogin() {
            return new SubtitleOutcome(AnalysisInputManifest.SUBTITLE_NEED_LOGIN, null, null);
        }

        static SubtitleOutcome available(PlayerSubtitle track) {
            return new SubtitleOutcome(AnalysisInputManifest.SUBTITLE_AVAILABLE,
                    track.lan(), track.aiType() == null ? null : String.valueOf(track.aiType()));
        }
    }
}
