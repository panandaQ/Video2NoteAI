package com.example.server.service;

import com.example.server.config.AgentBudgetProperties;
import com.example.server.config.AgentChapterProperties;
import com.example.server.config.ChatModelProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 判死阈值契约（D-076 修订）：max(配置, 2×单次模型超时+60s, 视频时长/2)。
 */
class AgentBudgetServiceThresholdTest {

    private final AgentBudgetService service = new AgentBudgetService(
            new AgentBudgetProperties(), new AgentChapterProperties(), chatProperties());

    private static ChatModelProperties chatProperties() {
        ChatModelProperties properties = new ChatModelProperties();
        properties.setTimeoutSeconds(300L);
        return properties;
    }

    @Test
    void thresholdIsMaxOfConfigStageAndHalfDuration() {
        // 默认配置 1800s；2×300+60=660s；时长 1800s → 900s。三者取最大 = 1800s
        assertEquals(1800L, service.recoveryStaleThresholdSeconds(1_800_000L, 1800L));
        // 3 小时视频：时长/2 = 5400s 起主导
        assertEquals(5400L, service.recoveryStaleThresholdSeconds(3 * 3600_000L, 1800L));
        // 短视频 + 小配置：阶段项 660s 起主导
        assertEquals(660L, service.recoveryStaleThresholdSeconds(60_000L, 120L));
        // 时长未知：配置起主导
        assertEquals(1800L, service.recoveryStaleThresholdSeconds(null, 1800L));
    }
}
