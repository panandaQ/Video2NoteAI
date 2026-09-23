-- 模块二 · 单视频问答「视频优先、模型知识兜底」三模式（runbook §6.2 三模式改造）
--
-- 背景：旧的 knowledge_turns.answerable 是单一布尔，把「是否有视频证据」与「是否能回答」混在一起，
-- 导致视频证据不足时机械拒答。本迁移把来源维度解耦为两个正交字段：
--   * answer_mode：来源模式（VIDEO_GROUNDED / HYBRID / MODEL_KNOWLEDGE）；
--   * video_evidence_found：本轮是否检索到校验通过的视频引用。
--
-- answerable 已确认移除：由 answer_mode 派生「可回答 + 来源」语义，不再有拒答终态。
-- 检索基础设施故障仍是独立的 FAILED/RETRIEVAL_UNAVAILABLE，与「无视频证据」严格区分。
ALTER TABLE knowledge_turns
    DROP COLUMN answerable,
    ADD COLUMN answer_mode VARCHAR(32) NULL,
    ADD COLUMN video_evidence_found TINYINT(1) NULL;
