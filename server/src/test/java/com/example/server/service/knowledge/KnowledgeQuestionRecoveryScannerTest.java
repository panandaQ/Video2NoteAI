package com.example.server.service.knowledge;

import com.example.server.config.KnowledgeQuestionProperties;
import com.example.server.dto.knowledge.KnowledgeErrorCode;
import com.example.server.dto.knowledge.KnowledgeTurnStatus;
import com.example.server.entity.KnowledgeTurn;
import com.example.server.mapper.KnowledgeTurnMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 僵尸轮次收敛契约（runbook §6.4）：只收敛 PROCESSING 且占用超时的轮次、
 * 复用失败短事务（不复制第二条收敛链）、单条异常不中断整轮扫描。
 */
class KnowledgeQuestionRecoveryScannerTest {

    private final KnowledgeTurnMapper turnMapper = mock(KnowledgeTurnMapper.class);
    private final KnowledgeQuestionCommandService commandService = mock(KnowledgeQuestionCommandService.class);
    private final KnowledgeQuestionRecoveryScanner scanner =
            new KnowledgeQuestionRecoveryScanner(turnMapper, commandService, new KnowledgeQuestionProperties());

    @Test
    void staleProcessingTurnsAreConvergedToInterruptedFailure() {
        KnowledgeTurn stale = turn(1L, KnowledgeTurnStatus.PROCESSING, "r1");
        KnowledgeTurn completed = turn(2L, KnowledgeTurnStatus.COMPLETED, "r2");
        when(turnMapper.findStaleProcessing(any(), anyInt())).thenReturn(List.of(stale, completed));
        when(commandService.fail(any())).thenReturn(true);

        scanner.scan();

        ArgumentCaptor<KnowledgeQuestionCommandService.FailureInput> failure =
                ArgumentCaptor.forClass(KnowledgeQuestionCommandService.FailureInput.class);
        verify(commandService).fail(failure.capture());
        assertEquals(KnowledgeErrorCode.QUESTION_PROCESS_INTERRUPTED, failure.getValue().errorCode());
        assertEquals(1L, failure.getValue().turnId());
    }

    @Test
    void recoverUsesCommandServiceAndReportsWhetherApplied() {
        KnowledgeTurn stale = turn(1L, KnowledgeTurnStatus.PROCESSING, "r1");
        when(commandService.fail(any())).thenReturn(false);

        assertFalse(scanner.recover(stale));
        verify(commandService).fail(any());
    }

    @Test
    void recoverSkipsTerminalTurnsAndNullsWithoutTouchingService() {
        assertFalse(scanner.recover(null));
        assertFalse(scanner.recover(turn(1L, KnowledgeTurnStatus.FAILED, "r1")));
        verify(commandService, never()).fail(any());
    }

    @Test
    void singleFailureDoesNotAbortWholeScan() {
        KnowledgeTurn stale = turn(1L, KnowledgeTurnStatus.PROCESSING, "r1");
        when(turnMapper.findStaleProcessing(any(), anyInt())).thenReturn(List.of(stale));
        when(commandService.fail(any())).thenThrow(new IllegalStateException("db down"));

        scanner.scan(); // 不抛出：恢复必须比被恢复的链路更抗异常

        verify(commandService).fail(any());
    }

    private KnowledgeTurn turn(Long id, KnowledgeTurnStatus status, String requestId) {
        KnowledgeTurn turn = new KnowledgeTurn();
        turn.setId(id);
        turn.setUserId(7L);
        turn.setConversationId(91L);
        turn.setTurnNo((int) (long) id);
        turn.setRequestId(requestId);
        turn.setStatus(status);
        turn.setCreatedAt(LocalDateTime.now());
        return turn;
    }
}
