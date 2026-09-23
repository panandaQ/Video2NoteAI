package com.example.server.config;

import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * View 章节批处理配置（计划 §4.1）：章节数 ≤ batch-size 时一批执行，否则按原顺序切成多个
 * batch，保存章级草稿 Checkpoint，全部完成后做一次全局聚合。
 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "agent.chapter")
public class AgentChapterProperties {

    @Min(1)
    private int batchSize = 8;
}
