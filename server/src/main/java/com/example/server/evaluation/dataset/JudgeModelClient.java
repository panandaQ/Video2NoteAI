package com.example.server.evaluation.dataset;

/** 独立 Judge 模型端口。实现必须与生成模型和生产回答模型隔离。 */
@FunctionalInterface
public interface JudgeModelClient {
    String judge(JudgeRequest request);
}
