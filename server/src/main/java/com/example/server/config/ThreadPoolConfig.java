package com.example.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
public class ThreadPoolConfig {

    @Bean("aiTaskExecutor")
    public ThreadPoolTaskExecutor aiTaskExecutor() {
        return executor("AI-Thread-", 4, 8, 100);
    }

    @Bean("asrExecutor")
    public ThreadPoolTaskExecutor asrExecutor() {
        return executor("ASR-Thread-", 4, 8, 50);
    }

    @Bean("ocrExecutor")
    public ThreadPoolTaskExecutor ocrExecutor() {
        int cores = Math.min(8, Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
        return executor("OCR-Thread-", cores, cores, 20);
    }

    @Bean("modelCallExecutor")
    public ThreadPoolTaskExecutor modelCallExecutor() {
        return executor("LLM-Thread-", 4, 8, 20);
    }

    /**
     * 媒体下载专用执行器：**把下载从 MQ 消费线程里隔离出来**。
     *
     * <p>为什么必须独立：一次下载可能持续几分钟（实测 26MB 视频几十秒，长视频更久）。
     * 下载跑在消费线程里时，慢下载会占住消费线程，把解析/其他消息一起堵在后面；同时没有并发上限，
     * 受理高峰期可能出现几十个下载同时跑，互相争抢带宽（匿名 480P 单连接实测约 465KB/s）。
     *
     * <p>并发上限取 {@code video.import.download-concurrency}（默认 4），队列容量取
     * {@code video.import.download-queue-capacity}（默认 200），拒绝策略为 Abort：
     * 队列打满时由调用方（消费者）记录并放弃本次投递，**媒体状态仍是 QUEUED**，
     * 由恢复扫描在下个周期重投——宁可晚一点，也不让下载把进程内存和带宽吃穿。
     *
     * <p>注意：这里只放"下载 + 上传对象"这类 I/O 等待型任务，不放 AI 调用（那是 ASR/OCR/LLM 三个池的事）。
     */
    @Bean("videoDownloadExecutor")
    public ThreadPoolTaskExecutor videoDownloadExecutor(VideoImportProperties properties) {
        ThreadPoolTaskExecutor executor = executor("VIDEO-DL-",
                properties.getDownloadConcurrency(), properties.getDownloadConcurrency(),
                properties.getDownloadQueueCapacity());
        // 下载是长任务：关闭时给足收尾时间，避免把已下载的临时文件丢成孤儿。
        executor.setAwaitTerminationSeconds(120);
        return executor;
    }

    private ThreadPoolTaskExecutor executor(String prefix, int coreSize, int maxSize, int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(prefix);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
