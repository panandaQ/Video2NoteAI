package com.example.server.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

@Mapper
public interface AgentCheckpointMapper {

    @Select("SELECT payload FROM agent_checkpoints WHERE media_id = #{mediaId} AND checkpoint_key = #{checkpointKey}")
    String findPayload(@Param("mediaId") Long mediaId,
                       @Param("checkpointKey") String checkpointKey);

    @Select("SELECT stage FROM agent_checkpoints WHERE media_id = #{mediaId} AND checkpoint_key = #{checkpointKey}")
    String findStage(@Param("mediaId") Long mediaId,
                     @Param("checkpointKey") String checkpointKey);

    /**
     * 该媒体最近一次检查点写入时间。
     *
     * <p>只读用途：恢复扫描需要判断"活跃键持有者是不是已经死了"。没有任何检查点记录时返回 {@code null}。
     */
    @Select("SELECT MAX(updated_at) FROM agent_checkpoints WHERE media_id = #{mediaId}")
    LocalDateTime findLatestUpdatedAt(@Param("mediaId") Long mediaId);

    @Insert("""
            INSERT INTO agent_checkpoints(media_id, checkpoint_key, stage, payload)
            VALUES(#{mediaId}, #{checkpointKey}, #{stage}, #{payload})
            ON DUPLICATE KEY UPDATE stage = VALUES(stage), payload = VALUES(payload), updated_at = CURRENT_TIMESTAMP(3)
            """)
    void upsert(@Param("mediaId") Long mediaId,
                @Param("checkpointKey") String checkpointKey,
                @Param("stage") String stage,
                @Param("payload") String payload);

    @Delete("DELETE FROM agent_checkpoints WHERE media_id = #{mediaId} AND checkpoint_key LIKE CONCAT(#{prefix}, '%')")
    void deleteByPrefix(@Param("mediaId") Long mediaId, @Param("prefix") String prefix);

    @Delete("DELETE FROM agent_checkpoints WHERE media_id = #{mediaId} AND checkpoint_key = #{checkpointKey}")
    void delete(@Param("mediaId") Long mediaId, @Param("checkpointKey") String checkpointKey);

    @Delete("DELETE FROM agent_checkpoints WHERE media_id = #{mediaId}")
    void deleteByMediaId(@Param("mediaId") Long mediaId);
}
