package com.example.server.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定时任务开关。
 *
 * <p>当前只有导入链路的恢复扫描（{@code ImportRecoveryScanner}）依赖调度：它必须能在没有用户请求、
 * 没有 MQ 消息的情况下自己跑起来，否则中间态只能靠人工 SQL 收敛。
 *
 * <p>调度线程只在扫描里做状态判断与消息重投，不承担下载或分析等长任务，因此保持 Spring 默认的
 * 单线程调度器即可；真正的并发由 MQ 消费者线程池承担。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
