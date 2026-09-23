package com.example.server.service.ingest;

import com.example.server.common.ErrorCode;
import com.example.server.dto.VideoImportJobStatus;
import com.example.server.dto.VideoImportResolveMessage;
import com.example.server.entity.VideoImportJob;
import com.example.server.exception.BusinessException;
import com.example.server.mapper.VideoImportJobMapper;
import org.apache.rocketmq.remoting.exception.RemotingException;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 投递 URL 解析消息并按投递结果推进父任务状态。
 *
 * <p>首次提交与用户重试共用同一段逻辑，避免两处各自演化：明确成功 CAS 到 {@code QUEUED}；
 * 明确失败 CAS 到 {@code DISPATCH_FAILED}、清空活跃键并返回 503；发送超时等无法判定结果的情况
 * 保留 {@code PENDING_DISPATCH}，交给恢复扫描。
 */
@Component
public class ImportResolveDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ImportResolveDispatcher.class);

    private final VideoImportJobMapper jobMapper;
    private final ImportRequestCache requestCache;
    private final RocketMQTemplate rocketMQTemplate;
    private final String resolveTopic;

    public ImportResolveDispatcher(VideoImportJobMapper jobMapper,
                                   ImportRequestCache requestCache,
                                   RocketMQTemplate rocketMQTemplate,
                                   @Value("${rocketmq.topic.video-import-resolve:video-import-resolve-topic}")
                                   String resolveTopic) {
        this.jobMapper = jobMapper;
        this.requestCache = requestCache;
        this.rocketMQTemplate = rocketMQTemplate;
        this.resolveTopic = resolveTopic;
    }

    /**
     * 恢复扫描专用：只重发消息，不改业务状态，不抛异常。
     *
     * <p>与 {@link #dispatch(VideoImportJob)} 的区别是职责不同：首次提交/用户重试要按投递结果决定
     * 对外错误码（503/202），而恢复扫描已经在处理一个非终态任务，投递失败只意味着“下一轮再试”。
     * 状态推进与重投预算由调用方掌握，避免扫描里出现会中断整轮处理的状态跳转。
     *
     * @return 是否已明确发出
     */
    public boolean redispatch(VideoImportJob job) {
        try {
            rocketMQTemplate.convertAndSend(resolveTopic,
                    VideoImportResolveMessage.of(job.getId(), job.getTraceId()));
            log.info("video_import_resolve_redispatched importId={} status={} attempt={}",
                    job.getId(), job.getStatus(), job.getAttemptCount());
            return true;
        } catch (RuntimeException e) {
            log.warn("video_import_resolve_redispatch_failed importId={} traceId={}",
                    job.getId(), job.getTraceId(), e);
            return false;
        }
    }

    /**
     * @return 是否已明确投递成功；{@code false} 表示结果不确定，父任务保持 {@code PENDING_DISPATCH}
     * @throws BusinessException 503：MQ 明确拒绝投递
     */
    public boolean dispatch(VideoImportJob job) {
        try {
            rocketMQTemplate.convertAndSend(resolveTopic,
                    VideoImportResolveMessage.of(job.getId(), job.getTraceId()));
        } catch (RuntimeException e) {
            if (isUncertainDispatchFailure(e)) {
                log.warn("video_import_resolve_dispatch_uncertain importId={} traceId={}",
                        job.getId(), job.getTraceId(), e);
                return false;
            }
            jobMapper.casStatusAndReleaseActiveKey(
                    job.getId(), VideoImportJobStatus.PENDING_DISPATCH, VideoImportJobStatus.DISPATCH_FAILED);
            requestCache.forget(job.getUserId(), job.getRequestHash());
            log.error("video_import_resolve_dispatch_failed importId={}", job.getId(), e);
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "导入任务投递失败，请稍后重试");
        }
        jobMapper.casStatus(job.getId(), VideoImportJobStatus.PENDING_DISPATCH, VideoImportJobStatus.QUEUED);
        requestCache.remember(job.getUserId(), job.getRequestHash(), job.getId());
        log.info("video_import_job_queued importId={} userId={} traceId={}",
                job.getId(), job.getUserId(), job.getTraceId());
        return true;
    }

    /** 发送超时或连接中断时 Broker 可能已经收单，不能按明确失败处理。 */
    private boolean isUncertainDispatchFailure(Throwable error) {        Throwable current = error;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (current instanceof RemotingException) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return false;
    }
}
