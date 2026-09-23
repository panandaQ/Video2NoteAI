package com.example.server.service;

import com.example.server.infrastructure.BilibiliMetadataClient.PlayerSubtitle;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 字幕轨道选择（计划 §4.3，D-073）：人工简中 → 其他人工中文 → AI 简中 → 其他 AI 中文。
 *
 * <p>否决条件只保留空 URL 与非中文轨道；{@code locked} 不作为否决条件——`bilibili-player-api.md`
 * 登录态样例中人工简中轨道就是 {@code is_lock=true}，按"过滤 locked"会得到 0 条可用字幕（D-073）。
 */
public final class SubtitleTrackSelector {

    private SubtitleTrackSelector() {
    }

    /** @return 最优轨道；没有可用中文轨道时返回 {@code null}（回退 ASR 由调用方决定） */
    public static PlayerSubtitle select(List<PlayerSubtitle> tracks) {
        if (tracks == null || tracks.isEmpty()) return null;
        return tracks.stream()
                .filter(SubtitleTrackSelector::isCandidate)
                .min(Comparator
                        .comparingInt(SubtitleTrackSelector::humanity)
                        .thenComparingInt(SubtitleTrackSelector::simplifiedRank))
                .orElse(null);
    }

    private static boolean isCandidate(PlayerSubtitle track) {
        return track != null
                && track.url() != null && !track.url().isBlank()
                && isChineseLan(track.lan());
    }

    static boolean isChineseLan(String lan) {
        if (lan == null) return false;
        String lower = lan.toLowerCase(Locale.ROOT);
        // 人工轨道 lan 形如 zh-Hans/zh-CN；AI 轨道形如 ai-zh（2026-09-20 真实样本）
        return lower.startsWith("zh") || lower.startsWith("ai-zh");
    }

    /** aiType=0 视为人工；缺失/其他值按 AI 对待（人工优先）。 */
    private static int humanity(PlayerSubtitle track) {
        return track.aiType() != null && track.aiType() == 0 ? 0 : 1;
    }

    /** 简中（zh-Hans/zh-CN）排在繁中/其他中文之前。 */
    private static int simplifiedRank(PlayerSubtitle track) {
        String lan = track.lan() == null ? "" : track.lan().toLowerCase(Locale.ROOT);
        return (lan.startsWith("zh-hans") || lan.startsWith("zh-cn")) ? 0 : 1;
    }
}
