package com.example.server.dto;

/**
 * 容器展示快照：只表达组织关系，不参与媒体唯一键。
 */
public record ContainerSummary(
        String containerId,
        String title
) {
}
