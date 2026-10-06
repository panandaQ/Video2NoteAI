package com.example.server.service;

import com.example.server.dto.SegmentRetrievalDocument;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Bm25SegmentIndexTest {
    @Test
    void ranksExactSegmentAndUsesStableRefTieBreak() {
        var index = new Bm25SegmentIndex(List.of(
                document("7:0:60000", "向量数据库 检索"),
                document("7:60000:120000", "模型训练")));

        var result = index.searchWithScores("向量数据库", 5);

        assertEquals("7:0:60000", result.get(0).document().segmentRef());
        assertEquals(1, result.get(0).rank());
    }

    @Test
    void expandsHighConfidenceTimeAliasAndKeepsExactSignals() {
        var index = new Bm25SegmentIndex(List.of(
                document("7:0:60000", "这是一个读书故事，后来谈到别的书"),
                document("7:60000:120000", "张建南借了罪与罚，要我一个晚上读到天亮")));

        var result = index.searchWithScores(
                "为了读一本书通宵熬夜的故事是哪本书",
                2,
                List.of("通宵熬夜", "罪与罚", "张建南"),
                0.35D,
                0.55D);

        assertEquals("7:60000:120000", result.get(0).document().segmentRef());
        assertTrue(Bm25SegmentIndex.expandQueryTerms("通宵熬夜").contains("天亮"));
    }

    private SegmentRetrievalDocument document(String ref, String text) {
        String[] parts = ref.split(":");
        return new SegmentRetrievalDocument(7L, ref, null, null,
                Long.parseLong(parts[1]), Long.parseLong(parts[2]), text, "CC", List.of(), List.of(),
                "VIDEO_CONTEXT_V2", "RAG_MINUTE_V1", "BM25_SEGMENT_V1");
    }
}
