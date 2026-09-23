package com.example.server.service.ingest;

import com.example.server.config.VideoImportProperties;
import com.example.server.dto.AnalysisInputManifest;
import com.example.server.dto.PlayerViewPoint;
import com.example.server.entity.MediaFile;
import com.example.server.infrastructure.BilibiliMetadataClient;
import com.example.server.infrastructure.BilibiliMetadataClient.LoginStatus;
import com.example.server.infrastructure.BilibiliMetadataClient.PlayerInfo;
import com.example.server.infrastructure.BilibiliMetadataClient.PlayerSubtitle;
import com.example.server.service.BilibiliCredentialService;
import com.example.server.source.VideoPlatform;
import com.example.server.utils.MinioUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 统一补充资产契约（计划 §4.4 / §6.1 / §7.1）：三路径闸门、零探测复用、章节强约束、
 * 字幕可降级、INVALID 显式降级、manifest 最后发布。
 */
class ContentArtifactEnrichmentServiceTest {

    private static final String CONTENT_HASH = "hash1";

    private final MinioUtils minioUtils = mock(MinioUtils.class);
    private final BilibiliMetadataClient metadataClient = mock(BilibiliMetadataClient.class);
    private final ImportUnitLock unitLock = mock(ImportUnitLock.class);
    private final VideoImportProperties properties = new VideoImportProperties();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BilibiliCredentialService credentialService = mock(BilibiliCredentialService.class);

    private final ContentArtifactEnrichmentService service = new ContentArtifactEnrichmentService(
            minioUtils, metadataClient, unitLock, properties, objectMapper, credentialService);

    private MediaFile media() {
        MediaFile media = new MediaFile();
        media.setId(3L);
        media.setPlatform(VideoPlatform.BILIBILI);
        media.setResourceType("UGC_VIDEO");
        media.setExternalResourceId("BV1ncbr6RELx");
        media.setExternalUnitId("123456");
        media.setContentHash(CONTENT_HASH);
        media.setContentAssetId(1L);
        media.setSourceDurationMs(60_000L);
        return media;
    }

    private void lockAcquired() {
        ImportUnitLock.Handle handle = mock(ImportUnitLock.Handle.class);
        when(handle.acquired()).thenReturn(true);
        when(unitLock.tryLockContent(any(), eq(3L), eq(TimeUnit.SECONDS))).thenReturn(handle);
    }

    private void urlMapping() {
        when(minioUtils.objectUrl(anyString()))
                .thenAnswer(inv -> "http://localhost:9000/media/" + inv.getArgument(0, String.class));
    }

    private void loginValid() {
        when(metadataClient.checkLogin(any())).thenReturn(new LoginStatus(true, 1L, "u"));
    }

    private PlayerInfo playerInfo(List<PlayerSubtitle> subtitles, List<PlayerViewPoint> points) {
        return new PlayerInfo(subtitles, false, points);
    }

    @Test
    void completeManifestSkipsProbeEntirely() {
        urlMapping();
        when(minioUtils.objectExists("http://localhost:9000/media/"
                + ContentArtifactEnrichmentService.manifestObject(CONTENT_HASH))).thenReturn(true);

        assertEquals(ContentArtifactEnrichmentService.EnrichmentResult.READY,
                service.ensureArtifacts(media()));

        verify(unitLock, never()).tryLockContent(any(), anyLong(), any(TimeUnit.class));
        verify(metadataClient, never()).fetchPlayerInfo(anyString(), any(), anyString(), any());
    }

    @Test
    void lockBusyReportsInProgressAndSkipsProbe() {
        urlMapping();
        ImportUnitLock.Handle handle = mock(ImportUnitLock.Handle.class);
        when(handle.acquired()).thenReturn(false);
        when(unitLock.tryLockContent(any(), eq(3L), eq(TimeUnit.SECONDS))).thenReturn(handle);

        assertEquals(ContentArtifactEnrichmentService.EnrichmentResult.IN_PROGRESS,
                service.ensureArtifacts(media()));

        verify(unitLock).tryLockContent(1L, 3L, TimeUnit.SECONDS);
        verify(metadataClient, never()).fetchPlayerInfo(anyString(), any(), anyString(), any());
    }

    @Test
    void lockBusyButManifestPublishedDuringWaitReturnsReady() {
        urlMapping();
        ImportUnitLock.Handle handle = mock(ImportUnitLock.Handle.class);
        when(handle.acquired()).thenReturn(false);
        when(unitLock.tryLockContent(any(), eq(3L), eq(TimeUnit.SECONDS))).thenReturn(handle);
        when(minioUtils.objectExists("http://localhost:9000/media/"
                + ContentArtifactEnrichmentService.manifestObject(CONTENT_HASH)))
                .thenReturn(false, true);

        assertEquals(ContentArtifactEnrichmentService.EnrichmentResult.READY,
                service.ensureArtifacts(media()));

        verify(metadataClient, never()).fetchPlayerInfo(anyString(), any(), anyString(), any());
    }

    @Test
    void nonBilibiliMediaSkipped() {
        MediaFile other = media();
        other.setPlatform(VideoPlatform.TEST);
        service.ensureArtifacts(other);
        verify(metadataClient, never()).fetchPlayerInfo(anyString(), any(), anyString(), any());
    }

    @Test
    void happyPathPublishesChaptersSubtitleAndManifest() throws Exception {
        lockAcquired();
        urlMapping();
        loginValid();
        when(metadataClient.fetchPlayerInfo("BV1ncbr6RELx", null, "123456", null))
                .thenReturn(playerInfo(
                        List.of(new PlayerSubtitle("zh-Hans", "中文（简体）",
                                "//aisubtitle.hdslb.com/bfs/a.json", 0, 0, true)),
                        List.of(new PlayerViewPoint("开场", 0, 10, 1),
                                new PlayerViewPoint("正片", 10, 50, 1))));
        when(metadataClient.fetchSubtitleJson("//aisubtitle.hdslb.com/bfs/a.json"))
                .thenReturn("{\"body\":[{\"from\":0,\"to\":1,\"content\":\"你好\"}]}");

        service.ensureArtifacts(media());

        ArgumentCaptor<InputStream> streams = ArgumentCaptor.forClass(InputStream.class);
        verify(minioUtils, atLeast(3)).uploadObject(anyString(), streams.capture(), anyLong(), anyString());
        String manifestJson = lastCaptured(streams);
        AnalysisInputManifest manifest = objectMapper.readValue(manifestJson, AnalysisInputManifest.class);
        assertEquals(AnalysisInputManifest.CHAPTER_PRESENT, manifest.chapterStatus());
        assertEquals(2, manifest.chapterCount());
        assertEquals(AnalysisInputManifest.SUBTITLE_AVAILABLE, manifest.subtitleStatus());
        assertEquals("zh-Hans", manifest.subtitleLan());
        assertEquals("BV1ncbr6RELx", manifest.externalResourceId());
        assertEquals(AnalysisInputManifest.PROBE_OK, manifest.playerProbeStatus());
    }

    @Test
    void emptyViewPointsRecordedAbsent() throws Exception {
        lockAcquired();
        urlMapping();
        loginValid();
        when(metadataClient.fetchPlayerInfo("BV1ncbr6RELx", null, "123456", null))
                .thenReturn(playerInfo(List.of(), List.of()));

        service.ensureArtifacts(media());

        AnalysisInputManifest manifest = lastManifest();
        assertEquals(AnalysisInputManifest.CHAPTER_ABSENT, manifest.chapterStatus());
        assertEquals(AnalysisInputManifest.SUBTITLE_ABSENT, manifest.subtitleStatus());
    }

    @Test
    void invalidChaptersRecordedInvalidAndStillPublishes() throws Exception {
        lockAcquired();
        urlMapping();
        loginValid();
        when(metadataClient.fetchPlayerInfo("BV1ncbr6RELx", null, "123456", null))
                .thenReturn(playerInfo(List.of(), List.of(
                        new PlayerViewPoint("a", 0, 20, 1),
                        new PlayerViewPoint("b", 10, 30, 1))));

        service.ensureArtifacts(media());

        AnalysisInputManifest manifest = lastManifest();
        assertEquals(AnalysisInputManifest.CHAPTER_INVALID, manifest.chapterStatus());
        assertEquals(0, manifest.chapterCount());
    }

    @Test
    void chapterObjectWriteFailureIsRetryableAndBlocksManifest() throws Exception {
        lockAcquired();
        urlMapping();
        when(metadataClient.fetchPlayerInfo("BV1ncbr6RELx", null, "123456", null))
                .thenReturn(playerInfo(List.of(), List.of(
                        new PlayerViewPoint("开场", 0, 10, 1))));
        when(minioUtils.uploadObject(anyString(), any(), anyLong(), anyString()))
                .thenThrow(new IllegalStateException("minio down"));

        assertThrows(ContentArtifactEnrichmentService.ArtifactEnrichmentException.class,
                () -> service.ensureArtifacts(media()));
        verify(minioUtils, never()).uploadObject(
                org.mockito.ArgumentMatchers.eq(
                        ContentArtifactEnrichmentService.manifestObject(CONTENT_HASH)),
                any(), anyLong(), anyString());
    }

    @Test
    void subtitleFailureDegradesButManifestStillPublished() throws Exception {
        lockAcquired();
        urlMapping();
        loginValid();
        when(metadataClient.fetchPlayerInfo("BV1ncbr6RELx", null, "123456", null))
                .thenReturn(playerInfo(
                        List.of(new PlayerSubtitle("zh-Hans", "中文（简体）",
                                "https://aisubtitle.hdslb.com/a.json", 0, 0, true)),
                        List.of(new PlayerViewPoint("开场", 0, 10, 1))));
        when(metadataClient.fetchSubtitleJson(anyString()))
                .thenThrow(new IllegalStateException("cdn down"));

        service.ensureArtifacts(media());

        AnalysisInputManifest manifest = lastManifest();
        assertEquals(AnalysisInputManifest.SUBTITLE_FAILED, manifest.subtitleStatus());
        assertEquals(AnalysisInputManifest.CHAPTER_PRESENT, manifest.chapterStatus());
    }

    @Test
    void subtitleNeedLoginRecordedWhenLoginInvalid() throws Exception {
        lockAcquired();
        urlMapping();
        when(metadataClient.fetchPlayerInfo("BV1ncbr6RELx", null, "123456", null))
                .thenReturn(playerInfo(List.of(), List.of(new PlayerViewPoint("开场", 0, 10, 1))));
        when(metadataClient.checkLogin(any())).thenReturn(LoginStatus.loggedOut());

        service.ensureArtifacts(media());

        AnalysisInputManifest manifest = lastManifest();
        assertEquals(AnalysisInputManifest.SUBTITLE_NEED_LOGIN, manifest.subtitleStatus());
        assertEquals(AnalysisInputManifest.CHAPTER_PRESENT, manifest.chapterStatus());
    }

    private AnalysisInputManifest lastManifest() throws Exception {
        ArgumentCaptor<InputStream> streams = ArgumentCaptor.forClass(InputStream.class);
        verify(minioUtils, atLeast(1)).uploadObject(anyString(), streams.capture(), anyLong(), anyString());
        return objectMapper.readValue(lastCaptured(streams), AnalysisInputManifest.class);
    }

    private String lastCaptured(ArgumentCaptor<InputStream> streams) throws Exception {
        InputStream last = streams.getAllValues().get(streams.getAllValues().size() - 1);
        return new String(last.readAllBytes(), StandardCharsets.UTF_8);
    }
}
