package com.example.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 一轮问答实际引用且校验通过的证据（runbook §4.3）。
 *
 * <p>只保存本轮实际引用的证据，不保存全部候选；{@code evidenceRank} 是服务端分配的
 * {@code E1..En} 编号顺序（{@code uk_knowledge_evidence_rank} 保证一轮内不重复）。
 * {@code titleSnapshot} 是回答当时的标题快照，不保存其他用户或共享资产的内部 ID。
 */
@Data
@TableName("knowledge_turn_evidence")
public class KnowledgeTurnEvidence {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long turnId;

    /** 本轮引用顺序，从 1 开始（E1...En）。 */
    private Integer evidenceRank;

    /** 当前用户条目 ID；跨视频时每条证据各自携带。 */
    private Long mediaId;
    private String titleSnapshot;

    private Long startMs;
    private Long endMs;

    /** {@code CC / ASR / OCR} 或组合。 */
    private String source;

    /** 有界证据片段（≤1000 字符）。 */
    private String snippet;

    /** 检索分数，仅用于诊断。 */
    private Double score;
}
