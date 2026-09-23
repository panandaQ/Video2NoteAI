package com.example.server.service.ingest;

import com.example.server.dto.TaskStatus;
import com.example.server.service.TaskEventService;
import org.springframework.stereotype.Component;

/**
 * 导入任务终态通知的唯一出口（D-066）。
 *
 * <p>只做一件事：把"父任务已经进入终态"这个事实翻译成一个受控的 SSE 事件。它不保存状态、不决定状态迁移——
 * 调用方必须<b>先</b>用 CAS 成功写入终态、<b>再</b>调用这里，否则会出现"事件说完成、数据库还没完成"。
 *
 * <p>为什么发布点收敛到 {@link ImportJobAggregator} + 解析失败点，而不是散落到每个"看起来会失败"的调用方：
 * 删除联动（{@code VideoImportDeletionLifecycle}）、悬挂子项判死（恢复扫描的 {@code failOrphanItems}）
 * 这些间接路径都经由聚合器重算，收敛后不会漏；散落写法每加一条路径就要记得补一次发布。
 *
 * <p>文案必须是受控字典里的文本：事件直达客户端，禁止放 URL、traceId、堆栈或第三方原始错误。
 */
@Component
public class ImportTerminalEventPublisher {

    /** 完成文案：客户端据此刷新媒体列表，不需要知道任何任务细节。 */
    public static final String COMPLETED_MESSAGE = "视频导入完成";

    /** 失败文案：不区分具体错误码，原因留在任务详情接口里查。 */
    public static final String FAILED_MESSAGE = "视频导入失败，可在任务详情查看原因或稍后重试";

    /** 部分成功文案：多单元场景的终态投影，单视频不会出现。 */
    public static final String PARTIAL_MESSAGE = "部分视频导入失败，可在任务详情查看结果";

    /** 处理中文案：仅用于 SSE 建立时的状态回放。 */
    public static final String PROCESSING_MESSAGE = "视频导入处理中";

    private final TaskEventService taskEventService;

    public ImportTerminalEventPublisher(TaskEventService taskEventService) {
        this.taskEventService = taskEventService;
    }

    public void publishCompleted(Long importId) {
        taskEventService.publishVideoImport(importId,
                TaskStatus.of(TaskStatus.State.COMPLETED, COMPLETED_MESSAGE));
    }

    public void publishFailed(Long importId) {
        publishFailed(importId, FAILED_MESSAGE);
    }

    /**
     * @param controlledMessage 受控失败文案；调用方可传入错误码字典里的文本，但不得传第三方原始错误
     */
    public void publishFailed(Long importId, String controlledMessage) {
        taskEventService.publishVideoImport(importId,
                TaskStatus.of(TaskStatus.State.FAILED, controlledMessage));
    }

    /** 部分成功：多单元场景的受控投影（单视频场景不可能出现）。 */
    public void publishPartial(Long importId) {
        publishFailed(importId, PARTIAL_MESSAGE);
    }
}
