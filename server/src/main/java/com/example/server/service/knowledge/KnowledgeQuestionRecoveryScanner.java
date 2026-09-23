package com.example.server.service.knowledge;

import com.example.server.config.KnowledgeQuestionProperties;
import com.example.server.dto.knowledge.KnowledgeErrorCode;
import com.example.server.dto.knowledge.KnowledgeTurnStatus;
import com.example.server.entity.KnowledgeTurn;
import com.example.server.mapper.KnowledgeTurnMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 僵尸轮次收敛（runbook §6.4）：进程在问答执行中死亡时，轮次永远停在 {@code PROCESSING}
 * 且会话执行权被永久占用；本扫描按判死阈值把它们收敛成可重试失败，释放执行权。
 *
 * <p>设计约束：
 * <ul>
 *   <li><b>不自动重跑模型</b>：用户“重新生成”使用新 requestId 在原会话提交，保证模型成本受控；</li>
 *   <li><b>判死阈值必须大于问答总超时</b>（启动期校验 {@code processing-stale-seconds >
 *       execution-timeout-seconds}），健康慢回答不被误杀；</li>
 *   <li><b>加锁顺序与完成/失败事务一致</b>：先条件更新轮次、再释放会话执行权
 *       （复用 {@link KnowledgeQuestionCommandService#fail} 的同一事务），避免死锁；</li>
 *   <li><b>旧线程防覆盖</b>：收敛之后旧执行线程返回，条件更新为 0 行，结果直接丢弃。</li>
 * </ul>
 *
 * <p>除了定时扫描，受理路径也会在 CAS 未命中时懒检查超时占用（见
 * {@link KnowledgeQuestionCommandService#acceptFollowUp}），把用户体感里的“会话一直忙”
 * 收敛为“可重试”。
 */
@Component
public class KnowledgeQuestionRecoveryScanner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeQuestionRecoveryScanner.class);

    private final KnowledgeTurnMapper turnMapper;
    private final KnowledgeQuestionCommandService commandService;
    private final KnowledgeQuestionProperties properties;

    public KnowledgeQuestionRecoveryScanner(KnowledgeTurnMapper turnMapper,
                                            KnowledgeQuestionCommandService commandService,
                                            KnowledgeQuestionProperties properties) {
        this.turnMapper = turnMapper;
        this.commandService = commandService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${knowledge.question.recovery-fixed-delay-ms:60000}")
    public void scan() {
        LocalDateTime staleBefore = LocalDateTime.now()
                .minusSeconds(properties.getQuestion().getProcessingStaleSeconds());
        List<KnowledgeTurn> staleTurns = turnMapper.findStaleProcessing(
                staleBefore, properties.getQuestion().getRecoveryBatchSize());
        int recovered = 0;
        for (KnowledgeTurn turn : staleTurns) {
            if (recover(turn)) {
                recovered++;
            }
        }
        if (recovered > 0) {
            log.info("knowledge_question_recovery_scan recovered={} scanned={}",
                    recovered, staleTurns.size());
        }
    }

    /**
     * 收敛一个僵尸轮次：条件更新为 {@code FAILED/QUESTION_PROCESS_INTERRUPTED} 并释放执行权。
     * 定时扫描与受理懒检查共用本入口。单条记录失败不影响整轮扫描（恢复必须比被恢复的链路更抗异常）。
     */
    public boolean recover(KnowledgeTurn turn) {
        if (turn == null || turn.getStatus() != KnowledgeTurnStatus.PROCESSING) {
            return false;
        }
        try {
            boolean done = commandService.fail(new KnowledgeQuestionCommandService.FailureInput(
                    turn.getUserId(), turn.getId(), turn.getRequestId(),
                    KnowledgeErrorCode.QUESTION_PROCESS_INTERRUPTED));
            if (done) {
                log.info("knowledge_question_recovered turnId={} conversationId={}",
                        turn.getId(), turn.getConversationId());
            }
            return done;
        } catch (RuntimeException e) {
            log.warn("knowledge_question_recover_failed turnId={}", turn.getId(), e);
            return false;
        }
    }
}
