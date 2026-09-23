package com.example.server.config;

import com.example.server.service.TaskEventService;
import com.example.server.service.ingest.ContentAssetReadyListener;
import com.example.server.utils.VideoImportKeys;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis Pub/Sub 订阅装配。
 *
 * <p>两条通道共用一个容器：{@code dovideo:task-events} 承载分析/导入的 SSE 事件（面向客户端），
 * {@code dovideo:content-ready} 承载"共享视频字节已就绪"（D-068，面向服务端自己的等待者）。
 * 分开是因为语义与消费者完全不同——前者是通知用户，后者是唤醒其它导入任务；混在一条通道上
 * 会让每一条用户事件都触发一次"查等待者"的数据库查询。
 */
@Configuration
public class TaskEventRedisConfig {

    @Bean
    public RedisMessageListenerContainer taskEventListenerContainer(
            RedisConnectionFactory connectionFactory,
            TaskEventService taskEventService,
            ContentAssetReadyListener contentAssetReadyListener) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(taskEventService, new ChannelTopic(TaskEventService.REDIS_CHANNEL));
        container.addMessageListener(contentAssetReadyListener,
                new ChannelTopic(VideoImportKeys.contentReadyChannel()));
        return container;
    }
}
