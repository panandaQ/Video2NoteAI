package com.example.server.service.ingest;

import com.example.server.common.ErrorCode;
import com.example.server.config.VideoImportProperties;
import com.example.server.dto.VideoImportJobStatus;
import com.example.server.dto.VideoImportSubmissionResponse;
import com.example.server.entity.VideoImportJob;
import com.example.server.exception.BusinessException;
import com.example.server.mapper.VideoImportJobMapper;
import com.example.server.service.BilibiliCredentialService;
import com.example.server.utils.VideoImportKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

/**
 * 提交链路契约：请求线程只落库与投递，不访问平台。
 *
 * <p>投递本身的三态行为在 {@link ImportResolveDispatcherTest} 覆盖；这里只验证受理与两层幂等。
 */
class ImportJobServiceTest {

    private static final String RAW_URL = "https://www.bilibili.com/video/BV1xx411c7mD";
    private static final String NORMALIZED_URL = "https://www.bilibili.com/video/BV1xx411c7mD";
    private static final String REQUEST_HASH = VideoImportKeys.requestHash(NORMALIZED_URL);
    private static final String ACTIVE_KEY = VideoImportKeys.activeRequestKey(7L, REQUEST_HASH);
    private static final Long USER_ID = 7L;
    private static final String CLIENT_IP = "203.0.113.7";

    private final VideoImportJobMapper jobMapper = mock(VideoImportJobMapper.class);
    private final ImportUrlNormalizer urlNormalizer = mock(ImportUrlNormalizer.class);
    private final ImportRequestCache requestCache = mock(ImportRequestCache.class);
    private final ImportJobQuota quota = mock(ImportJobQuota.class);
    private final ImportResolveDispatcher resolveDispatcher = mock(ImportResolveDispatcher.class);
    private final VideoImportProperties properties = new VideoImportProperties();
    private final BilibiliCredentialService credentialService = mock(BilibiliCredentialService.class);

    private ImportJobService service;

    @BeforeEach
    void setUp() {
        service = new ImportJobService(jobMapper, urlNormalizer, requestCache, quota,
                resolveDispatcher, properties, credentialService);
        when(urlNormalizer.normalize(RAW_URL)).thenReturn(NORMALIZED_URL);
        when(credentialService.hasCookie(USER_ID)).thenReturn(true);
    }

    /** 链接长度上限必须真正读配置（契约 §12），而不是被 DTO 写死成 2048。 */
    @Test
    void rejectsUrlLongerThanConfiguredLimitBeforeTouchingDatabase() {
        properties.setMaxUrlLength(40);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.submit(USER_ID, RAW_URL, CLIENT_IP));

        assertEquals(ErrorCode.INVALID_ARGUMENT, error.errorCode());
        verify(jobMapper, never()).insert(any(VideoImportJob.class));
        verify(quota, never()).requireSubmissionQuota(anyLong(), any());
        verify(resolveDispatcher, never()).dispatch(any(VideoImportJob.class));
    }

    @Test
    void reusesActiveJobFromRequestCacheWithoutDispatchingAgain() {
        VideoImportJob active = job(11L, VideoImportJobStatus.RESOLVING);
        when(requestCache.findImportId(USER_ID, REQUEST_HASH)).thenReturn(11L);
        when(jobMapper.findOwnedById(11L, USER_ID)).thenReturn(active);

        VideoImportSubmissionResponse response = service.submit(USER_ID, RAW_URL, CLIENT_IP);

        assertTrue(response.reused());
        assertEquals(11L, response.importId());
        assertEquals(VideoImportJobStatus.RESOLVING, response.status());
        verify(quota).requireSubmissionQuota(USER_ID, CLIENT_IP);
        verify(resolveDispatcher, never()).dispatch(any(VideoImportJob.class));
        verify(quota, never()).requireNewJobQuota(anyLong(), any());
        verify(jobMapper, never()).insert(any(VideoImportJob.class));
    }

    @Test
    void recreatesJobAfterTerminalJobSoCollectionCanBeRescanned() {
        VideoImportJob terminal = job(11L, VideoImportJobStatus.COMPLETED);
        when(requestCache.findImportId(USER_ID, REQUEST_HASH)).thenReturn(11L);
        when(jobMapper.findOwnedById(11L, USER_ID)).thenReturn(terminal);
        stubInsert(21L);
        when(jobMapper.findOwnedById(21L, USER_ID)).thenReturn(job(21L, VideoImportJobStatus.QUEUED));

        VideoImportSubmissionResponse response = service.submit(USER_ID, RAW_URL, CLIENT_IP);

        assertFalse(response.reused());
        assertEquals(21L, response.importId());
        verify(requestCache).forget(USER_ID, REQUEST_HASH);
        verify(quota).requireSubmissionQuota(USER_ID, CLIENT_IP);
        verify(quota).requireNewJobQuota(USER_ID, CLIENT_IP);
        verify(resolveDispatcher).dispatch(any(VideoImportJob.class));
    }

    @Test
    void createsJobWithFreshTraceIdAndDispatchesResolve() {
        stubNoCachedRequestMapping();
        stubInsert(21L);
        when(jobMapper.findOwnedById(21L, USER_ID)).thenReturn(job(21L, VideoImportJobStatus.QUEUED));

        VideoImportSubmissionResponse response = service.submit(USER_ID, RAW_URL, CLIENT_IP);

        assertFalse(response.reused());
        assertEquals(VideoImportJobStatus.QUEUED, response.status());
        verify(quota).requireSubmissionQuota(USER_ID, CLIENT_IP);
        verify(quota).requireNewJobQuota(USER_ID, CLIENT_IP);
        verify(jobMapper).insert(any(VideoImportJob.class));
        verify(resolveDispatcher).dispatch(any(VideoImportJob.class));
    }

    @Test
    void uniqueKeyRaceReusesWinnerInsteadOfFailing() {
        stubNoCachedRequestMapping();
        when(jobMapper.insert(any(VideoImportJob.class))).thenThrow(new DuplicateKeyException("dup"));
        // 第一次查无活跃任务才会进入 insert；唯一键冲突后第二次查才拿到获胜者。
        when(jobMapper.findByActiveRequestKey(ACTIVE_KEY))
                .thenReturn(null, job(31L, VideoImportJobStatus.QUEUED));

        VideoImportSubmissionResponse response = service.submit(USER_ID, RAW_URL, CLIENT_IP);

        assertTrue(response.reused());
        assertEquals(31L, response.importId());
        verify(quota).requireSubmissionQuota(USER_ID, CLIENT_IP);
        verify(quota).requireNewJobQuota(USER_ID, CLIENT_IP);
        verify(requestCache).remember(USER_ID, REQUEST_HASH, 31L);
        verify(resolveDispatcher, never()).dispatch(any(VideoImportJob.class));
    }

    /** 强制登录：未保存 Cookie 时在受理阶段直接拒绝，不建任务、不消耗配额、不投递。 */
    @Test
    void rejectsWhenNoCookieSaved() {
        when(credentialService.hasCookie(USER_ID)).thenReturn(false);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.submit(USER_ID, RAW_URL, CLIENT_IP));

        assertEquals(ErrorCode.INVALID_ARGUMENT, error.errorCode());
        verify(jobMapper, never()).insert(any(VideoImportJob.class));
        verify(quota, never()).requireSubmissionQuota(anyLong(), any());
        verify(resolveDispatcher, never()).dispatch(any(VideoImportJob.class));
    }

    @Test
    void invalidUrlFailsBeforeTouchingDatabaseOrQueue() {
        when(urlNormalizer.normalize(RAW_URL))
                .thenThrow(new BusinessException(ErrorCode.VALIDATION_FAILED, "视频链接格式不正确"));

        assertThrows(BusinessException.class, () -> service.submit(USER_ID, RAW_URL, CLIENT_IP));

        verify(jobMapper, never()).insert(any(VideoImportJob.class));
        verify(quota, never()).requireSubmissionQuota(anyLong(), any());
        verify(quota, never()).requireNewJobQuota(anyLong(), any());
        verify(resolveDispatcher, never()).dispatch(any(VideoImportJob.class));
    }

    /** 清晰度白名单校验在受理前拒绝，不让脏值进入异步链路。 */
    @Test
    void rejectsUnsupportedQualityBeforeTouchingDatabase() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.submit(USER_ID, RAW_URL, 999, CLIENT_IP));

        assertEquals(ErrorCode.INVALID_ARGUMENT, error.errorCode());
        verify(jobMapper, never()).insert(any(VideoImportJob.class));
        verify(quota, never()).requireSubmissionQuota(anyLong(), any());
        verify(resolveDispatcher, never()).dispatch(any(VideoImportJob.class));
    }

    /** 合法清晰度随父任务落库，登记阶段再复制到媒体行。 */
    @Test
    void persistsRequestedQualityOnNewJob() {
        stubNoCachedRequestMapping();
        stubInsert(21L);
        when(jobMapper.findOwnedById(21L, USER_ID)).thenReturn(job(21L, VideoImportJobStatus.QUEUED));

        service.submit(USER_ID, RAW_URL, 720, CLIENT_IP);

        ArgumentCaptor<VideoImportJob> captor = ArgumentCaptor.forClass(VideoImportJob.class);
        verify(jobMapper).insert(captor.capture());
        assertEquals(720, captor.getValue().getRequestedQuality());
    }

    private void stubNoCachedRequestMapping() {
        when(requestCache.findImportId(USER_ID, REQUEST_HASH)).thenReturn(null);
        when(jobMapper.findByActiveRequestKey(ACTIVE_KEY)).thenReturn(null);
    }

    private void stubInsert(Long importId) {
        when(jobMapper.insert(any(VideoImportJob.class))).thenAnswer(invocation -> {
            VideoImportJob inserted = invocation.getArgument(0);
            inserted.setId(importId);
            // 新建任务必须携带持久化的 traceId，后续解析与获取消息都复用它。
            if (inserted.getTraceId() == null || inserted.getTraceId().isBlank()) {
                throw new AssertionError("新建任务缺少 traceId");
            }
            return 1;
        });
    }

    private VideoImportJob job(Long importId, VideoImportJobStatus status) {
        VideoImportJob job = new VideoImportJob();
        job.setId(importId);
        job.setUserId(USER_ID);
        job.setStatus(status);
        job.setRequestHash(REQUEST_HASH);
        job.setOriginalUrl(NORMALIZED_URL);
        job.setTraceId("trace-" + importId);
        return job;
    }
}
