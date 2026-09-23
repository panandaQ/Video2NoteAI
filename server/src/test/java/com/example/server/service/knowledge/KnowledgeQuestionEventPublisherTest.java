package com.example.server.service.knowledge;

import com.example.server.dto.TaskStatus;
import com.example.server.service.TaskEventService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 终态发布契约（runbook §2.3 / §6.3）：发布必须发生在事务提交之后——
 * 订阅者收到事件时 MySQL 终态必然可见；无事务时立即发布。
 */
class KnowledgeQuestionEventPublisherTest {

    private final TaskEventService taskEventService = mock(TaskEventService.class);
    private final KnowledgeQuestionEventPublisher publisher =
            new KnowledgeQuestionEventPublisher(taskEventService);

    @Test
    void publishesImmediatelyWithoutTransaction() {
        publisher.publishAfterCommit(314L, true);

        ArgumentCaptor<TaskStatus> status = ArgumentCaptor.forClass(TaskStatus.class);
        verify(taskEventService).publishKnowledgeQuestion(eq(314L), status.capture());
        assertEquals(TaskStatus.State.COMPLETED, status.getValue().state());
    }

    @Test
    void publishesFailureStateForFailedTurns() {
        publisher.publishAfterCommit(314L, false);

        ArgumentCaptor<TaskStatus> status = ArgumentCaptor.forClass(TaskStatus.class);
        verify(taskEventService).publishKnowledgeQuestion(eq(314L), status.capture());
        assertEquals(TaskStatus.State.FAILED, status.getValue().state());
    }

    @Test
    void defersPublishUntilAfterCommitWhenSynchronizationActive() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            publisher.publishAfterCommit(314L, true);
            // 提交前绝不发布：客户端收到事件就会 GET 单轮结果，必须先让 MySQL 终态可见。
            verify(taskEventService, never()).publishKnowledgeQuestion(any(), any());

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            ArgumentCaptor<TaskStatus> status = ArgumentCaptor.forClass(TaskStatus.class);
            verify(taskEventService).publishKnowledgeQuestion(eq(314L), status.capture());
            assertEquals(TaskStatus.State.COMPLETED, status.getValue().state());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
