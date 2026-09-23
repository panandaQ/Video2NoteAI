package com.example.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.server.dto.MediaImportStatus;
import com.example.server.entity.VideoImportItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 导入子项数据访问。
 *
 * <p>子项表是父任务计数的事实来源：只有条件更新真正改变了子项状态，父任务计数才允许增减。
 * 按媒体更新时必须跳过已经终态的子项，防止重复消息把完成项改回中间态。
 */
@Mapper
public interface VideoImportItemMapper extends BaseMapper<VideoImportItem> {

    /** 查询顺序与契约一致：{@code itemOrder ASC, mediaId ASC}。 */
    @Select("""
            SELECT * FROM video_import_items
             WHERE import_id = #{importId}
             ORDER BY item_order ASC, media_id ASC
            """)
    List<VideoImportItem> findByImportId(@Param("importId") Long importId);

    @Select("SELECT * FROM video_import_items WHERE media_id = #{mediaId}")
    List<VideoImportItem> findByMediaId(@Param("mediaId") Long mediaId);

    @Select("""
            SELECT * FROM video_import_items
             WHERE import_id = #{importId} AND media_id = #{mediaId}
            """)
    VideoImportItem findOne(@Param("importId") Long importId, @Param("mediaId") Long mediaId);

    /** 取得单个子项的状态推进权；返回 0 表示状态已被其他消费者推进或消息重复。 */
    @Update("""
            UPDATE video_import_items
               SET item_status = #{next}, retryable = #{retryable}, error_code = #{errorCode}
             WHERE import_id = #{importId} AND media_id = #{mediaId} AND item_status = #{expected}
            """)
    int casItemStatus(@Param("importId") Long importId,
                      @Param("mediaId") Long mediaId,
                      @Param("expected") MediaImportStatus expected,
                      @Param("next") MediaImportStatus next,
                      @Param("retryable") boolean retryable,
                      @Param("errorCode") String errorCode);

    /**
     * 把一个媒体的终态同步到所有非终态子项（同一媒体可能属于多个历史导入任务）。
     *
     * <p><b>只允许写终态</b>（D-066）：处理中的子项不再跟随媒体的每个中间态更新。
     * 媒体状态是执行权与恢复位置的唯一依据，子项只承担"关联"与"父任务计数事实来源"两个职责，
     * 因此子项的持久化粒度收敛为 {@code PENDING_*} → {@code READY/FAILED}。这样做同时消除了
     * "每个单元 6 次状态推进 × 每次都要同步子项并重算父任务"的写入与读取放大器；
     * 中间态只存在于 {@code media_files}，查询接口按媒体状态投影（见 {@code VideoImportQueryService}）。
     *
     * <p>{@code terminalStatus} 在 SQL 里被限制为 {@code READY/FAILED}：传中间态不会静默成功，
     * 调用者无法再借这个入口写处理中状态。
     *
     * <p>终态列表在 SQL 中显式写出：SQL 无法引用枚举，改状态枚举时必须同步此处。
     */
    @Update("""
            UPDATE video_import_items
               SET item_status = #{terminalStatus}, retryable = #{retryable}, error_code = #{errorCode}
             WHERE media_id = #{mediaId}
               AND item_status NOT IN ('READY', 'FAILED', 'COMPLETED')
               AND #{terminalStatus} IN ('READY', 'FAILED')
            """)
    int updatePendingToTerminalByMediaId(@Param("mediaId") Long mediaId,
                                         @Param("terminalStatus") MediaImportStatus terminalStatus,
                                         @Param("retryable") boolean retryable,
                                         @Param("errorCode") String errorCode);

    /**
     * 按状态聚合子项计数（父任务重算的唯一输入）。
     *
     * <p>为什么不在 Java 里数：父任务每推进一次就重算一次，若每次都把全部子项读回来再计数，
     * 单元数为 N 时总读取量是 O(N²)（N 个单元 × 每个单元约 6 次推进 × N 行）。这里只回一行/状态，
     * 既省读取又省网络往返。
     */
    @Select("""
            SELECT item_status AS status, COUNT(*) AS total, COALESCE(SUM(reused), 0) AS reused
              FROM video_import_items
             WHERE import_id = #{importId}
             GROUP BY item_status
            """)
    List<ItemStatusCount> countByStatusGrouped(@Param("importId") Long importId);

    /** 一个状态下的子项数量；{@code status} 用字符串承接，避免依赖枚举类型处理器。 */
    record ItemStatusCount(String status, long total, long reused) {
    }

    /** 按状态统计子项数量，用于从子项重算父任务计数。 */
    @Select("""
            SELECT COUNT(*) FROM video_import_items
             WHERE import_id = #{importId} AND item_status = #{status}
            """)
    int countByStatus(@Param("importId") Long importId,
                      @Param("status") MediaImportStatus status);

    /**
     * 恢复扫描入口：仍在处理中的子项（终态列表必须与 {@link #updatePendingToTerminalByMediaId} 保持一致）。
     *
     * <p>用于发现“媒体行已经不存在”的悬挂子项：它们永远不会再收到状态推进，
     * 不收敛就会让父任务永久停在处理中。
     *
     * <p>子项不再写中间态后（D-066），处理中的子项会长期停留在这个集合里——悬挂判定以"媒体行是否存在"
     * 为准，因此不会误伤；但排序必须按 {@code updated_at ASC}（最久没动的先看）并保留 {@code LIMIT}，
     * 否则长期非终态的行会挤占批量窗口。
     */
    @Select("""
            SELECT * FROM video_import_items
             WHERE item_status NOT IN ('READY', 'FAILED', 'COMPLETED')
             ORDER BY updated_at ASC
             LIMIT #{limit}
            """)
    List<VideoImportItem> findNonTerminal(@Param("limit") int limit);
}
