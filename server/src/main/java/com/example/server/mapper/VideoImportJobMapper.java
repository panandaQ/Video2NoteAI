package com.example.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.server.dto.VideoImportJobStatus;
import com.example.server.entity.VideoImportJob;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 导入父任务数据访问。
 *
 * <p>状态推进一律使用“带旧状态条件”的 UPDATE：只有返回值大于 0 才代表本次调用取得了执行权。
 * 唯一键冲突（{@code active_request_key}）属于并发复用路径，由服务层回查收敛，不记为系统异常。
 */
@Mapper
public interface VideoImportJobMapper extends BaseMapper<VideoImportJob> {

    @Select("SELECT * FROM video_import_jobs WHERE active_request_key = #{activeRequestKey}")
    VideoImportJob findByActiveRequestKey(@Param("activeRequestKey") String activeRequestKey);

    /** 归属校验查询：任何按 ID 的对外查询都必须带 user_id。 */
    @Select("SELECT * FROM video_import_jobs WHERE id = #{id} AND user_id = #{userId}")
    VideoImportJob findOwnedById(@Param("id") Long id, @Param("userId") Long userId);

    @Update("""
            UPDATE video_import_jobs
               SET status = #{next}
             WHERE id = #{id} AND status = #{expected}
            """)
    int casStatus(@Param("id") Long id,
                  @Param("expected") VideoImportJobStatus expected,
                  @Param("next") VideoImportJobStatus next);

    /** 进入终态时必须在同一语句内清空活跃键，否则相同 URL 再也无法重新提交。 */
    @Update("""
            UPDATE video_import_jobs
               SET status = #{next}, active_request_key = NULL
             WHERE id = #{id} AND status = #{expected}
            """)
    int casStatusAndReleaseActiveKey(@Param("id") Long id,
                                     @Param("expected") VideoImportJobStatus expected,
                                     @Param("next") VideoImportJobStatus next);

    /** 仅当当前没有活跃键时占用；返回 0 表示已有其他活跃任务。 */
    @Update("""
            UPDATE video_import_jobs
               SET active_request_key = #{activeRequestKey}
             WHERE id = #{id} AND active_request_key IS NULL
            """)
    int attachActiveRequestKey(@Param("id") Long id,
                               @Param("activeRequestKey") String activeRequestKey);

    /**
     * 重试受理：同一条语句里推进状态并重新占用活跃键。
     *
     * <p>活跃键带唯一约束：若同一 URL 已有另一个活跃任务，本语句会触发唯一键冲突，
     * 调用方按“并发复用路径”回查并返回那个任务（契约 §3.4）。
     */
    @Update("""
            UPDATE video_import_jobs
               SET status = #{next}, active_request_key = #{activeRequestKey}, attempt_count = attempt_count + 1
             WHERE id = #{id} AND status = #{expected}
            """)
    int casStatusAndAttachActiveKey(@Param("id") Long id,
                                    @Param("expected") VideoImportJobStatus expected,
                                    @Param("next") VideoImportJobStatus next,
                                    @Param("activeRequestKey") String activeRequestKey);

    /** 重试次数真源；Redis 只做热点计数。 */
    @Update("UPDATE video_import_jobs SET attempt_count = attempt_count + 1 WHERE id = #{id}")
    int incrementAttempt(@Param("id") Long id);

    /**
     * 用户显式重试：重置自动恢复预算。
     *
     * <p>没有这一步，用户在恢复预算耗尽后手动重试会立刻被下一轮扫描再次判失败，
     * 人工干预就失去意义（D-053）。
     */
    @Update("UPDATE video_import_jobs SET attempt_count = 0 WHERE id = #{id}")
    int resetAttempt(@Param("id") Long id);

    /** 恢复扫描：取超时未推进的指定状态父任务，最久未更新优先。 */
    @Select("""
            SELECT * FROM video_import_jobs
             WHERE status = #{status} AND updated_at < #{staleBefore}
             ORDER BY updated_at ASC
             LIMIT #{limit}
            """)
    List<VideoImportJob> findStaleByStatus(@Param("status") VideoImportJobStatus status,
                                           @Param("staleBefore") LocalDateTime staleBefore,
                                           @Param("limit") int limit);

    /**
     * 恢复扫描入口（多状态合并版）：一条语句取多种超时状态。
     *
     * <p>与媒体侧同理：把"每轮 4~5 条状态查询"压成一条，轮询语句数不再随状态种类增长。
     * 调用方按行上的 {@code status} 分组后走各自分支（重投、回退、重占活跃键）。
     */
    @Select("""
            <script>
            SELECT * FROM video_import_jobs
             WHERE status IN
             <foreach item="item" collection="statuses" open="(" separator="," close=")">#{item}</foreach>
               AND updated_at &lt; #{staleBefore}
             ORDER BY updated_at ASC
             LIMIT #{limit}
            </script>
            """)
    List<VideoImportJob> findStaleByStatuses(@Param("statuses") Collection<VideoImportJobStatus> statuses,
                                             @Param("staleBefore") LocalDateTime staleBefore,
                                             @Param("limit") int limit);

    /** 恢复扫描对账：超时未推进的非终态父任务（计数字段只是查询优化，事实来源是子项表）。 */
    @Select("""
            SELECT * FROM video_import_jobs
             WHERE status NOT IN ('COMPLETED', 'PARTIAL_SUCCESS', 'FAILED')
               AND updated_at < #{staleBefore}
             ORDER BY updated_at ASC
             LIMIT #{limit}
            """)
    List<VideoImportJob> findNonTerminalStale(@Param("staleBefore") LocalDateTime staleBefore,
                                              @Param("limit") int limit);

    /**
     * 恢复扫描对账：与子项事实矛盾的终态父任务。
     *
     * <p>“全部子项 READY 却停在 FAILED/PARTIAL_SUCCESS”只可能来自崩溃、人工 SQL 或历史缺陷，
     * 但用户看到的是自相矛盾的查询结果。检测出来交给聚合器按子项收敛（D-052）。
     */
    @Select("""
            SELECT j.* FROM video_import_jobs j
             WHERE j.status IN ('FAILED', 'PARTIAL_SUCCESS')
               AND EXISTS (SELECT 1 FROM video_import_items i WHERE i.import_id = j.id)
               AND NOT EXISTS (SELECT 1 FROM video_import_items i
                                WHERE i.import_id = j.id AND i.item_status <> 'READY')
             ORDER BY j.updated_at ASC
             LIMIT #{limit}
            """)
    List<VideoImportJob> findTerminalContradictions(@Param("limit") int limit);

    @Update("UPDATE video_import_jobs SET active_request_key = NULL WHERE id = #{id}")
    int clearActiveRequestKey(@Param("id") Long id);

    /** 解析成功后回写识别结果：目标类型、平台与容器展示快照。 */
    @Update("""
            UPDATE video_import_jobs
               SET target_type = #{targetType},
                   platform = #{platform},
                   container_id = #{containerId},
                   container_title = #{containerTitle}
             WHERE id = #{id}
            """)
    int updateResolvedPlan(@Param("id") Long id,
                           @Param("targetType") String targetType,
                           @Param("platform") String platform,
                           @Param("containerId") String containerId,
                           @Param("containerTitle") String containerTitle);

    /** 记录异步失败的受控文案；禁止写入 yt-dlp 原始输出、Cookie、Token 或签名地址。 */
    @Update("""
            UPDATE video_import_jobs
               SET error_code = #{errorCode}, error_message = #{errorMessage}, retryable = #{retryable}
             WHERE id = #{id}
            """)
    int updateError(@Param("id") Long id,
                    @Param("errorCode") String errorCode,
                    @Param("errorMessage") String errorMessage,
                    @Param("retryable") boolean retryable);

    /** 计数汇总值；事实来源是 {@code video_import_items}，异常退出后可按子项重算。 */
    @Update("""
            UPDATE video_import_jobs
               SET total_count = #{total}, reused_count = #{reused},
                   completed_count = #{completed}, failed_count = #{failed}
             WHERE id = #{id}
            """)
    int updateCounts(@Param("id") Long id,
                     @Param("total") int total,
                     @Param("reused") int reused,
                     @Param("completed") int completed,
                     @Param("failed") int failed);
}
