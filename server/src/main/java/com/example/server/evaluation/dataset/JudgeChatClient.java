package com.example.server.evaluation.dataset;

/** 独立 Judge 的窄传输端口；数据集门禁与回答声明判分共享同一模型配置。 */
@FunctionalInterface
public interface JudgeChatClient {
    String complete(String systemPolicy, String prompt);
}
