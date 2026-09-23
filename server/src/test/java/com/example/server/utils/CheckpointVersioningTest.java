package com.example.server.utils;

import com.example.server.dto.AnalysisMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * profileVersion 参与任务摘要的契约（计划 §6.2 / §7.1，D-077）：
 * V2 结果、活跃键、Checkpoint 必须与 V1 互不串键，历史 V1 摘要逐字节不变。
 */
class CheckpointVersioningTest {

    private static final String GOAL = "提取视频核心内容，生成结构化视频笔记";

    @Test
    void legacyDigestIsByteStableAcrossVersionedOverload() {
        assertEquals(AnalysisTaskKeys.goalDigest(GOAL, AnalysisMode.GENERAL),
                AnalysisTaskKeys.goalDigest(GOAL, AnalysisMode.GENERAL, "VIDEO_NOTE_V1"),
                "V1 显式版本与历史无版本摘要必须相同，保证既有 Checkpoint 可读");
    }

    @Test
    void missingOrBlankVersionFallsBackToLegacyDigest() {
        String legacy = AnalysisTaskKeys.goalDigest(GOAL, AnalysisMode.GENERAL);
        assertEquals(legacy, AnalysisTaskKeys.goalDigest(GOAL, AnalysisMode.GENERAL, null));
        assertEquals(legacy, AnalysisTaskKeys.goalDigest(GOAL, AnalysisMode.GENERAL, ""));
    }

    @Test
    void v2DigestDiffersFromLegacy() {
        assertNotEquals(AnalysisTaskKeys.goalDigest(GOAL, AnalysisMode.GENERAL),
                AnalysisTaskKeys.goalDigest(GOAL, AnalysisMode.GENERAL, "VIDEO_NOTE_V2"),
                "V2 不得复用 V1 摘要");
        assertNotEquals(AnalysisTaskKeys.goalDigest(GOAL, AnalysisMode.GENERAL, "VIDEO_NOTE_V2"),
                AnalysisTaskKeys.goalDigest(GOAL, AnalysisMode.GENERAL, "VIDEO_NOTE_V3"),
                "不同版本互不串键");
    }

    @Test
    void versionedDigestIsModeAware() {
        assertNotEquals(AnalysisTaskKeys.goalDigest(GOAL, AnalysisMode.LEARNING, "VIDEO_NOTE_V2"),
                AnalysisTaskKeys.goalDigest(GOAL, AnalysisMode.REVIEW, "VIDEO_NOTE_V2"),
                "同一目标不同模式在不同版本下仍互不串键");
        assertEquals(AnalysisTaskKeys.goalDigest(GOAL, AnalysisMode.LEARNING, "VIDEO_NOTE_V2"),
                AnalysisTaskKeys.goalDigest(GOAL, AnalysisMode.LEARNING, "VIDEO_NOTE_V2"),
                "摘要确定可复现");
    }
}
