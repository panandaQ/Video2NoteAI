package com.example.server.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 在线导入的 Redis Key 与摘要生成。
 *
 * <p>规范要求同一类 Key 只能有一个生成位置，禁止在 Service 中手写字符串。本类同时负责导入域的全部
 * 摘要计算：{@code requestHash}（规范化 URL）、{@code activeRequestKey}（用户 + 请求）和
 * {@code sourceHash}（可播放单元 sourceKey），后者的目的是不让 URL 特殊字符扩散进 Key。
 *
 * <p>与 {@link AnalysisTaskKeys} 分开：AI 任务身份按 {@code (contentHash, goalDigest)} 生成，
 * 导入按 {@code (userId, requestHash/sourceHash, importId, mediaId)} 生成，两者前缀不同、生命周期不同。
 */
public final class VideoImportKeys {

    private static final String REQUEST_PREFIX = "video:import:request:";
    private static final String LOCK_JOB_PREFIX = "lock:video:import-job:";
    private static final String LOCK_UNIT_PREFIX = "lock:video:import:";
    private static final String LOCK_CONTENT_PREFIX = "lock:video:content:";
    private static final String ATTEMPTS_JOB_PREFIX = "video:import:attempts:job:";
    private static final String ATTEMPTS_MEDIA_PREFIX = "video:import:attempts:media:";
    private static final String LIMIT_SUBMISSION_USER_PREFIX = "limit:video:import:submit:user:";
    private static final String LIMIT_SUBMISSION_IP_PREFIX = "limit:video:import:submit:ip:";
    private static final String LIMIT_USER_PREFIX = "limit:video:import:user:";
    private static final String LIMIT_IP_PREFIX = "limit:video:import:ip:";
    private static final String LIMIT_GLOBAL = "limit:video:import:global";
    /** 共享内容字节就绪的广播通道（D-068）。 */
    private static final String CONTENT_READY_CHANNEL = "dovideo:content-ready";

    private VideoImportKeys() {
    }

    /** 规范化 URL 的 SHA-256，用于合并完全相同的提交。 */
    public static String requestHash(String normalizedUrl) {
        return sha256(normalizedUrl);
    }

    /**
     * 父任务活跃键：非终态时写入 {@code video_import_jobs.active_request_key} 并唯一约束。
     * 让并发提交在数据库层收敛，Redis 只能加速、不能替代。
     */
    public static String activeRequestKey(Long userId, String requestHash) {
        return sha256(userId + ":" + requestHash);
    }

    /** 可播放单元身份摘要，用于热点去重与 Unit 级锁。 */
    public static String sourceHash(String sourceKey) {
        return sha256(sourceKey);
    }

    public static String request(Long userId, String requestHash) {
        return REQUEST_PREFIX + userId + ":" + requestHash;
    }

    public static String lockJob(Long importId) {
        return LOCK_JOB_PREFIX + importId;
    }

    public static String lockUnit(Long userId, String sourceHash) {
        return LOCK_UNIT_PREFIX + userId + ":" + sourceHash;
    }

    /**
     * 共享内容的下载权锁（D-068）：按内容资产 ID 分片，跨用户唯一。
     *
     * <p>Unit 锁按 {@code userId} 分片，只能防"同一个用户重复下载"；同一份内容被两个用户同时导入时
     * 需要一把与用户无关的锁，否则两边都会下载（AC-07）。
     */
    public static String lockContent(Long assetId) {
        return LOCK_CONTENT_PREFIX + assetId;
    }

    /**
     * "共享字节已就绪"的广播通道（D-068）。
     *
     * <p>它只用来**唤醒等待者**：抢内容锁失败的用户原本要等恢复扫描（最长约一个 stale 窗口 + 一个扫描间隔）
     * 才会发现自己要的字节已经下好。通知丢了一点也不影响正确性——等待者仍在数据库里，恢复扫描照样收敛。
     */
    public static String contentReadyChannel() {
        return CONTENT_READY_CHANNEL;
    }

    public static String attemptsJob(Long importId) {
        return ATTEMPTS_JOB_PREFIX + importId;
    }

    public static String attemptsMedia(Long mediaId) {
        return ATTEMPTS_MEDIA_PREFIX + mediaId;
    }

    /** 每次有效提交的用户级限流键；在读取请求缓存或 MySQL 之前检查。 */
    public static String userSubmissionLimit(Long userId) {
        return LIMIT_SUBMISSION_USER_PREFIX + userId;
    }

    /** 每次有效提交的 IP 级限流键；与新建任务配额使用不同的桶。 */
    public static String ipSubmissionLimit(String clientIp) {
        return LIMIT_SUBMISSION_IP_PREFIX + clientIp;
    }

    /** 单用户“新建导入任务”限流键；重复提交不消耗配额。 */
    public static String userNewJobLimit(Long userId) {
        return LIMIT_USER_PREFIX + userId;
    }

    /** 单客户端 IP“新建导入任务”限流键；用于兜住"多开账号绕过用户级限额"的放大。 */
    public static String ipNewJobLimit(String clientIp) {
        return LIMIT_IP_PREFIX + clientIp;
    }

    /** 全局“新建导入任务”限流键。 */
    public static String globalNewJobLimit() {
        return LIMIT_GLOBAL;
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
