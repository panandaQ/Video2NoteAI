package com.example.server.service;

import com.example.server.config.NoteBatchProperties;
import com.example.server.dto.NoteInputBatch;
import com.example.server.dto.SegmentPart;
import com.example.server.dto.VideoChapter;
import com.example.server.dto.VideoContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 把完整 VideoContext 规划成默认笔记的动态输入批次。
 *
 * <p>有章节时先按平台章节顺序分组，章节之间绝不装入同一个批次；无章节时按时间排序。
 * 两种路径都使用同一个 Token 装箱器。普通溢出只封存当前批次，溢出的 SegmentPart 会在下一批
 * 重新尝试；单个 Segment 超过硬上限时先完整拆成连续 Part，不允许截断或丢失。
 */
@Service
public class NoteBatchBuilder {

    private final NoteBatchProperties properties;

    public NoteBatchBuilder(NoteBatchProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
        validateProperties();
    }

    /** 规划一份完整 VideoContext，sequenceNo 从 1 开始且跨章节单调递增。 */
    public List<NoteInputBatch> build(Long mediaId, String profileVersion, VideoContext context) {
        Objects.requireNonNull(mediaId, "mediaId");
        if (profileVersion == null || profileVersion.isBlank()) {
            throw new IllegalArgumentException("profileVersion is required");
        }
        Objects.requireNonNull(context, "context");
        if (context.segments().isEmpty()) return List.of();

        List<SegmentGroup> groups = groupSegments(context);
        List<NoteInputBatch> batches = new ArrayList<>();
        int sequenceNo = 1;
        for (SegmentGroup group : groups) {
            List<SegmentPart> current = new ArrayList<>();
            long currentTokens = 0;
            for (VideoContext.VideoSegment segment : group.segments()) {
                for (SegmentPart part : split(segment)) {
                    long partTokens = estimateTokens(part.inputChars());
                    // split() guarantees this invariant. Keep the check here so a future tokenizer
                    // change fails loudly instead of silently creating an over-sized request.
                    if (partTokens > properties.effectiveHardInputTokens()) {
                        throw new IllegalStateException("segment part exceeds hard input token budget: "
                                + part.segmentId() + " partNo=" + part.partNo());
                    }
                    if (!current.isEmpty()
                            && currentTokens + partTokens > properties.getBatchSoftInputTokens()) {
                        batches.add(toBatch(mediaId, profileVersion, group, sequenceNo++, current));
                        if (batches.size() > properties.getMaxBatches()) {
                            throw tooManyBatches(mediaId);
                        }
                        current = new ArrayList<>();
                        currentTokens = 0;
                    }
                    current.add(part);
                    currentTokens += partTokens;
                }
            }
            if (!current.isEmpty()) {
                batches.add(toBatch(mediaId, profileVersion, group, sequenceNo++, current));
                if (batches.size() > properties.getMaxBatches()) {
                    throw tooManyBatches(mediaId);
                }
            }
        }
        return List.copyOf(batches);
    }

    /** 别名便于 Planner 代码表达“规划批次”，不创建第二套实现。 */
    public List<NoteInputBatch> plan(Long mediaId, String profileVersion, VideoContext context) {
        return build(mediaId, profileVersion, context);
    }

    private List<SegmentGroup> groupSegments(VideoContext context) {
        List<VideoContext.VideoSegment> sorted = context.segments().stream()
                .sorted(Comparator.comparingLong(VideoContext.VideoSegment::startMs)
                        .thenComparingLong(VideoContext.VideoSegment::endMs))
                .toList();
        if (context.chapters() == null || context.chapters().isEmpty()) {
            return List.of(new SegmentGroup(null, "", sorted));
        }

        List<SegmentGroup> groups = new ArrayList<>();
        Set<VideoContext.VideoSegment> assigned = new HashSet<>();
        // Chapters are deliberately not sorted: platform order is the stable user-visible order.
        for (VideoChapter chapter : context.chapters()) {
            List<VideoContext.VideoSegment> chapterSegments = new ArrayList<>();
            for (VideoContext.VideoSegment segment : sorted) {
                if (assigned.contains(segment)) continue;
                if (belongsToChapter(segment, chapter, context.chapters())) {
                    chapterSegments.add(segment);
                    assigned.add(segment);
                }
            }
            // Empty chapters are represented by the chapter directory, not an empty model request.
            if (!chapterSegments.isEmpty()) {
                chapterSegments.sort(Comparator.comparingLong(VideoContext.VideoSegment::startMs));
                groups.add(new SegmentGroup(chapter.id(), chapter.title(), chapterSegments));
            }
        }

        // A malformed/legacy context can contain segments without a chapter. Preserve them in a
        // separate group rather than crossing a chapter boundary or dropping them.
        List<VideoContext.VideoSegment> unassigned = sorted.stream()
                .filter(segment -> !assigned.contains(segment))
                .toList();
        if (!unassigned.isEmpty()) groups.add(new SegmentGroup(null, "", unassigned));
        return groups;
    }

    private boolean belongsToChapter(VideoContext.VideoSegment segment,
                                     VideoChapter chapter,
                                     List<VideoChapter> chapters) {
        if (chapter.id().equals(segment.chapterId())) return true;
        if (segment.chapterId() != null) return false;
        long overlap = overlapMs(segment.startMs(), segment.endMs(), chapter.startMs(), chapter.endMs());
        if (overlap <= 0) return false;
        // If a legacy segment overlaps two chapters, assign it to the chapter with the largest
        // overlap; ties follow platform order because callers iterate chapters in that order.
        long best = chapters.stream()
                .mapToLong(other -> overlapMs(segment.startMs(), segment.endMs(), other.startMs(), other.endMs()))
                .max().orElse(0);
        return overlap == best;
    }

    private long overlapMs(long firstStart, long firstEnd, long secondStart, long secondEnd) {
        return Math.max(0, Math.min(firstEnd, secondEnd) - Math.max(firstStart, secondStart));
    }

    private List<SegmentPart> split(VideoContext.VideoSegment segment) {
        int maxChars = (int) Math.min(properties.getSegmentPartMaxChars(),
                Math.max(1L, (long) Math.floor(properties.effectiveHardInputTokens()
                        * properties.getCharsPerToken())));
        if (segment.transcript().length() + segment.ocrTexts().stream().mapToInt(String::length).sum()
                <= maxChars && estimateTokens(segment.transcript().length()
                + segment.ocrTexts().stream().mapToInt(String::length).sum())
                <= properties.effectiveHardInputTokens()) {
            return List.of(SegmentPart.of(segment));
        }

        List<PartText> partTexts = new ArrayList<>();
        PartText current = new PartText();
        int remaining = maxChars;
        if (!segment.transcript().isEmpty()) {
            remaining = appendText(current, segment.transcript(), true, remaining, partTexts);
        }
        for (String ocr : segment.ocrTexts()) {
            if (ocr == null || ocr.isEmpty()) continue;
            remaining = appendText(current, ocr, false, remaining, partTexts);
        }
        if (!current.empty()) partTexts.add(current);
        if (partTexts.isEmpty()) partTexts.add(new PartText());

        long duration = segment.endMs() - segment.startMs();
        List<SegmentPart> parts = new ArrayList<>(partTexts.size());
        for (int i = 0; i < partTexts.size(); i++) {
            long start = segment.startMs() + duration * i / partTexts.size();
            long end = i == partTexts.size() - 1
                    ? segment.endMs()
                    : segment.startMs() + duration * (i + 1) / partTexts.size();
            parts.add(SegmentPart.of(segment, i + 1, start, end,
                    partTexts.get(i).transcript, partTexts.get(i).ocrTexts));
        }
        return List.copyOf(parts);
    }

    /** Append one text unit while preserving whether it was transcript or OCR. */
    private int appendText(PartText current,
                           String text,
                           boolean transcript,
                           int remaining,
                           List<PartText> completed) {
        int offset = 0;
        while (offset < text.length()) {
            if (remaining == 0) {
                completed.add(current.copy());
                current.clear();
                remaining = Math.min(properties.getSegmentPartMaxChars(),
                        (int) Math.floor(properties.effectiveHardInputTokens()
                                * properties.getCharsPerToken()));
            }
            int take = Math.min(remaining, text.length() - offset);
            String piece = text.substring(offset, offset + take);
            if (transcript) current.transcript += piece;
            else current.ocrTexts.add(piece);
            offset += take;
            remaining -= take;
        }
        return remaining;
    }

    private NoteInputBatch toBatch(Long mediaId,
                                   String profileVersion,
                                   SegmentGroup group,
                                   int sequenceNo,
                                   List<SegmentPart> parts) {
        List<SegmentPart> immutable = List.copyOf(parts);
        long inputChars = immutable.stream().mapToLong(SegmentPart::inputChars).sum();
        long tokens = estimateTokens(inputChars);
        long startMs = immutable.stream().mapToLong(SegmentPart::startMs).min().orElseThrow();
        long endMs = immutable.stream().mapToLong(SegmentPart::endMs).max().orElseThrow();
        return new NoteInputBatch(mediaId,
                NoteInputBatch.stableBatchId(mediaId, profileVersion, group.chapterId(), sequenceNo),
                group.chapterId(), group.chapterTitle(), sequenceNo, startMs, endMs,
                immutable, inputChars, tokens, false);
    }

    private long estimateTokens(long chars) {
        if (chars <= 0) return 0;
        return (long) Math.ceil(chars / properties.getCharsPerToken());
    }

    private void validateProperties() {
        if (!properties.isBatchBoundsValid() || !properties.isModelWindowValid()
                || !properties.isCharsPerTokenValid()) {
            throw new IllegalArgumentException("invalid note batch budget configuration");
        }
        if (properties.effectiveHardInputTokens() < 1 || properties.getSegmentPartMaxChars() < 1
                || properties.getMaxBatches() < 1) {
            throw new IllegalArgumentException("invalid note batch limits");
        }
    }

    private IllegalStateException tooManyBatches(Long mediaId) {
        return new IllegalStateException("note batch limit exceeded for mediaId=" + mediaId
                + "; refusing to omit segments");
    }

    private record SegmentGroup(String chapterId, String chapterTitle,
                                List<VideoContext.VideoSegment> segments) {
    }

    private static final class PartText {
        private String transcript = "";
        private final List<String> ocrTexts = new ArrayList<>();

        private boolean empty() {
            return transcript.isEmpty() && ocrTexts.isEmpty();
        }

        private void clear() {
            transcript = "";
            ocrTexts.clear();
        }

        private PartText copy() {
            PartText copy = new PartText();
            copy.transcript = transcript;
            copy.ocrTexts.addAll(ocrTexts);
            return copy;
        }
    }
}
