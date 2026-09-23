package com.example.server.config;

import com.example.server.service.ingest.VideoNoteProfile;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * 默认视频笔记的版本与后台容量参数。
 *
 * <p>默认笔记是系统自动任务，不能复用用户交互的“每分钟 5 次”拒绝语义，否则合集会在少量单元后
 * 大量失败。这里单独定义后台速率，S3 实现延迟重投时使用。
 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "video.note")
public class VideoNoteProperties {

    /**
     * 默认笔记的任务身份版本，参与 {@code goalDigest}。
     * 默认值取自代码内的唯一定义 {@link VideoNoteProfile#VERSION}；只有灰度切换版本时才需要覆盖。
     */
    @NotBlank
    private String profileVersion = VideoNoteProfile.VERSION;

    /** 单用户每分钟后台笔记投递上限。 */
    @Min(1)
    private int backgroundUserPerMinute = 10;

    /** 全局每分钟后台笔记投递上限。 */
    @Min(1)
    private int backgroundGlobalPerMinute = 60;

    /** 默认笔记的业务尝试上限，真源是 {@code media_files.note_attempt_count}。 */
    @Min(1)
    private int maxAttempts = 3;

    /**
     * 判定"持有活跃键的分析进程已经死了"的静默秒数（默认 30 分钟）。
     *
     * <p>活跃键 TTL 是 6 小时，进程被杀后键仍在，期间重投一律被挡回。恢复扫描用检查点最后写入时间判断存活：
     * 超过本阈值没有任何检查点推进，就认为持键者已死，释放活跃键并重投。
     * 取值必须明显大于单个分析任务的执行预算（`agent.budget.max-duration-ms`），否则会把"跑得慢"误判成"死了"，
     * 造成重复 ASR/LLM 开销。
     */
    @Min(60)
    private int recoveryStaleSeconds = 1800;
}
