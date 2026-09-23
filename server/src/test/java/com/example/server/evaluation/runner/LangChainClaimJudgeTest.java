package com.example.server.evaluation.runner;

import com.example.server.dto.VideoEvidenceHit;
import com.example.server.dto.knowledge.AnswerMode;
import com.example.server.dto.knowledge.AnswerOutcome;
import com.example.server.evaluation.dataset.JudgeChatClient;
import com.example.server.evaluation.dataset.JudgeModelConfig;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LangChainClaimJudgeTest {

    private final JudgeChatClient client = mock(JudgeChatClient.class);
    private final JudgeModelConfig config = new JudgeModelConfig(
            "key", "https://judge.invalid", "judge-model", Duration.ofSeconds(10));
    private final LangChainClaimJudge judge = new LangChainClaimJudge(
            config, client, JsonMapper.builder().build());

    @Test
    void calculatesUnsupportedRateFromClaimLabels() {
        when(client.complete(anyString(), anyString())).thenReturn("""
                {"claims":[
                  {"claim":"支持的声明","supported":true},
                  {"claim":"无依据的声明","supported":false}
                ]}
                """);
        AnswerOutcome outcome = new AnswerOutcome(
                "独立问题", "HYBRID", 1, List.of(), AnswerMode.VIDEO_GROUNDED, true, "两个事实", 10, 0, 0);
        RetrievalProbe.ProbeResult probe = new RetrievalProbe.ProbeResult(List.of(
                new VideoEvidenceHit(10, 20, "CC+OCR", "证据", "转录证据", List.of("画面证据"))));

        ClaimJudge.Judgement result = judge.judge(new ClaimJudge.JudgeRequest(
                "问题", "独立问题", outcome, probe));

        assertEquals(0.5, result.unsupportedClaimRate());
        assertEquals(2, result.totalClaims());
        assertEquals(1, result.unsupportedClaims());
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(client).complete(anyString(), prompt.capture());
        assertTrue(prompt.getValue().contains("转录证据"));
        assertTrue(prompt.getValue().contains("画面证据"));
    }

    @Test
    void failsClosedWhenRuntimeJudgeMatchesGenerator() {
        EvaluationDataset.Provenance provenance = new EvaluationDataset.Provenance(
                "judge-model", "gen-v1", "judge-model", "judge-v1",
                "production-model", null, null);

        assertThrows(IllegalArgumentException.class,
                () -> judge.validateIsolation(provenance));
    }
}
