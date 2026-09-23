package com.example.server.service.ingest;

import com.example.server.dto.TaskStatus;
import com.example.server.dto.VideoImportErrorCode;
import com.example.server.service.TaskEventService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 终态发布器契约（D-066）：只发受控终态、不碰数据库。
 *
 * <p>它没有状态，所以"重复调用不改变数据库状态"这一点由调用方的 CAS 保证（见
 * {@code ImportJobAggregatorTest.terminalCasLosingSideDoesNotPublishAgain}）；这里固定的是
 * 事件内容本身：状态只能是终态、文案只能来自字典、不能夹带内部细节。
 */
class ImportTerminalEventPublisherTest {

    private static final Long IMPORT_ID = 42L;

    private final TaskEventService taskEventService = mock(TaskEventService.class);
    private final ImportTerminalEventPublisher publisher =
            new ImportTerminalEventPublisher(taskEventService);

    @Test
    void completedPublishesCompletedWithControlledText() {
        publisher.publishCompleted(IMPORT_ID);

        TaskStatus status = captured();
        assertEquals(TaskStatus.State.COMPLETED, status.state());
        assertEquals(ImportTerminalEventPublisher.COMPLETED_MESSAGE, status.message());
        assertNull(status.result(), "终态通知不携带笔记内容：结果由媒体列表与详情接口提供");
    }

    @Test
    void failedPublishesFailedWithControlledText() {
        publisher.publishFailed(IMPORT_ID);

        TaskStatus status = captured();
        assertEquals(TaskStatus.State.FAILED, status.state());
        assertEquals(ImportTerminalEventPublisher.FAILED_MESSAGE, status.message());
    }

    /** 部分成功对客户端就是"有失败"：事件状态取 FAILED，文案取更准确的部分失败文本。 */
    @Test
    void partialPublishesFailedStateWithPartialText() {
        publisher.publishPartial(IMPORT_ID);

        TaskStatus status = captured();
        assertEquals(TaskStatus.State.FAILED, status.state());
        assertEquals(ImportTerminalEventPublisher.PARTIAL_MESSAGE, status.message());
    }

    /** 允许传字典里的错误码文案，但必须是受控文本：不能出现 URL、traceId、堆栈或第三方原文。 */
    @Test
    void customMessageStaysControlled() {
        publisher.publishFailed(IMPORT_ID, VideoImportErrorCode.SOURCE_UNSUPPORTED.message());

        String message = captured().message();
        assertFalse(message.contains("http"), "事件不得夹带 URL");
        assertFalse(message.contains("Exception"), "事件不得夹带异常类型");
        assertTrue(message.length() < 100, "文案必须是短句，不是错误原样透传");
    }

    private TaskStatus captured() {
        ArgumentCaptor<TaskStatus> captor = ArgumentCaptor.forClass(TaskStatus.class);
        verify(taskEventService).publishVideoImport(anyLong(), captor.capture());
        return captor.getValue();
    }
}
