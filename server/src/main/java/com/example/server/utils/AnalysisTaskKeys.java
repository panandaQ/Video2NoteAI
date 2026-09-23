package com.example.server.utils;

import com.example.server.dto.AnalysisMode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

public final class AnalysisTaskKeys {

    private static final Pattern MD5_PATTERN = Pattern.compile("[a-fA-F0-9]{32}");

    /**
     * 历史默认笔记处理版本的标识。该版本不进入摘要，保证既有 V1 checkpoint / 去重 / 状态键
     * 逐字节不变；升级 V2 后，旧媒体上的历史结果仍然可读（D-077）。
     */
    public static final String LEGACY_NOTE_VERSION = "VIDEO_NOTE_V1";

    private AnalysisTaskKeys() {
    }

    public static String normalizeContentHash(Long mediaId, String contentHash) {
        if (contentHash != null && MD5_PATTERN.matcher(contentHash).matches()) {
            return contentHash.toLowerCase(Locale.ROOT);
        }
        return "media-" + mediaId;
    }

    public static String goalDigest(String goal) {
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("analysis goal is required");
        }
        return sha256(goal.trim());
    }

    /**
     * 模式感知的目标摘要:任务身份 = (内容, 目标, 模式)。
     *
     * <p>GENERAL 直接委托 {@link #goalDigest(String)},摘要逐字节不变,与引入模式前的既有缓存/键
     * 完全兼容;其余模式把模式名并入摘要,使"同一目标文本、不同模式"落在不同的 checkpoint / 去重 /
     * 状态键上,互不污染。null 模式按 GENERAL 处理,保证历史消息与遗漏调用安全降级。
     */
    public static String goalDigest(String goal, AnalysisMode mode) {
        if (mode == null || mode == AnalysisMode.GENERAL) {
            return goalDigest(goal);
        }
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("analysis goal is required");
        }
        // U+241F 是不可见的单元分隔符,避免"模式名+目标"与某个真实目标文本发生摘要碰撞。
        return sha256(mode.name() + '␟' + goal.trim());
    }

    /**
     * 版本感知的目标摘要：任务身份 = (内容, 目标, 模式, 处理版本)。
     *
     * <p>{@code profileVersion} 为空或为 {@link #LEGACY_NOTE_VERSION} 时委托旧公式，
     * 摘要与引入版本前的既有键逐字节相同（历史消息缺失版本字段即按此处理，D-077）；
     * 其余版本把版本号并入摘要，保证 V2 结果、活跃键、Checkpoint 与 V1 互不串键。
     */
    public static String goalDigest(String goal, AnalysisMode mode, String profileVersion) {
        if (profileVersion == null || profileVersion.isBlank()
                || LEGACY_NOTE_VERSION.equals(profileVersion)) {
            return goalDigest(goal, mode);
        }
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("analysis goal is required");
        }
        String modePart = (mode == null || mode == AnalysisMode.GENERAL)
                ? "" : mode.name() + '␟';
        // U+241F 是不可见的单元分隔符，避免"版本号+模式+目标"与真实目标文本发生摘要碰撞。
        return sha256(profileVersion + '␟' + modePart + goal.trim());
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    public static String active(String contentHash, String goalDigest) {
        return "analysis:active:" + contentHash + ":" + goalDigest;
    }

    public static String lock(String contentHash, String goalDigest) {
        return "lock:analysis:" + contentHash + ":" + goalDigest;
    }

    public static String completed(String contentScope, String goalDigest) {
        return "analysis:completed:" + contentScope + ":" + goalDigest;
    }

    public static String attempts(String contentScope, String goalDigest) {
        return "analysis:attempts:" + contentScope + ":" + goalDigest;
    }

    /**
     * 后台默认笔记的单用户投递速率键（契约 §10）。
     *
     * <p>与交互式 `limit:ai:user` 分开：自动笔记是系统行为，不能占用用户的交互配额，也不能因为
     * "用户刚刚聊了几句" 就被拒绝——多单元合集正是靠这条独立速率才能在分钟级内全部投递完成。
     */
    public static String noteUserLimit(Long userId) {
        return "limit:video:note:user:" + userId;
    }

    /** 后台默认笔记的全局投递速率键（契约 §10）。 */
    public static String noteGlobalLimit() {
        return "limit:video:note:global";
    }

    /**
     * 内容级预处理的归属键（V2）：记录哪个 mediaId 已经产出过该内容的 V2 VideoContext。
     * ASR/OCR 只取决于视频内容本身，与用户目标无关，因此按 contentHash 而非 goal 复用。
     * 版本后缀保证 V1 时代的归属指针不会被 V2 流程误用（计划 §6.2）。
     */
    public static String contextOwner(String contentHash) {
        return "analysis:context-owner:v2:" + contentHash;
    }

    /**
     * 内容级预处理锁（V2）：同一视频被不同目标同时提交时，只允许一个消费者真正跑 ASR/OCR，
     * 其余等待后直接复用，避免重复烧算力与第三方额度。
     */
    public static String contextLock(String contentHash) {
        return "lock:analysis-context:v2:" + contentHash;
    }
}
