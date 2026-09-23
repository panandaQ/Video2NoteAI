package com.example.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.server.dto.MediaImportStatus;
import com.example.server.entity.MediaFile;
import com.example.server.source.VideoPlatform;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 媒体单元数据访问。
 *
 * <p>媒体身份查询必须带 {@code user_id}：同一平台单元在不同用户下是各自的记录，跨用户共享来源表不在首期范围内。
 * 状态推进使用带旧状态条件的 UPDATE，锁只减少重复成本，数据库 CAS 才是事实。
 */
@Mapper
public interface MediaFileMapper extends BaseMapper<MediaFile> {

    /**
     * 按可播放单元唯一身份查询本用户已有媒体。
     * 唯一键冲突时的回查走同一语句，因此它是并发复用路径的收敛点。
     */
    @Select("""
            SELECT * FROM media_files
             WHERE user_id = #{userId}
               AND platform = #{platform}
               AND resource_type = #{resourceType}
               AND external_resource_id = #{externalResourceId}
               AND external_unit_id = #{externalUnitId}
            """)
    MediaFile findBySourceUnit(@Param("userId") Long userId,
                               @Param("platform") VideoPlatform platform,
                               @Param("resourceType") String resourceType,
                               @Param("externalResourceId") String externalResourceId,
                               @Param("externalUnitId") String externalUnitId);

    @Update("UPDATE media_files SET status = #{next} WHERE id = #{id} AND status = #{expected}")
    int casStatus(@Param("id") Long id,
                  @Param("expected") MediaImportStatus expected,
                  @Param("next") MediaImportStatus next);

    /**
     * 恢复扫描入口：取超时未推进的 URL 媒体。
     *
     * <p>{@code platform IS NULL} 的旧上传记录不参与恢复——它们没有来源身份，也没有中间态。
     * 走 {@code idx_media_status_updated} 索引，按最久未更新优先，保证卡死最久的先被处理。
     */
    @Select("""
            SELECT * FROM media_files
             WHERE platform IS NOT NULL
               AND status = #{status}
               AND updated_at < #{staleBefore}
             ORDER BY updated_at ASC
             LIMIT #{limit}
            """)
    List<MediaFile> findStaleByStatus(@Param("status") MediaImportStatus status,
                                      @Param("staleBefore") LocalDateTime staleBefore,
                                      @Param("limit") int limit);

    /**
     * 恢复扫描入口（多状态合并版）：一条语句取多种超时状态。
     *
     * <p>扫描每轮要检查 7 种媒体状态；逐个状态发一条 SQL 会让"固定节奏的轮询"变成十几条语句/轮。
     * 合并成一条之后，轮询的语句数只与"状态分组数"有关，与状态种类数无关，语义完全不变
     * （调用方按行上的 {@code status} 分组后走各自分支）。
     */
    @Select("""
            <script>
            SELECT * FROM media_files
             WHERE platform IS NOT NULL
               AND status IN
               <foreach item="item" collection="statuses" open="(" separator="," close=")">#{item}</foreach>
               AND updated_at &lt; #{staleBefore}
             ORDER BY updated_at ASC
             LIMIT #{limit}
            </script>
            """)
    List<MediaFile> findStaleByStatuses(@Param("statuses") Collection<MediaImportStatus> statuses,
                                        @Param("staleBefore") LocalDateTime staleBefore,
                                        @Param("limit") int limit);

    /**
     * 恢复扫描重投计数：只增加尝试次数，不改业务状态。
     *
     * <p>{@code acquire_attempt_count} 同时是“消息投递后没有任何进展”的自动重投预算真源：
     * 每次恢复重投都消耗一次，避免消息反复丢失时无限重投；用户显式重试会重置该预算。
     */
    @Update("UPDATE media_files SET acquire_attempt_count = acquire_attempt_count + 1 WHERE id = #{id}")
    int incrementAcquireAttempt(@Param("id") Long id);

    /**
     * 知识问答入口的 READY 归属查询（模块二设计 §10.1）：一条语句同时校验归属与就绪。
     *
     * <p>未命中后调用方还要用 {@link #findOwnedIdById} 区分“媒体未 READY”（409 MEDIA_NOT_READY）
     * 与“不存在或越权”（统一 404）——两条查询的目的就是不泄漏其他用户的媒体状态。
     */
    @Select("SELECT * FROM media_files WHERE id = #{id} AND user_id = #{userId} AND status = 'READY'")
    MediaFile findOwnedReadyById(@Param("id") Long id, @Param("userId") Long userId);

    /** 字幕重建工具入口：所有 READY 的 B 站媒体（不分用户，重建按内容哈希去重）。 */
    @Select("SELECT * FROM media_files WHERE platform = 'BILIBILI' AND status = 'READY'")
    List<MediaFile> findReadyBilibili();

    /** 归属探针：只回答“该媒体是否属于当前用户”，不返回任何字段内容。 */
    @Select("SELECT id FROM media_files WHERE id = #{id} AND user_id = #{userId}")
    Long findOwnedIdById(@Param("id") Long id, @Param("userId") Long userId);

    /** 用户显式重试：重置自动重投预算，让恢复扫描重新计算而不是立刻再次判失败。 */
    @Update("UPDATE media_files SET acquire_attempt_count = 0 WHERE id = #{id}")
    int resetAcquireAttempt(@Param("id") Long id);

    /** 把用户条目挂到共享内容资产上；已有引用时不改动（先到者为准）。 */
    @Update("""
            UPDATE media_files SET content_asset_id = #{assetId}
             WHERE id = #{mediaId} AND content_asset_id IS NULL
            """)
    int bindContentAsset(@Param("mediaId") Long mediaId, @Param("assetId") Long assetId);

    /**
     * 还在等这份共享字节的条目（D-068）：内容资产已就绪时用它们唤醒等待者。
     *
     * <p>只取"没有对象引用且还没走进分析"的状态，外加一条 {@code FAILED + acquire_retryable=1}——
     * 那是"当时下载失败、但用户仍可重试"的条目，既然字节现在免费了，就应该跟着一起恢复。
     * 确定性失败（{@code retryable=0}）不在此列：再试一万次结果一样。
     */
    @Select("""
            SELECT id FROM media_files
             WHERE content_asset_id = #{assetId}
               AND (file_path IS NULL OR file_path = '')
               AND (status IN ('PENDING_DISPATCH', 'QUEUED', 'ACQUIRING', 'DISPATCH_FAILED')
                    OR (status = 'FAILED' AND acquire_retryable = 1))
             ORDER BY id ASC
             LIMIT #{limit}
            """)
    List<Long> findAwaitingSharedBytes(@Param("assetId") Long assetId, @Param("limit") int limit);

    /**
     * 命中跨用户共享字节：把资产的对象引用直接写进用户条目（D-068 / AC-06）。
     *
     * <p>{@code file_path IS NULL} 是必要条件：条目已经有自己的对象引用时绝不覆盖——
     * 那可能是同一次导入刚刚下载好的字节。写入之后获取链路会按"对象已在存储里"直接推进，
     * 因此这条 UPDATE 就是"不重复下载"的全部机制。
     */
    @Update("""
            UPDATE media_files
               SET content_asset_id = #{assetId},
                   file_path = #{objectRef},
                   content_hash = #{contentHash},
                   file_size = #{fileSize},
                   cover_url = COALESCE(#{coverRef}, cover_url)
             WHERE id = #{mediaId} AND file_path IS NULL
            """)
    int adoptSharedBytes(@Param("mediaId") Long mediaId,
                         @Param("assetId") Long assetId,
                         @Param("objectRef") String objectRef,
                         @Param("contentHash") String contentHash,
                         @Param("fileSize") Long fileSize,
                         @Param("coverRef") String coverRef);

    /**
     * 媒体入库成功：写入受管对象地址、内容哈希与文件大小，并清除获取失败痕迹。
     * 只在 {@code ACQUIRING} 期间生效，重复消息不会覆盖更新的状态。
     *
     * <p>{@code cover_url} 用 {@code COALESCE} 更新：封面抓取失败时不得把上一次已有的封面清空。
     */
    @Update("""
            UPDATE media_files
               SET status = #{next},
                   file_path = #{filePath},
                   content_hash = #{contentHash},
                   file_size = #{fileSize},
                   cover_url = COALESCE(#{coverUrl}, cover_url),
                   acquire_retryable = 0,
                   acquire_error_code = NULL,
                   acquire_error_message = NULL
             WHERE id = #{id} AND status = #{expected}
            """)
    int markMediaReady(@Param("id") Long id,
                       @Param("expected") MediaImportStatus expected,
                       @Param("next") MediaImportStatus next,
                       @Param("filePath") String filePath,
                       @Param("contentHash") String contentHash,
                       @Param("fileSize") Long fileSize,
                       @Param("coverUrl") String coverUrl);

    /** 记录一次获取失败；可重试性由错误码决定，重试次数真源是 {@code acquire_attempt_count}。 */
    @Update("""
            UPDATE media_files
               SET status = #{next},
                   acquire_attempt_count = acquire_attempt_count + 1,
                   acquire_retryable = #{retryable},
                   acquire_error_code = #{errorCode},
                   acquire_error_message = #{errorMessage}
             WHERE id = #{id} AND status = #{expected}
            """)
    int markAcquireFailed(@Param("id") Long id,
                          @Param("expected") MediaImportStatus expected,
                          @Param("next") MediaImportStatus next,
                          @Param("retryable") boolean retryable,
                          @Param("errorCode") String errorCode,
                          @Param("errorMessage") String errorMessage);

    /**
     * 进入默认笔记链路后记录笔记错误与尝试次数。
     * 笔记结果真源是 {@code agent_checkpoints}，这里只保存业务状态和可诊断信息。
     */
    @Update("""
            UPDATE media_files
               SET status = #{next},
                   note_profile_version = #{profileVersion},
                   note_attempt_count = note_attempt_count + 1,
                   note_retryable = #{retryable},
                   note_error_code = #{errorCode},
                   note_error_message = #{errorMessage}
             WHERE id = #{id} AND status = #{expected}
            """)
    int markNoteFailed(@Param("id") Long id,
                       @Param("expected") MediaImportStatus expected,
                       @Param("next") MediaImportStatus next,
                       @Param("profileVersion") String profileVersion,
                       @Param("retryable") boolean retryable,
                       @Param("errorCode") String errorCode,
                       @Param("errorMessage") String errorMessage);

    /** 默认笔记完成：清空笔记错误痕迹并记录完成的 profile 版本。 */
    @Update("""
            UPDATE media_files
               SET status = #{next},
                   note_profile_version = #{profileVersion},
                   note_retryable = 0,
                   note_error_code = NULL,
                   note_error_message = NULL
             WHERE id = #{id} AND status = #{expected}
            """)
    int markNoteCompleted(@Param("id") Long id,
                          @Param("expected") MediaImportStatus expected,
                          @Param("next") MediaImportStatus next,
                          @Param("profileVersion") String profileVersion);

    /**
     * 默认笔记完成但 Critic 达到最大轮次仍未通过（D-104）：状态照常推进到 READY，
     * 只把复核提示写进 {@code note_error_code}/{@code note_error_message} 供前端展示角标，
     * 不计入尝试次数、不标记可重试——重投不会让证据变得更可核验，这是展示态而非故障态。
     */
    @Update("""
            UPDATE media_files
               SET status = #{next},
                   note_profile_version = #{profileVersion},
                   note_retryable = 0,
                   note_error_code = #{errorCode},
                   note_error_message = #{errorMessage}
             WHERE id = #{id} AND status = #{expected}
            """)
    int markNoteCompletedWithWarning(@Param("id") Long id,
                                     @Param("expected") MediaImportStatus expected,
                                     @Param("next") MediaImportStatus next,
                                     @Param("profileVersion") String profileVersion,
                                     @Param("errorCode") String errorCode,
                                     @Param("errorMessage") String errorMessage);

    /**
     * 后台容量不足：保持当前状态等待恢复扫描重投。
     * 不计入 {@code note_attempt_count}——它不是一次真正的处理尝试，否则会在容量波动时快速耗尽重试额度。
     */
    @Update("""
            UPDATE media_files
               SET note_profile_version = #{profileVersion},
                   note_retryable = 1,
                   note_error_code = #{errorCode},
                   note_error_message = #{errorMessage}
             WHERE id = #{id} AND status = #{expected}
            """)
    int markNoteDeferred(@Param("id") Long id,
                         @Param("expected") MediaImportStatus expected,
                         @Param("profileVersion") String profileVersion,
                         @Param("errorCode") String errorCode,
                         @Param("errorMessage") String errorMessage);
}
