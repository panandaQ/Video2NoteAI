package com.example.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.example.server.dto.knowledge.KnowledgeConversationStatus;
import com.example.server.dto.knowledge.KnowledgeScopeType;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一条用户私有问答会话（D-081 / D-082）。
 *
 * <p>范围创建后不可变：{@code SINGLE_VIDEO} 会话绑定 {@code scope_media_id}，{@code LIBRARY}
 * 会话该列为空。{@code active_request_id} 是同一会话同一时间只处理一个新问题的执行权：
 * 非空表示有请求正在生成，只能由完成/失败事务或僵尸收敛释放，Redis/Redisson 不承担业务执行权。
 *
 * <p>{@code version} 在每次轮次终态时递增；本切片只把它作为变更标记回传给客户端，
 * 不做缓存版本协商（runbook §19 已推迟到 Q3）。
 */
@Data
@TableName("knowledge_conversations")
public class KnowledgeConversation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private KnowledgeScopeType scopeType;
    private Long scopeMediaId;

    /** 默认取首个问题的受控截断（120 字符），不额外调用模型。 */
    private String title;

    private KnowledgeConversationStatus status;

    private Long version;

    private Integer lastTurnNo;

    private String activeRequestId;
    private LocalDateTime activeRequestStartedAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
