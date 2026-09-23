package com.example.server.service.knowledge;

import com.example.server.dto.TaskStatus;
import com.example.server.service.TaskEventService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 知识问答轮次的终态事件发布（runbook §2.3 / §6.3）。
 *
 * <p>收敛原则与 D-066 一致：发布点只有“终态条件更新成功”这一种语义，不逐调用方补发布；
 * 本类被完成/失败短事务与受理懒收敛调用，因此所有路径（执行器、僵尸扫描、懒检查、队列满）
 * 自动覆盖。旧执行线程条件更新 0 行时不发布。
 *
 * <p>发布必须发生在<b>事务提交之后</b>：订阅者收到 COMPLETED 后立即 GET 单轮结果，
 * 如果事件先于提交送达，GET 可能读到提交前的 PROCESSING 而客户端已关闭连接。
 * 无活动事务时（测试或事务外调用）直接发布。
 *
 * <p>SSE 只是通知通道，不是结果真源：Redis Pub/Sub 故障时同实例仍本机投递，
 * 跨实例漏信号由客户端重连回放 MySQL 终态兜底。
 */
@Service
public class KnowledgeQuestionEventPublisher {

    public static final String COMPLETED_MESSAGE = "问答完成";
    public static final String FAILED_MESSAGE = "回答失败，可重新提问";

    private final TaskEventService taskEventService;

    public KnowledgeQuestionEventPublisher(TaskEventService taskEventService) {
        this.taskEventService = taskEventService;
    }

    public void publishCompleted(Long turnId) {
        taskEventService.publishKnowledgeQuestion(
                turnId, TaskStatus.of(TaskStatus.State.COMPLETED, COMPLETED_MESSAGE));
    }

    public void publishFailed(Long turnId) {
        taskEventService.publishKnowledgeQuestion(
                turnId, TaskStatus.of(TaskStatus.State.FAILED, FAILED_MESSAGE));
    }

    /** 终态 CAS 成功后调用：事务提交后发布，订阅者拿到事件时 MySQL 终态必然可见。 */
    public void publishAfterCommit(Long turnId, boolean completed) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    doPublish(turnId, completed);
                }
            });
        } else {
            doPublish(turnId, completed);
        }
    }

    private void doPublish(Long turnId, boolean completed) {
        if (completed) {
            publishCompleted(turnId);
        } else {
            publishFailed(turnId);
        }
    }
}
