package com.example.server.dto;

/**
 * {@code analysis-input/v2/manifest.json} 的结构（计划 §4.4）。
 *
 * <p>记录四段来源身份、player 探测结果、字幕与章节状态和对象摘要；**不保存 Cookie 或
 * 原始签名 URL**（契约 §11 红线）。manifest 最后发布：崩溃在 subtitle/chapters 对象写入后、
 * manifest 发布前时，恢复流程重新校验并发布，不覆盖已确认的同版本对象（首写者胜出）。
 */
public record AnalysisInputManifest(
        int schemaVersion,
        String manifestVersion,
        String platform,
        String resourceType,
        String externalResourceId,
        String externalUnitId,
        String contentHash,
        String playerProbeStatus,
        String subtitleStatus,
        String subtitleLan,
        String subtitleAiType,
        String chapterStatus,
        int chapterCount,
        long generatedAtEpochMs,
        String subtitleObject,
        String chaptersObject
) {
    public static final String MANIFEST_VERSION = "V2";

    public static final String PROBE_OK = "OK";

    public static final String SUBTITLE_AVAILABLE = "AVAILABLE";
    public static final String SUBTITLE_FAILED = "FAILED";
    public static final String SUBTITLE_ABSENT = "ABSENT";
    /** 字幕存在但需登录态获取，当前 Cookie 过期/未登录（拉字幕前的登录校验未通过）。 */
    public static final String SUBTITLE_NEED_LOGIN = "NEED_LOGIN";

    /** 有效章节已写入 chapters.json，是强约束分析输入。 */
    public static final String CHAPTER_PRESENT = "PRESENT";
    /** player 成功且 view_points 为空：唯一允许无章节分析的状态。 */
    public static final String CHAPTER_ABSENT = "ABSENT";
    /** view_points 非空但整份校验失败：显式、可审计的降级（D-074），不属静默丢章。 */
    public static final String CHAPTER_INVALID = "INVALID";
}
