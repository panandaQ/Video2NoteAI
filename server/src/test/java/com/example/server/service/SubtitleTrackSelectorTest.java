package com.example.server.service;

import com.example.server.infrastructure.BilibiliMetadataClient.PlayerSubtitle;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 字幕轨道选择契约（计划 §4.3，D-073）：人工简中优先；locked 不是否决条件。
 */
class SubtitleTrackSelectorTest {

    private static PlayerSubtitle track(String lan, String url, Integer aiType) {
        return new PlayerSubtitle(lan, lan, url, aiType, 0, null);
    }

    @Test
    void lockedHumanTrackBeatsUnlockedAiTrack() {
        // bilibili-player-api.md 真实样例：人工轨道 is_lock=true；按"过滤 locked"会全灭（D-073）
        PlayerSubtitle lockedHuman = new PlayerSubtitle(
                "zh-Hans", "中文（简体）", "https://aisubtitle.hdslb.com/a.json", 0, 0, true);
        PlayerSubtitle unlockedAi = new PlayerSubtitle(
                "zh-Hans", "中文（简体）", "https://aisubtitle.hdslb.com/b.json", 1, 0, false);
        assertEquals(lockedHuman, SubtitleTrackSelector.select(List.of(unlockedAi, lockedHuman)));
    }

    @Test
    void humanSimplifiedPreferredOverTraditionalAndOtherLanguages() {
        PlayerSubtitle humanTraditional = track("zh-Hant", "https://c/x.json", 0);
        PlayerSubtitle aiSimplified = track("zh-Hans", "https://c/y.json", 1);
        PlayerSubtitle humanSimplified = track("zh-Hans", "https://c/z.json", 0);
        assertEquals(humanSimplified,
                SubtitleTrackSelector.select(List.of(humanTraditional, aiSimplified, humanSimplified)));
    }

    @Test
    void nonChineseAndEmptyUrlFilteredOut() {
        assertNull(SubtitleTrackSelector.select(List.of(
                track("en-US", "https://c/e.json", 0),
                track("ja-JP", "https://c/j.json", 0))));
        assertNull(SubtitleTrackSelector.select(List.of(
                track("zh-Hans", "", 0),
                track("zh-Hans", null, 0))));
    }

    @Test
    void nullOrEmptyTracksReturnNull() {
        assertNull(SubtitleTrackSelector.select(null));
        assertNull(SubtitleTrackSelector.select(List.of()));
    }

    @Test
    void chineseLanDetection() {
        PlayerSubtitle traditional = track("zh-Hant", "https://c/a.json", 0);
        assertEquals(traditional, SubtitleTrackSelector.select(List.of(traditional)));
    }

    /** 真实样本（2026-09-20）：AI 字幕轨道的 lan 是 "ai-zh" 而非 "zh-*"。 */
    @Test
    void aiChineseTrackIsSelectedOverOtherLanguages() {
        PlayerSubtitle aiZh = track("ai-zh", "https://c/ai-zh.json", 1);
        PlayerSubtitle aiEn = track("ai-en", "https://c/ai-en.json", 1);
        PlayerSubtitle aiJa = track("ai-ja", "https://c/ai-ja.json", 1);
        assertEquals(aiZh, SubtitleTrackSelector.select(List.of(aiEn, aiJa, aiZh)));
    }

    /** 人工简中仍优先于 AI 简中（aiType=0 优先）。 */
    @Test
    void humanChineseStillBeatsAiChinese() {
        PlayerSubtitle aiZh = track("ai-zh", "https://c/ai-zh.json", 1);
        PlayerSubtitle human = track("zh-Hans", "https://c/zh.json", 0);
        assertEquals(human, SubtitleTrackSelector.select(List.of(aiZh, human)));
    }
}
