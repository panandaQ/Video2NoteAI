package com.example.server.service.knowledge;

import com.example.server.config.KnowledgeQuestionProperties;
import com.example.server.dto.VideoContext;
import com.example.server.dto.knowledge.AnswerOptions;
import com.example.server.dto.knowledge.AnswerOutcome;
import com.example.server.dto.knowledge.AnswerRequest;
import com.example.server.dto.knowledge.HistoryTurn;
import com.example.server.dto.knowledge.KnowledgeErrorCode;
import com.example.server.dto.knowledge.QueryPlan;
import com.example.server.entity.KnowledgeTurnEvidence;
import com.example.server.entity.MediaFile;
import com.example.server.dto.VideoEvidenceHit;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.service.AgentCheckpointService;
import com.example.server.service.AgentExecutionBudget;
import com.example.server.service.AgentTelemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 问答执行器（runbook §2.6 / §6.2）：可脱离 HTTP 与 SSE 调用的服务层入口。
 *
 * <p>执行链：加载最近历史 → 查询改写 → 单视频检索 → 证据回答 → 引用校验 → 产出
 * {@link AnswerOutcome}。它不依赖 HTTP 请求上下文、SSE emitter 或事务边界，因此评测 Runner
 * 与 A/B/C/D 消融只调用这一个方法（{@link #answer}），不必驱动整个 Web 栈；消融只是
 * {@link AnswerOptions} 的参数差异，不复制第二份执行链。
 *
 * <p>总超时通过 {@link AgentExecutionBudget} 作用于整条链：模型调用会以剩余预算作为自己的超时，
 * 慢服务商不可能悄悄跑过问答预算；僵尸恢复的判死阈值（{@code processing-stale-seconds}）
 * 必须大于该超时，才不会误杀健康慢回答。
 *
 * <p>{@link #process} 是 Q1b Controller 提交到 {@code aiTaskExecutor} 的编排入口：
 * 加载历史 → 执行 → 交给完成/失败短事务落库。旧执行线程在僵尸收敛后返回时，
 * 完成/失败的条件更新为 0 行，结果直接丢弃。
 *
 * <p>协作者 7 个（超 6 的说明）：本类按执行链阶段串起历史、检索、校验上下文、媒体标题、
 * 模型、配置与落库——它们分属不同变化原因，拆开会退化成只有编排没有内核的门面，因此保留
 * 在单类内，仅按阶段顺序排列。
 */
@Service
public class KnowledgeQuestionExecutor {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeQuestionExecutor.class);

    private final KnowledgeConversationQueryService queryService;
    private final ScopedEvidenceRetriever retriever;
    private final EvidenceGroundedAnswerService answerService;
    private final AgentCheckpointService checkpointService;
    private final MediaFileMapper mediaFileMapper;
    private final KnowledgeQuestionProperties properties;
    private final KnowledgeQuestionCommandService commandService;
    private final AgentTelemetry telemetry;

    public KnowledgeQuestionExecutor(KnowledgeConversationQueryService queryService,
                                     ScopedEvidenceRetriever retriever,
                                     EvidenceGroundedAnswerService answerService,
                                     AgentCheckpointService checkpointService,
                                     MediaFileMapper mediaFileMapper,
                                     KnowledgeQuestionProperties properties,
                                     KnowledgeQuestionCommandService commandService,
                                     AgentTelemetry telemetry) {
        this.queryService = queryService;
        this.retriever = retriever;
        this.answerService = answerService;
        this.checkpointService = checkpointService;
        this.mediaFileMapper = mediaFileMapper;
        this.properties = properties;
        this.commandService = commandService;
        this.telemetry = telemetry;
    }

    /** Q1b 异步编排的输入：受理事务已经创建的轮次定位信息。 */
    public record ProcessCommand(Long userId,
                                 Long conversationId,
                                 Long turnId,
                                 String requestId,
                                 long expectedVersion,
                                 Long mediaId,
                                 String question,
                                 String traceId) {
    }

    /**
     * §2.6 服务层入口：输入 userId + mediaId + question + 历史轮次，
     * 输出改写查询 + 检索诊断 + 校验通过的引用证据 + answerMode + answer + 耗时。
     *
     * @throws KnowledgeRetrievalUnavailableException 检索基础设施不可用（与“无证据”严格区分）
     */
    public AnswerOutcome answer(AnswerRequest request) {
        long started = System.nanoTime();
        long timeoutMs = TimeUnit.SECONDS.toMillis(
                properties.getQuestion().getExecutionTimeoutSeconds());
        try (AgentExecutionBudget.Scope budget = AgentExecutionBudget.open(timeoutMs)) {
            // 1. 查询规划（D-111）：一次调用产出「消歧后的独立问法」与「检索意图」。历史按
            //    includeHistory 开关进入，只作消歧依据（Prompt 里已声明不是事实来源）。
            List<HistoryTurn> conversationHistory = request.options().includeHistory()
                    ? request.history() : List.of();
            QueryPlan plan = retriever.plan(request.question(), conversationHistory);
            AgentExecutionBudget.check("QUERY_PLAN");

            // 2. 本轮重新检索：引用只能来自本轮命中（D-086）。
            long retrievalStarted = System.nanoTime();
            RetrievalResult retrieved = retriever.retrieve(
                    RetrievalScope.singleVideo(request.mediaId()), plan);
            AgentExecutionBudget.check("RETRIEVAL");
            telemetry.stageCurrent("RETRIEVAL", retrievalStarted, true);

            // 3. 引用校验所需的 V2 原 Context（D-079：只读 V2 快照）。
            VideoContext videoContext = checkpointService.loadContext(request.mediaId());
            if (videoContext == null) {
                throw new KnowledgeRetrievalUnavailableException(
                        "媒体 V2 上下文缺失: mediaId=" + request.mediaId());
            }

            // 4. 证据回答：追问用**消歧后的独立问法**作答（D-108）——检索已经用它命中证据，
            //    回答若还用未消歧的原始指代问句（"那下一种呢？"），模型只能靠证据措辞猜指代对象。
            //    首问与不启用改写的消融档（A/B）保持原问题，A/B 档的测量点才不被动摇。
            String answerQuestion = request.options().rewriteQuery() && !plan.standaloneQuery().isBlank()
                    ? plan.standaloneQuery()
                    : request.question();
            EvidenceGroundedAnswerService.GroundedResult result =
                    answerService.answer(answerQuestion, conversationHistory, videoContext,
                            retrieved.hits(), request.options());
            AgentExecutionBudget.check("ANSWER");

            List<KnowledgeTurnEvidence> evidence = buildEvidence(
                    request.mediaId(), mediaTitle(request.mediaId()), result.citedEvidence());
            return new AnswerOutcome(answerQuestion, retrieved.retrievalMode(), retrieved.retrievedCount(),
                    evidence, result.answerMode(), result.videoEvidenceFound(), result.answer(),
                    elapsedMillis(started), result.rawCitationCount(), result.fabricatedCitationCount());
        }
    }

    /**
     * 编排入口：受理事务之后由有界执行器调用，把执行链产出落到轮次终态。
     *
     * <p>失败分流：检索不可用 → {@code RETRIEVAL_UNAVAILABLE}；其余运行时失败
     * （模型、预算超时等）→ {@code ANSWER_GENERATION_FAILED}；两种都释放执行权，
     * 用户以新 requestId 在原会话重新生成。
     */
    public void process(ProcessCommand cmd) {
        telemetry.register(cmd.traceId(), cmd.turnId(), cmd.question());
        try {
            log.info("knowledge_question_executing userId={} turnId={} requestId={} traceId={} mediaId={}",
                    cmd.userId(), cmd.turnId(), cmd.requestId(), cmd.traceId(), cmd.mediaId());
            List<HistoryTurn> history = queryService.loadHistory(
                    cmd.conversationId(), cmd.userId(), cmd.expectedVersion());
            AnswerRequest request = new AnswerRequest(
                    cmd.userId(), cmd.mediaId(), cmd.question(), history, AnswerOptions.D);
            AnswerOutcome outcome = answer(request);
            boolean done = commandService.complete(new KnowledgeQuestionCommandService.CompletionInput(
                    cmd.userId(), cmd.turnId(), cmd.requestId(),
                    outcome.answerMode(), outcome.videoEvidenceFound(), outcome.answer(),
                    outcome.rewrittenQuery(), outcome.retrievalMode(),
                    outcome.retrievedCount(), outcome.citedCount(),
                    outcome.durationMs(), outcome.evidence()));
            log.info("knowledge_question_finished turnId={} mode={} cited={} retrieved={} retrievalMode={} "
                            + "durationMs={} persisted={}",
                    cmd.turnId(), outcome.answerMode(), outcome.citedCount(),
                    outcome.retrievedCount(), outcome.retrievalMode(), outcome.durationMs(), done);
        } catch (KnowledgeRetrievalUnavailableException e) {
            log.info("knowledge_question_failed turnId={} error=RETRIEVAL_UNAVAILABLE", cmd.turnId());
            commandService.fail(new KnowledgeQuestionCommandService.FailureInput(
                    cmd.userId(), cmd.turnId(), cmd.requestId(), KnowledgeErrorCode.RETRIEVAL_UNAVAILABLE));
        } catch (RuntimeException e) {
            log.warn("knowledge_question_failed turnId={} error=ANSWER_GENERATION_FAILED", cmd.turnId(), e);
            commandService.fail(new KnowledgeQuestionCommandService.FailureInput(
                    cmd.userId(), cmd.turnId(), cmd.requestId(), KnowledgeErrorCode.ANSWER_GENERATION_FAILED));
        } finally {
            telemetry.clear();
        }
    }

    private List<KnowledgeTurnEvidence> buildEvidence(Long mediaId, String titleSnapshot,
                                                      List<VideoEvidenceHit> cited) {
        List<KnowledgeTurnEvidence> rows = new ArrayList<>(cited.size());
        for (int i = 0; i < cited.size(); i++) {
            VideoEvidenceHit hit = cited.get(i);
            KnowledgeTurnEvidence row = new KnowledgeTurnEvidence();
            row.setEvidenceRank(i + 1);
            row.setMediaId(mediaId);
            row.setTitleSnapshot(titleSnapshot);
            row.setStartMs(hit.startMs());
            row.setEndMs(hit.endMs());
            row.setSource(hit.source());
            row.setSnippet(hit.snippet());
            row.setScore(hit.score());
            rows.add(row);
        }
        return rows;
    }

    private String mediaTitle(Long mediaId) {
        MediaFile media = mediaFileMapper.selectById(mediaId);
        if (media == null) {
            return "视频" + mediaId;
        }
        if (media.getSourceTitle() != null && !media.getSourceTitle().isBlank()) {
            return media.getSourceTitle();
        }
        if (media.getFilename() != null && !media.getFilename().isBlank()) {
            return media.getFilename();
        }
        return "视频" + mediaId;
    }

    private long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }
}
