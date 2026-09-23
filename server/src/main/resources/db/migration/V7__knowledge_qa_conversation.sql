-- 模块二 · 单视频连续追问数据地基（D-081 / D-083 / D-084，runbook §4）
--
-- 背景：现有 /analysis/follow-up 是一次性追问，不持久化会话、轮次、回答与证据；问答数据
-- 不得写入 agent_checkpoints（那是内容级 Context/Chunk 与结构化分析任务的真源，D-081）。
-- 本迁移新增三张知识问答事实表，MySQL 从会话创建起就是唯一真源：
--
--   * knowledge_conversations：会话归属、固定范围、执行权（active_request_id）与版本；
--   * knowledge_turns：轮次（问题 + 回答 + 生命周期 + 评测诊断字段）；
--   * knowledge_turn_evidence：只保存本轮实际引用且校验通过的证据（不保存全部候选）。
--
-- 关键设计：
--   * 会话执行权是 MySQL CAS：active_request_id 为 NULL 才能取得，执行权与 turnNo 在同一条
--     条件更新里推进（runbook §2.2），Redis/Redisson 不承担业务执行权（D-084）。
--   * 幂等真源是 uk_knowledge_turn_request (user_id, request_id)：重复 requestId 永远定位到
--     同一轮次，其他用户即使猜到 UUID 也不能探测（D-084）。
--   * 顺序真源是 uk_knowledge_turn_order (conversation_id, turn_no)：事务回滚时连同会话
--     last_turn_no 一起回滚，不留下 turnNo 空洞。
--   * 不建外键：与 V1～V6 一致，删除与一致性由应用层事务显式处理。
--   * scope_type 保留 LIBRARY 枚举与可空 scope_media_id 是稳定模型边界，不代表跨视频已实现
--     （当前切片对 LIBRARY 稳定拒绝）。
CREATE TABLE knowledge_conversations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    scope_type VARCHAR(32) NOT NULL,
    scope_media_id BIGINT NULL,
    title VARCHAR(120) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    last_turn_no INT NOT NULL DEFAULT 0,
    active_request_id VARCHAR(36) NULL,
    active_request_started_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_knowledge_conversation_user_time (user_id, updated_at, id),
    KEY idx_knowledge_conversation_scope (user_id, scope_type, scope_media_id),
    CONSTRAINT chk_knowledge_conversation_scope CHECK (
        (scope_type = 'SINGLE_VIDEO' AND scope_media_id IS NOT NULL)
        OR (scope_type = 'LIBRARY' AND scope_media_id IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- user_id 是为用户维度幂等查询和隔离而有意冗余：按 requestId 查询必须同时带 userId。
-- rewritten_query / retrieval_mode / retrieved_count / cited_count / duration_ms 是评测诊断字段，
-- 链路跑通后自然落库，评测指标无需额外埋点。
CREATE TABLE knowledge_turns (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    conversation_id BIGINT NOT NULL,
    turn_no INT NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    trace_id VARCHAR(36) NOT NULL,
    question VARCHAR(1000) NOT NULL,
    rewritten_query VARCHAR(1000) NULL,
    status VARCHAR(16) NOT NULL,
    answerable TINYINT(1) NULL,
    answer LONGTEXT NULL,
    scope_media_count INT NOT NULL DEFAULT 1,
    scope_fingerprint CHAR(64) NOT NULL,
    retrieval_mode VARCHAR(32) NULL,
    retrieved_count INT NULL,
    cited_count INT NULL,
    duration_ms BIGINT NULL,
    error_code VARCHAR(64) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    completed_at TIMESTAMP(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_turn_request (user_id, request_id),
    UNIQUE KEY uk_knowledge_turn_order (conversation_id, turn_no),
    KEY idx_knowledge_turn_conversation (conversation_id, id),
    KEY idx_knowledge_turn_trace (trace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 证据行不保存向量，也不保存其他用户或共享资产的内部 ID；title_snapshot 回答当时的标题快照。
CREATE TABLE knowledge_turn_evidence (
    id BIGINT NOT NULL AUTO_INCREMENT,
    turn_id BIGINT NOT NULL,
    evidence_rank INT NOT NULL,
    media_id BIGINT NOT NULL,
    title_snapshot VARCHAR(255) NOT NULL,
    start_ms BIGINT NOT NULL,
    end_ms BIGINT NOT NULL,
    source VARCHAR(32) NOT NULL,
    snippet VARCHAR(1000) NOT NULL,
    score DOUBLE NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_evidence_rank (turn_id, evidence_rank),
    KEY idx_knowledge_evidence_turn (turn_id),
    KEY idx_knowledge_evidence_media (media_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
