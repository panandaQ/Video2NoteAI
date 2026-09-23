package com.example.server.dto.knowledge;

import com.example.server.dto.VideoRetrievalIntent;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 查询规划结果（D-111）：一次模型调用同时产出「消歧后的独立问法」「检索意图」与「错字纠正记录」。
 *
 * <p>两个查询字段都保留：{@code standaloneQuery} 以问句形态喂给回答器，{@code semanticQuery} 是
 * 面向向量检索的陈述式表达。检索同时保留原始表达（{@code originalTerms}）与纠正表达
 * （{@code correctedTerms}），纠正不能替代或丢失原查询（语音/手写输入可能含同音、形近错字）。
 *
 * @param standaloneQuery 补全指代与省略、并做高置信错字纠正后的独立问题
 * @param semanticQuery   适合向量检索的完整语句（使用纠正后的术语）
 * @param keywords        人物、概念、事件与专有名词
 * @param visualKeywords  可能出现在字幕/PPT/代码/画面文字里的词
 * @param originalTerms   用户问题中的原始术语（含可能的错字），原样保留
 * @param correctedTerms  上下文能明确判断的纠正术语；不能确定时为空
 * @param corrections     高置信纠正记录（raw → corrected → reason）
 */
public record QueryPlan(
        String standaloneQuery,
        String semanticQuery,
        List<String> keywords,
        List<String> visualKeywords,
        List<String> originalTerms,
        List<String> correctedTerms,
        List<Correction> corrections
) {

    /** 单条高置信纠正记录；{@code reason} 说明基于上下文的判断理由。 */
    public record Correction(String raw, String corrected, String reason) {
        public Correction {
            raw = raw == null ? "" : raw.trim();
            corrected = corrected == null ? "" : corrected.trim();
            reason = reason == null ? "" : reason.trim();
        }
    }

    public QueryPlan {
        standaloneQuery = standaloneQuery == null ? "" : standaloneQuery.trim();
        semanticQuery = semanticQuery == null ? "" : semanticQuery.trim();
        keywords = normalizeTerms(keywords);
        visualKeywords = normalizeTerms(visualKeywords);
        originalTerms = normalizeTerms(originalTerms);
        correctedTerms = normalizeTerms(correctedTerms);
        corrections = corrections == null ? List.of() : List.copyOf(corrections);
    }

    /** 无纠错信息的便利构造器：供不关心错字纠正的调用方与测试使用。 */
    public QueryPlan(String standaloneQuery, String semanticQuery,
                     List<String> keywords, List<String> visualKeywords) {
        this(standaloneQuery, semanticQuery, keywords, visualKeywords,
                List.of(), List.of(), List.of());
    }

    /** 规则兜底：模型不可用时退化为「用原问题检索、按标点切词」（与合并前的兜底一致）。 */
    public static QueryPlan fallback(String question) {
        String trimmed = question == null ? "" : question.trim();
        List<String> terms = splitTerms(trimmed);
        return new QueryPlan(trimmed, trimmed, terms, terms, terms, List.of(), List.of());
    }

    /**
     * 检索侧视图：关键词合并「原始词 + 纠正词 + 模型关键词」，确保原始表达与纠正表达都进关键词
     * 检索，不因错误纠正而丢失原查询；向量检索仍走单路 {@code semanticQuery}（纠正后的语义表达）。
     */
    public VideoRetrievalIntent toRetrievalIntent() {
        List<String> mergedKeywords = Stream.of(originalTerms, correctedTerms, keywords)
                .flatMap(List::stream)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(term -> !term.isBlank())
                .distinct()
                .limit(16)
                .toList();
        return new VideoRetrievalIntent(semanticQuery, mergedKeywords, visualKeywords);
    }

    /** 模型产出不可用（缺 semanticQuery）时调用方应改用 {@link #fallback}。 */
    public boolean usable() {
        return !semanticQuery.isBlank();
    }

    private static List<String> normalizeTerms(List<String> terms) {
        if (terms == null) return List.of();
        return terms.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(term -> !term.isBlank())
                .distinct()
                .limit(16)
                .toList();
    }

    private static List<String> splitTerms(String query) {
        if (query.isBlank()) return List.of();
        List<String> terms = java.util.Arrays.stream(query.trim().split("[\\s，。！？、,.;:：；!?]+"))
                .map(String::trim)
                .filter(term -> term.length() >= 2)
                .distinct()
                .limit(8)
                .toList();
        return terms.isEmpty() ? List.of(query.trim()) : terms;
    }
}
