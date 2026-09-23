package com.example.server.evaluation.dataset;

/** 工具已生成报告但门禁未通过；CLI 将其映射为非零退出码。 */
public final class DatasetGateFailedException extends RuntimeException {
    public DatasetGateFailedException(String code) {
        super(code);
    }
}
