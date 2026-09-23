package com.example.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.server.entity.KnowledgeTurn;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 知识问答轮次数据访问。
 *
 * <p>幂等真源是 {@code uk_knowledge_turn_request (user_id, request_id)}；终态只允许
 * {@code PROCESSING → COMPLETED/FAILED} 的条件更新——旧执行线程在僵尸收敛后返回时必然 0 行，
 * 结果直接丢弃（runbook §6.3）。
 */
@Mapper
public interface KnowledgeTurnMapper extends BaseMapper<KnowledgeTurn> {

    /** 用户维度幂等查询：必须同时带 userId，其他用户即使猜到 requestId 也不能探测。 */
    @Select("SELECT * FROM knowledge_turns WHERE user_id = #{userId} AND request_id = #{requestId}")
    KnowledgeTurn findByRequestId(@Param("userId") Long userId, @Param("requestId") String requestId);

    /** 归属校验查询：任何按 ID 的对外查询都必须带 user_id。 */
    @Select("SELECT * FROM knowledge_turns WHERE id = #{id} AND user_id = #{userId}")
    KnowledgeTurn findOwnedById(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * 完成（runbook §6.3）：只有轮次仍在 PROCESSING **且**会话执行权仍属于本次请求才允许完成。
     *
     * <p>{@code EXISTS} 把两个条件放进同一条 UPDATE：轮次状态是旧线程防覆盖，会话
     * {@code active_request_id} 是执行权防覆盖。两条防线都通过后才写入回答与诊断字段。
     *
     * @return 1 = 本次完成生效；0 = 轮次已终态或执行权已不属于本次请求，结果必须丢弃
     */
    @Update("""
            UPDATE knowledge_turns t
               SET t.status = 'COMPLETED',
                   t.rewritten_query = #{rewrittenQuery},
                   t.answer_mode = #{answerMode},
                   t.video_evidence_found = #{videoEvidenceFound},
                   t.answer = #{answer},
                   t.retrieval_mode = #{retrievalMode},
                   t.retrieved_count = #{retrievedCount},
                   t.cited_count = #{citedCount},
                   t.duration_ms = #{durationMs},
                   t.completed_at = NOW(3)
             WHERE t.id = #{id}
               AND t.user_id = #{userId}
               AND t.request_id = #{requestId}
               AND t.status = 'PROCESSING'
               AND EXISTS (SELECT 1 FROM knowledge_conversations c
                            WHERE c.id = t.conversation_id
                              AND c.user_id = #{userId}
                              AND c.active_request_id = #{requestId})
            """)
    int complete(@Param("id") Long id,
                 @Param("userId") Long userId,
                 @Param("requestId") String requestId,
                 @Param("rewrittenQuery") String rewrittenQuery,
                 @Param("answerMode") String answerMode,
                 @Param("videoEvidenceFound") boolean videoEvidenceFound,
                 @Param("answer") String answer,
                 @Param("retrievalMode") String retrievalMode,
                 @Param("retrievedCount") Integer retrievedCount,
                 @Param("citedCount") Integer citedCount,
                 @Param("durationMs") Long durationMs);

    /**
     * 失败：保存受控错误码并进入终态，不保存半成品；同样要求执行权仍属于本次请求。
     *
     * @return 1 = 本次失败生效；0 = 轮次已终态或执行权已不属于本次请求，结果必须丢弃
     */
    @Update("""
            UPDATE knowledge_turns t
               SET t.status = 'FAILED',
                   t.error_code = #{errorCode},
                   t.completed_at = NOW(3)
             WHERE t.id = #{id}
               AND t.user_id = #{userId}
               AND t.request_id = #{requestId}
               AND t.status = 'PROCESSING'
               AND EXISTS (SELECT 1 FROM knowledge_conversations c
                            WHERE c.id = t.conversation_id
                              AND c.user_id = #{userId}
                              AND c.active_request_id = #{requestId})
            """)
    int fail(@Param("id") Long id,
             @Param("userId") Long userId,
             @Param("requestId") String requestId,
             @Param("errorCode") String errorCode);

    /**
     * 追问消歧上下文：最近 {@code limit} 个 COMPLETED 轮次，按时间正序返回（runbook §6.2 第 2 步）。
     *
     * <p>历史只用于消歧，不作为事实证据；每轮回答仍要重新检索当前授权范围。
     */
    @Select("""
            SELECT * FROM (
                SELECT * FROM knowledge_turns
                 WHERE conversation_id = #{conversationId}
                   AND user_id = #{userId}
                   AND status = 'COMPLETED'
                 ORDER BY turn_no DESC
                 LIMIT #{limit}
            ) recent ORDER BY turn_no ASC
            """)
    List<KnowledgeTurn> findRecentCompleted(@Param("conversationId") Long conversationId,
                                            @Param("userId") Long userId,
                                            @Param("limit") int limit);

    /**
     * 僵尸轮次：PROCESSING 且会话执行权占用超过判死阈值的轮次（runbook §6.4）。
     *
     * <p>占用时间取 {@code active_request_started_at}（执行权起点），最久未更新优先；
     * 判死阈值必须大于问答总超时，否则会把健康的慢回答误判成中断。
     */
    @Select("""
            SELECT t.* FROM knowledge_turns t
              JOIN knowledge_conversations c ON c.id = t.conversation_id
             WHERE t.status = 'PROCESSING'
               AND c.active_request_id IS NOT NULL
               AND c.active_request_started_at < #{staleBefore}
             ORDER BY c.active_request_started_at ASC
             LIMIT #{limit}
            """)
    List<KnowledgeTurn> findStaleProcessing(@Param("staleBefore") LocalDateTime staleBefore,
                                            @Param("limit") int limit);

    /**
     * 历史页游标查询（runbook §5.4）：{@code beforeTurnNo} 为空取最新一页，否则取更早的轮次；
     * 按 turn_no 降序取页，调用方按需反转为时间正序。
     */
    @Select("""
            SELECT * FROM knowledge_turns
             WHERE conversation_id = #{conversationId}
               AND user_id = #{userId}
               AND (#{beforeTurnNo} IS NULL OR turn_no < #{beforeTurnNo})
             ORDER BY turn_no DESC
             LIMIT #{limit}
            """)
    List<KnowledgeTurn> findPageBefore(@Param("conversationId") Long conversationId,
                                       @Param("userId") Long userId,
                                       @Param("beforeTurnNo") Integer beforeTurnNo,
                                       @Param("limit") int limit);

    @Select("SELECT id FROM knowledge_turns WHERE conversation_id = #{conversationId}")
    List<Long> findIdsByConversationId(@Param("conversationId") Long conversationId);

    @org.apache.ibatis.annotations.Delete(
            "DELETE FROM knowledge_turns WHERE conversation_id = #{conversationId} AND user_id = #{userId}")
    int deleteByConversationId(@Param("conversationId") Long conversationId,
                               @Param("userId") Long userId);
}
