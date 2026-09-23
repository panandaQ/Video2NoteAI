package com.example.server.service.ingest;

import com.example.server.config.VideoImportProperties;
import com.example.server.dto.MediaImportStatus;
import com.example.server.dto.VideoImportErrorCode;
import com.example.server.dto.VideoImportJobStatus;
import com.example.server.entity.VideoImportJob;
import com.example.server.mapper.VideoImportJobMapper;
import com.example.server.service.BilibiliCredentialService;
import com.example.server.service.TaskEventService;
import com.example.server.source.ImportTargetType;
import com.example.server.source.SourceAdapterRegistry;
import com.example.server.source.VideoImportPlan;
import com.example.server.source.VideoPlatform;
import com.example.server.source.VideoSourceAdapter;
import com.example.server.source.VideoSourceException;
import com.example.server.source.VideoSourceUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 解析编排契约：执行权来自数据库 CAS，失败按可重试性分流。
 *
 * <p>覆盖：正常单 P 链路、重复消息不重复解析、终态跳过、不可重试失败落父任务 {@code FAILED}、
 * 可重试失败放回 {@code QUEUED}、身份不完整与超上限的整单失败。
 */
class ImportResolveServiceTest {

    private static final String URL = "https://www.bilibili.com/video/BV1xx411c7mD";
    private static final Long IMPORT_ID = 10L;

    private final VideoImportJobMapper jobMapper = mock(VideoImportJobMapper.class);
    private final VideoSourceAdapter adapter = mock(VideoSourceAdapter.class);
    /** 使用真实 Registry 与假 Adapter：既验证选择逻辑，也避免 subclass mock maker 无法模拟 final 类。 */
    private final SourceAdapterRegistry registry = new SourceAdapterRegistry(List.of(adapter));
    private final ImportMediaRegistrar registrar = mock(ImportMediaRegistrar.class);
    private final ImportUnitDispatcher dispatcher = mock(ImportUnitDispatcher.class);
    private final ImportJobAggregator aggregator = mock(ImportJobAggregator.class);
    private final VideoImportProperties properties = new VideoImportProperties();
    private final TaskEventService taskEventService = mock(TaskEventService.class);
    private final ImportTerminalEventPublisher terminalPublisher =
            new ImportTerminalEventPublisher(taskEventService);
    private final BilibiliCredentialService credentialService = mock(BilibiliCredentialService.class);

    private final ImportResolveService service = new ImportResolveService(
            jobMapper, registry, registrar, dispatcher, aggregator, properties, terminalPublisher,
            credentialService);

    @Test
    void resolvesSingleUnitRegistersAndDispatchesAcquire() {
        stubClaim();
        when(adapter.resolve(any(), any())).thenReturn(plan(unit("BV1xx411c7mD", "30000001")));
        when(registrar.register(eq(job()), anyList())).thenReturn(List.of(
                new ImportMediaRegistrar.RegisteredUnit(21L, MediaImportStatus.PENDING_DISPATCH, false, 1)));

        service.resolve(IMPORT_ID);

        verify(dispatcher).dispatch(IMPORT_ID, 21L, "trace-10");
        verify(aggregator).recompute(IMPORT_ID);
        verify(jobMapper).updateResolvedPlan(IMPORT_ID, "SINGLE", "BILIBILI", null, null);
        verify(jobMapper, never()).casStatusAndReleaseActiveKey(anyLong(), any(), any());
    }

    @Test
    void skipsAlreadyStoredMediaWithoutDownloadingAgain() {
        stubClaim();
        when(adapter.resolve(any(), any())).thenReturn(plan(unit("BV1xx411c7mD", "30000001")));
        when(registrar.register(eq(job()), anyList())).thenReturn(List.of(
                new ImportMediaRegistrar.RegisteredUnit(21L, MediaImportStatus.ANALYSIS_QUEUED, true, 1)));

        service.resolve(IMPORT_ID);

        verify(dispatcher, never()).dispatch(anyLong(), anyLong(), any());
        verify(aggregator).recompute(IMPORT_ID);
    }

    /** D-078 懒升级：READY 媒体再次导入时仍投递获取消息，由 acquire 判定是否 V1→V2 升级。 */
    @Test
    void readyMediaIsRedispatchedForLazyUpgradeCheck() {
        stubClaim();
        when(adapter.resolve(any(), any())).thenReturn(plan(unit("BV1xx411c7mD", "30000001")));
        when(registrar.register(eq(job()), anyList())).thenReturn(List.of(
                new ImportMediaRegistrar.RegisteredUnit(21L, MediaImportStatus.READY, true, 1)));

        service.resolve(IMPORT_ID);

        verify(dispatcher).dispatch(IMPORT_ID, 21L, "trace-10");
    }

    @Test
    void duplicateMessageCannotClaimExecutionTwice() {
        when(jobMapper.selectById(IMPORT_ID)).thenReturn(job());
        when(jobMapper.casStatus(IMPORT_ID, VideoImportJobStatus.QUEUED, VideoImportJobStatus.RESOLVING))
                .thenReturn(0);

        service.resolve(IMPORT_ID);

        verifyNoInteractions(adapter);
        verifyNoInteractions(registrar);
    }

    @Test
    void terminalJobIsSkipped() {
        VideoImportJob terminal = job();
        terminal.setStatus(VideoImportJobStatus.COMPLETED);
        when(jobMapper.selectById(IMPORT_ID)).thenReturn(terminal);

        service.resolve(IMPORT_ID);

        verify(jobMapper, never()).casStatus(anyLong(), any(), any());
    }

    @Test
    void nonRetryableSourceFailureMarksJobFailedAndReleasesActiveKey() {
        stubClaim();
        when(adapter.resolve(any(), any())).thenThrow(new VideoSourceException(VideoImportErrorCode.SOURCE_NOT_FOUND));

        assertDoesNotThrow(() -> service.resolve(IMPORT_ID));

        verify(jobMapper).updateError(IMPORT_ID, "SOURCE_NOT_FOUND",
                VideoImportErrorCode.SOURCE_NOT_FOUND.message(), false);
        verify(jobMapper).casStatusAndReleaseActiveKey(IMPORT_ID,
                VideoImportJobStatus.RESOLVING, VideoImportJobStatus.FAILED);
        verify(registrar, never()).register(any(), anyList());
    }

    /**
     * 可重试解析失败不会直接写 {@code FAILED}：它回到 {@code QUEUED} 交给 MQ 有限重投，
     * 只有恢复扫描在预算耗尽后才把它变成"可重试失败"（D-051/D-053，见 ImportRecoveryScannerTest）。
     */
    @Test
    void retryableSourceFailureReturnsJobToQueuedAndPropagates() {
        stubClaim();
        when(adapter.resolve(any(), any()))
                .thenThrow(new VideoSourceException(VideoImportErrorCode.SOURCE_TEMPORARY_UNAVAILABLE));

        assertThrows(VideoSourceException.class, () -> service.resolve(IMPORT_ID));

        verify(jobMapper).casStatus(IMPORT_ID, VideoImportJobStatus.RESOLVING, VideoImportJobStatus.QUEUED);
        verify(jobMapper, never()).casStatusAndReleaseActiveKey(anyLong(), any(), any());
        verify(jobMapper, never()).updateError(anyLong(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void collectionOverLimitFailsWholeJobWithoutRegisteringPartialItems() {
        properties.setMaxItems(1);
        stubClaim();
        when(adapter.resolve(any(), any())).thenReturn(plan(
                unit("BV1xx411c7mD", "30000001"), unit("BV1xx411c7mD", "30000002")));

        assertDoesNotThrow(() -> service.resolve(IMPORT_ID));

        verify(jobMapper).updateError(IMPORT_ID, "COLLECTION_TOO_LARGE",
                VideoImportErrorCode.COLLECTION_TOO_LARGE.message(), false);
        verify(registrar, never()).register(any(), anyList());
    }

    @Test
    void unitWithoutStableUnitIdFailsWholeJob() {
        stubClaim();
        when(adapter.resolve(any(), any())).thenReturn(plan(
                new VideoSourceUnit(VideoPlatform.BILIBILI, "UGC_VIDEO", "BV1xx411c7mD", " ",
                        URL, "标题", "作者", 1_000L, null, 1)));

        assertDoesNotThrow(() -> service.resolve(IMPORT_ID));

        verify(jobMapper).updateError(IMPORT_ID, "SOURCE_METADATA_INVALID",
                VideoImportErrorCode.SOURCE_METADATA_INVALID.message(), false);
        verify(registrar, never()).register(any(), anyList());
    }

    @Test
    void rejectsResolveWhenNoCookieSaved() {
        stubClaim();
        when(credentialService.getCookie(anyLong())).thenReturn(null);

        assertDoesNotThrow(() -> service.resolve(IMPORT_ID));

        verify(jobMapper).updateError(IMPORT_ID, "BILIBILI_COOKIE_REQUIRED",
                VideoImportErrorCode.BILIBILI_COOKIE_REQUIRED.message(), false);
        verify(adapter, never()).resolve(any(URI.class), anyString());
    }

    @Test
    void rejectsResolveWhenCookieExpired() {
        stubClaim();
        when(adapter.isCookieValid(anyString())).thenReturn(false);

        assertDoesNotThrow(() -> service.resolve(IMPORT_ID));

        verify(jobMapper).updateError(IMPORT_ID, "BILIBILI_COOKIE_EXPIRED",
                VideoImportErrorCode.BILIBILI_COOKIE_EXPIRED.message(), false);
        verify(adapter, never()).resolve(any(URI.class), anyString());
    }

    @Test
    void duplicateUnitsInsideOnePlanAreCollapsedBeforeRegistration() {
        stubClaim();
        when(adapter.resolve(any(), any())).thenReturn(plan(
                unit("BV1xx411c7mD", "30000001"), unit("BV1xx411c7mD", "30000001")));
        when(registrar.register(eq(job()), anyList())).thenReturn(List.of(
                new ImportMediaRegistrar.RegisteredUnit(21L, MediaImportStatus.PENDING_DISPATCH, false, 1)));

        service.resolve(IMPORT_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<VideoSourceUnit>> captor = ArgumentCaptor.forClass(List.class);
        verify(registrar).register(eq(job()), captor.capture());
        assertEquals(1, captor.getValue().size(), "同一 sourceKey 的重复单元必须先去重");
    }

    private void stubClaim() {
        when(jobMapper.selectById(IMPORT_ID)).thenReturn(job());
        when(jobMapper.casStatus(IMPORT_ID, VideoImportJobStatus.QUEUED, VideoImportJobStatus.RESOLVING))
                .thenReturn(1);
        when(adapter.supports(URI.create(URL))).thenReturn(true);
        // 强制登录默认放行：多数用例关心解析编排，不关心 Cookie 校验。
        when(credentialService.getCookie(anyLong())).thenReturn("SESSDATA=valid");
        when(adapter.isCookieValid(anyString())).thenReturn(true);
    }

    private VideoImportJob job() {
        VideoImportJob job = new VideoImportJob();
        job.setId(IMPORT_ID);
        job.setUserId(7L);
        job.setStatus(VideoImportJobStatus.QUEUED);
        job.setOriginalUrl(URL);
        job.setRequestHash("hash");
        job.setTraceId("trace-10");
        return job;
    }

    private VideoImportPlan plan(VideoSourceUnit... units) {
        return new VideoImportPlan(units.length == 1 ? ImportTargetType.SINGLE : ImportTargetType.COLLECTION,
                VideoPlatform.BILIBILI, null, null, new ArrayList<>(List.of(units)));
    }

    private VideoSourceUnit unit(String bvid, String cid) {
        return new VideoSourceUnit(VideoPlatform.BILIBILI, "UGC_VIDEO", bvid, cid,
                URL, "标题", "作者", 1_000L, null, 1);
    }
}
