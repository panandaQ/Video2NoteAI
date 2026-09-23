package com.example.server.config;

import java.util.Set;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * 在线导入的容量、超时和缓存参数。
 *
 * <p>集中在一个 {@code @ConfigurationProperties} 中，禁止在业务类里散落 {@code @Value}。
 * 所有项都有明确默认值；非法值在启动期即失败，不静默使用危险默认。
 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "video.import")
public class VideoImportProperties {

    /** 提交 URL 的最大长度，与请求 DTO 的 Bean Validation 上限保持一致。 */
    @Min(1)
    private int maxUrlLength = 2048;

    /** 单次自动展开的 Unit 上限；超限整单失败，不静默截断。 */
    @Min(1)
    private int maxItems = 50;

    /** 单用户每分钟可提交的有效导入请求总数，包含复用活跃任务的重复提交。 */
    @Min(1)
    private int userSubmissionsPerMinute = 60;

    /** 单客户端 IP 每分钟可提交的有效导入请求总数。 */
    @Min(1)
    private int ipSubmissionsPerMinute = 120;

    /** 单用户每分钟可新建的导入父任务数；重复请求不消耗配额。 */
    @Min(1)
    private int userNewJobsPerMinute = 5;

    /** 全局每分钟可新建的导入父任务数。 */
    @Min(1)
    private int globalNewJobsPerMinute = 30;

    /** 解析阶段的临时网络异常等待时间的展示上限，单位秒。 */
    @Min(1)
    private int resolveTimeoutSeconds = 60;

    /** 请求指纹到 importId 的映射缓存时长，单位小时。 */
    @Min(1)
    private int requestCacheTtlHours = 6;

    /** 恢复扫描判定处理中状态超时的秒数。 */
    @Min(1)
    private int recoveryStaleSeconds = 60;

    /** 恢复扫描的固定间隔秒数。 */
    @Min(1)
    private int recoveryDelaySeconds = 30;

    /** 每次恢复扫描处理的最大记录数。 */
    @Min(1)
    private int recoveryBatchSize = 100;

    /**
     * 恢复扫描空闲退避的最大倍数（1 = 关闭退避）。
     *
     * <p>扫描是固定节奏轮询：空闲时也在查库。连续 3 轮无动作后按倍数跳轮，上限即本值。
     * 默认 2 意味着"空闲时最多每 2 个周期扫一次"，最坏情况把卡死记录的发现时间从 1 个周期推迟到 2 个周期；
     * 有任何动作立即回到基础频率。设为 1 可恢复"每轮必扫"。
     */
    @Min(1)
    private int recoveryIdleBackoffMax = 2;

    /** 解析与获取的有限重试上限，与现有分析消费者保持一致的量级。 */
    @Min(1)
    private int maxAttempts = 3;

    /**
     * 同时进行的媒体下载数上限。
     *
     * <p>下载被隔离在专用执行器里，本值就是该执行器的线程数（也是并发上限）。
     * 取值的依据是带宽：匿名 480P 单连接实测约 465KB/s，并发过高只会互相拖慢并让每个下载更容易超时。
     */
    @Min(1)
    private int downloadConcurrency = 4;

    /** 下载执行器的等待队列容量；打满后本次投递被放弃，媒体保持 QUEUED 由恢复扫描重投。 */
    @Min(1)
    private int downloadQueueCapacity = 200;

    /**
     * 单个客户端 IP 每分钟可新建的导入任务数。
     *
     * <p>为什么在"用户级"之外再加一层：用户级限额按账号计数，**多开账号即可绕过**（注册本身没有防护）。
     * IP 维度按来源地址兜住这种放大，取值明显高于用户级（默认 20），因此同一个出口 IP 下多个正常用户
     * （办公室 NAT、校园网）不会被误伤。
     */
    @Min(1)
    private int ipNewJobsPerMinute = 20;

    /** 可选清晰度白名单（高度像素）。用户未选择时保持既有 480P 默认；登录 Cookie 可选更高档。 */
    public static final Set<Integer> SUPPORTED_QUALITIES = Set.of(360, 480, 720, 1080);

    /**
     * 既有 480P 选择器的逐字符默认值（D-045 实测结论：匿名高码率流被 PCDN 限速）。
     * 配置化后默认保持不变（D-072/D-075）；登录后可通过 `video.import.format-selector` 调高。
     */
    public static final String DEFAULT_FORMAT_SELECTOR = formatSelectorForHeight(480);

    /** yt-dlp 画质选择器；默认值与既有硬编码逐字符一致。 */
    @NotBlank
    private String formatSelector = DEFAULT_FORMAT_SELECTOR;

    /**
     * 按目标高度生成画质选择器：与默认 480P 同一套 AVC/AAC + 回退链，只把 {@code height<=480}
     * 换成目标高度。用户选清晰度时优先于 {@link #formatSelector} 配置。
     */
    public static String formatSelectorForHeight(int height) {
        return "bv*[vcodec^=avc1][height<=" + height + "][ext=mp4]"
                + "+ba[acodec^=mp4a][ext=m4a]"
                + "/b[vcodec^=avc1][height<=" + height + "][ext=mp4]"
                + "/bv*[vcodec^=avc1][height<=" + height + "]+ba[acodec^=mp4a]"
                + "/b[height<=" + height + "]/bv*+ba/b";
    }

    /** 字幕优先开关：关闭后所有媒体直接走 ASR（计划 §4.1）。 */
    private boolean subtitleEnabled = true;

    /** 字幕覆盖率门槛，区间 (0,1]：区间并集覆盖率低于该值则回退 ASR。 */
    @DecimalMin(value = "0", inclusive = false)
    @DecimalMax("1.0")
    private double subtitleMinCoverage = 0.6;

    /** 字幕最大无内容空洞秒数，超过该空洞即质量门槛不通过。 */
    @Min(1)
    private int subtitleMaxGapSeconds = 180;

    /** 字幕 CC JSON 响应体字节上限，超过立即中止下载。 */
    @Min(1)
    private long subtitleMaxBytes = 5_242_880L;

    /** View 章节开关（计划 §4.1）。 */
    private boolean chaptersEnabled = true;

    /** chapters.json 响应体字节上限。 */
    @Min(1)
    private long chaptersMaxBytes = 1_048_576L;
}
