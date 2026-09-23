package com.example.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.server.entity.KnowledgeTurnEvidence;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 问答证据数据访问。
 *
 * <p>证据只随轮次终态在同一事务里写入（runbook §6.3）：插入前完成条件更新已经成功，
 * 因此这里不重复校验执行权。{@code uk_knowledge_evidence_rank (turn_id, evidence_rank)}
 * 保证一轮内引用编号不重复。
 */
@Mapper
public interface KnowledgeTurnEvidenceMapper extends BaseMapper<KnowledgeTurnEvidence> {

    /**
     * 批量插入本轮引用证据；调用方保证列表非空（空列表直接跳过本语句）。
     *
     * <p>返回值为插入行数；与完成条件更新在同一事务内，任一失败整体回滚。
     */
    @Insert("""
            <script>
            INSERT INTO knowledge_turn_evidence
                (turn_id, evidence_rank, media_id, title_snapshot, start_ms, end_ms, source, snippet, score)
            VALUES
            <foreach collection="evidence" item="e" separator=",">
                (#{turnId}, #{e.evidenceRank}, #{e.mediaId}, #{e.titleSnapshot},
                 #{e.startMs}, #{e.endMs}, #{e.source}, #{e.snippet}, #{e.score})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("turnId") Long turnId,
                    @Param("evidence") List<KnowledgeTurnEvidence> evidence);

    @Select("""
            SELECT * FROM knowledge_turn_evidence
             WHERE turn_id = #{turnId}
             ORDER BY evidence_rank ASC
            """)
    List<KnowledgeTurnEvidence> findByTurnId(@Param("turnId") Long turnId);

    /** 历史页批量装载：避免每轮一次查询。调用方保证列表非空。 */
    @Select("""
            <script>
            SELECT * FROM knowledge_turn_evidence
             WHERE turn_id IN
            <foreach item="id" collection="turnIds" open="(" separator="," close=")">#{id}</foreach>
             ORDER BY turn_id ASC, evidence_rank ASC
            </script>
            """)
    List<KnowledgeTurnEvidence> findByTurnIds(@Param("turnIds") List<Long> turnIds);

    @org.apache.ibatis.annotations.Delete("""
            <script>
            DELETE FROM knowledge_turn_evidence
             WHERE turn_id IN
            <foreach item="id" collection="turnIds" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    int deleteByTurnIds(@Param("turnIds") List<Long> turnIds);
}
