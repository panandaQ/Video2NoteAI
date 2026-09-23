package com.example.server.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 知识问答域的 Key 与摘要生成（runbook §7 / §10.2）。
 *
 * <p>与 {@link VideoImportKeys} 分开：导入按 {@code (userId, requestHash/sourceHash, importId,
 * mediaId)} 生成，问答按 {@code (userId, conversationId/turnId/requestId)} 生成，前缀不同、生命周期不同。
 * 问答 Redis 键只此一个生成位置，禁止在 Service 中手写字符串。
 */
public final class KnowledgeQuestionKeys {

    private static final String REQUEST_PREFIX = "knowledge:question:request:";
    private static final String HOT_CONVERSATION_PREFIX = "knowledge:conversation:hot:";

    private KnowledgeQuestionKeys() {
    }

    /**
     * requestId 幂等映射键（runbook §7）：命中后仍回 MySQL 校验，未命中或故障直接查唯一键。
     */
    public static String request(Long userId, String requestId) {
        return REQUEST_PREFIX + userId + ":" + requestId;
    }

    /** 幂等映射值：{@code conversationId:turnId}。 */
    public static String requestValue(Long conversationId, Long turnId) {
        return conversationId + ":" + turnId;
    }

    /** 用户私有热会话投影；userId 必须进入 Key，禁止仅按 conversationId 缓存。 */
    public static String hotConversation(Long userId, Long conversationId) {
        return HOT_CONVERSATION_PREFIX + userId + ":" + conversationId;
    }

    /**
     * 本轮允许媒体 ID 集合的 SHA-256（runbook §4.2 的 {@code scope_fingerprint}）。
     *
     * <p>先排序再拼接，保证同一集合任何顺序都得到同一指纹；它只用于审计与诊断，**不是权限凭证**——
     * 后续轮次仍会重新解析授权范围。
     */
    public static String scopeFingerprint(List<Long> mediaIds) {
        List<Long> sorted = new ArrayList<>(mediaIds);
        Collections.sort(sorted);
        String joined = sorted.stream().map(String::valueOf).collect(Collectors.joining(","));
        return VideoImportKeys.sha256(joined);
    }
}
