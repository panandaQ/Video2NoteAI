package com.example.server.controller;

import com.example.server.common.Result;
import com.example.server.dto.VideoImportDetailResponse;
import com.example.server.dto.VideoImportSubmissionResponse;
import com.example.server.service.AuthService;
import com.example.server.service.TaskEventService;
import com.example.server.service.ingest.ImportJobRetryService;
import com.example.server.service.ingest.VideoImportQueryService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 导入任务查询与重试入口。
 *
 * <p>{@code /video-imports/**} 已在 {@code WebConfig} 中登记鉴权；查询与重试都只作用于当前用户自己的任务。
 */
@RestController
@RequestMapping("/video-imports")
public class VideoImportQueryController {

    private final VideoImportQueryService queryService;
    private final ImportJobRetryService retryService;
    private final TaskEventService taskEventService;

    public VideoImportQueryController(VideoImportQueryService queryService,
                                      ImportJobRetryService retryService,
                                      TaskEventService taskEventService) {
        this.queryService = queryService;
        this.retryService = retryService;
        this.taskEventService = taskEventService;
    }

    @GetMapping("/{importId}")
    public Result<VideoImportDetailResponse> detail(
            @PathVariable Long importId,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return Result.ok(queryService.detail(userId, importId));
    }

    /**
     * 导入任务终态通知（SSE，D-066）。
     *
     * <p>主流程只有一次长连接：建立后不推送中间阶段，父任务进入 {@code COMPLETED/FAILED} 时推送
     * 一个终态事件并关闭；订阅时任务已终态则立即回放当前终态后关闭。它是最佳努力通知，
     * 不是业务真源——连接丢失时以 {@code /media/list} 和详情接口为准。
     *
     * <p>订阅前先做归属校验：不存在与越权统一 404，不泄漏其他用户的任务。
     */
    @GetMapping(value = "/{importId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
            @PathVariable Long importId,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        queryService.requireOwned(userId, importId);
        // 先注册订阅者、再回放当前状态：顺序反过来会在"注册前完成"的竞态里永久丢失终态事件。
        return taskEventService.subscribeVideoImport(importId,
                () -> queryService.currentEvent(userId, importId));
    }

    /**
     * 重试导入任务：仅允许 {@code DISPATCH_FAILED}、可重试的 {@code FAILED}
     * 与含可重试失败子项的 {@code PARTIAL_SUCCESS}；其余状态返回 409。
     */
    @PostMapping("/{importId}/retry")
    public ResponseEntity<Result<VideoImportSubmissionResponse>> retry(
            @PathVariable Long importId,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        VideoImportSubmissionResponse body = retryService.retry(userId, importId);
        return ResponseEntity.accepted().body(Result.ok(body));
    }
}
