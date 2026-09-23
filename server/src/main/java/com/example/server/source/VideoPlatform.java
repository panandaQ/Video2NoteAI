package com.example.server.source;

/**
 * 视频来源平台。
 *
 * <p>平台命名空间同时进入媒体唯一身份和 {@code sourceKey}，保证不同平台的同 ID 内容不会互相覆盖。
 * 新增生产平台时只新增 Adapter 与常量，不修改导入、媒体处理、AI 和检索主链路。
 *
 * <p>{@link #TEST} 仅供测试与压测使用的 Fake Adapter，不代表已接入第二个生产平台。
 */
public enum VideoPlatform {

    BILIBILI,
    TEST
}
