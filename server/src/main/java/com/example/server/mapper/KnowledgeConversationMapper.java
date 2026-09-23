package com.example.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.server.entity.KnowledgeConversation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 知识问答会话数据访问（D-084：执行权是 MySQL CAS，不是 Redis 锁）。
 *
 * <p>状态推进一律使用带条件的 UPDATE：只有返回值大于 0 才代表本次调用取得了执行权。
 * 任何按 ID 的对外查询都必须带 {@code user_id}，不存在与越权统一 404。
 */
@Mapper
public interface KnowledgeConversationMapper extends BaseMapper<KnowledgeConversation> {

    /** 归属校验查询：任何按 ID 的对外查询都必须带 user_id。 */
    @Select("SELECT * FROM knowledge_conversations WHERE id = #{id} AND user_id = #{userId}")
    KnowledgeConversation findOwnedById(@Param("id") Long id, @Param("userId") Long userId);

    /** 最近会话首页：MySQL 仍是列表与归属事实来源。 */
    @Select("""
            SELECT * FROM knowledge_conversations
             WHERE user_id = #{userId}
               AND status = 'ACTIVE'
             ORDER BY updated_at DESC, id DESC
             LIMIT #{limit}
            """)
    List<KnowledgeConversation> findOwnedPage(@Param("userId") Long userId,
                                              @Param("limit") int limit);

    /** 以 cursor 会话的 (updated_at,id) 作为稳定游标读取更早页面。 */
    @Select("""
            SELECT c.* FROM knowledge_conversations c
             WHERE c.user_id = #{userId}
               AND c.status = 'ACTIVE'
               AND EXISTS (SELECT 1 FROM knowledge_conversations cursor_row
                            WHERE cursor_row.id = #{cursor}
                              AND cursor_row.user_id = #{userId}
                              AND (c.updated_at < cursor_row.updated_at
                                   OR (c.updated_at = cursor_row.updated_at AND c.id < cursor_row.id)))
             ORDER BY c.updated_at DESC, c.id DESC
             LIMIT #{limit}
            """)
    List<KnowledgeConversation> findOwnedPageBefore(@Param("userId") Long userId,
                                                    @Param("cursor") Long cursor,
                                                    @Param("limit") int limit);

    /** 当前视频的最近会话，只返回用户自己的 SINGLE_VIDEO 会话。 */
    @Select("""
            SELECT * FROM knowledge_conversations
             WHERE user_id = #{userId}
               AND status = 'ACTIVE'
               AND scope_type = 'SINGLE_VIDEO'
               AND scope_media_id = #{mediaId}
             ORDER BY updated_at DESC, id DESC
             LIMIT #{limit}
            """)
    List<KnowledgeConversation> findOwnedMediaPage(@Param("userId") Long userId,
                                                   @Param("mediaId") Long mediaId,
                                                   @Param("limit") int limit);

    /** 当前视频会话的稳定游标分页。 */
    @Select("""
            SELECT c.* FROM knowledge_conversations c
             WHERE c.user_id = #{userId}
               AND c.status = 'ACTIVE'
               AND c.scope_type = 'SINGLE_VIDEO'
               AND c.scope_media_id = #{mediaId}
               AND EXISTS (SELECT 1 FROM knowledge_conversations cursor_row
                            WHERE cursor_row.id = #{cursor}
                              AND cursor_row.user_id = #{userId}
                              AND (c.updated_at < cursor_row.updated_at
                                   OR (c.updated_at = cursor_row.updated_at AND c.id < cursor_row.id)))
             ORDER BY c.updated_at DESC, c.id DESC
             LIMIT #{limit}
            """)
    List<KnowledgeConversation> findOwnedMediaPageBefore(@Param("userId") Long userId,
                                                         @Param("mediaId") Long mediaId,
                                                         @Param("cursor") Long cursor,
                                                         @Param("limit") int limit);

    @Select("""
            SELECT id FROM knowledge_conversations
             WHERE user_id = #{userId}
               AND scope_type = 'SINGLE_VIDEO'
               AND scope_media_id = #{mediaId}
            """)
    List<Long> findIdsByOwnedMedia(@Param("userId") Long userId,
                                   @Param("mediaId") Long mediaId);

    @org.apache.ibatis.annotations.Delete("""
            DELETE FROM knowledge_conversations
             WHERE id = #{id} AND user_id = #{userId}
            """)
    int deleteOwnedById(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * 会话执行权 CAS（runbook §2.2）：执行权与 {@code last_turn_no} 在同一条条件更新中取得，
     * 只有一个请求能赢。赢家随后在**同一事务**里重读会话行取到已递增的 {@code last_turn_no}
     * 作为本轮 turnNo——重读不会读到旧值：赢家持有行锁直到事务提交，其他请求无法插入中间状态。
     *
     * @return 1 = 本次取得执行权；0 = 已有其他请求活跃或会话不可用，调用方需重读后分流
     *         （相同 requestId 回放 / 其他请求 409 CONVERSATION_BUSY）
     */
    @Update("""
            UPDATE knowledge_conversations
               SET active_request_id = #{requestId},
                   active_request_started_at = NOW(3),
                   last_turn_no = last_turn_no + 1
             WHERE id = #{id}
               AND user_id = #{userId}
               AND status = 'ACTIVE'
               AND active_request_id IS NULL
            """)
    int claimExecution(@Param("id") Long id,
                       @Param("userId") Long userId,
                       @Param("requestId") String requestId);

    /**
     * 终态释放：版本递增与释放执行权在同一条语句里完成，只对持有执行权的请求生效。
     *
     * @return 1 = 释放成功；0 = 执行权已不属于该请求（僵尸收敛抢先后旧线程返回），调用方必须回滚
     */
    @Update("""
            UPDATE knowledge_conversations
               SET version = version + 1,
                   active_request_id = NULL,
                   active_request_started_at = NULL
             WHERE id = #{id}
               AND user_id = #{userId}
               AND active_request_id = #{requestId}
            """)
    int releaseExecution(@Param("id") Long id,
                         @Param("userId") Long userId,
                         @Param("requestId") String requestId);
}
