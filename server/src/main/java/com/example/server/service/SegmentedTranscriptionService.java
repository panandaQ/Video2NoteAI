package com.example.server.service;

import com.example.server.dto.TranscriptSegment;
import com.example.server.dto.TranscriptSource;
import com.example.server.dto.VideoChapter;
import com.example.server.utils.AliyunAsrUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class SegmentedTranscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SegmentedTranscriptionService.class);
    private static final long SEGMENT_MS = 60_000L;

    private final AliyunAsrUtils aliyunAsrUtils;
    private final AgentTelemetry telemetry;

    public SegmentedTranscriptionService(AliyunAsrUtils aliyunAsrUtils, AgentTelemetry telemetry) {
        this.aliyunAsrUtils = aliyunAsrUtils;
        this.telemetry = telemetry;
    }

    public List<TranscriptSegment> transcribe(String videoPath, Path audioDir, String traceId) throws Exception {
        return transcribe(videoPath, audioDir, traceId, List.of(), null);
    }

    /**
     * 带边界的分片转写（计划 §5.1）：音频切片边界 = 60 秒边界 ∪ View 章节边界，
     * 从切片阶段保证一个 ASR segment 不跨章；边界为空时保持既有 60 秒固定分片行为。
     *
     * @param boundariesMs 已按升序去重的切片边界（不含 0 与结尾）
     * @param durationMs   视频时长；已知时最后一刀到时长为止
     */
    public List<TranscriptSegment> transcribe(String videoPath, Path audioDir, String traceId,
                                              List<Long> boundariesMs, Long durationMs) throws Exception {
        Files.createDirectories(audioDir);
        Path outputPattern = audioDir.resolve("audio_%03d.mp3");
        List<Long> boundaries = normalizeBoundaries(boundariesMs, durationMs);
        runFfmpeg(videoPath, outputPattern, boundaries);

        List<Path> audioFiles;
        try (var paths = Files.list(audioDir)) {
            audioFiles = paths.filter(Files::isRegularFile).sorted().toList();
        }

        List<long[]> ranges = sliceRanges(boundaries, durationMs, audioFiles.size());
        List<TranscriptSegment> result = new ArrayList<>();
        int failedSegments = 0;
        RuntimeException lastSegmentError = null;
        for (int i = 0; i < audioFiles.size(); i++) {
            Path audioFile = audioFiles.get(i);
            try {
                telemetry.increment(traceId, "asrCalls", 1);
                String text = aliyunAsrUtils.audioToText(audioFile.toString());
                if (text != null && !text.isBlank()) {
                    long start = i < ranges.size() ? ranges.get(i)[0] : (long) i * SEGMENT_MS;
                    long end = i < ranges.size() ? ranges.get(i)[1] : start + SEGMENT_MS;
                    result.add(new TranscriptSegment(start, end, text, TranscriptSource.ASR));
                }
            } catch (RuntimeException e) {
                failedSegments++;
                lastSegmentError = e;
                telemetry.increment(traceId, "asrSegmentFailures", 1);
                log.warn("asr_segment_failed segment={} file={}", i, audioFile.getFileName(), e);
            }
        }
        if (result.isEmpty() && failedSegments > 0) {
            // 必须带上 cause：AliyunAsrUtils 已经区分了「429/5xx 可重试」与「4xx 请求本身有问题」，
            // 这里若丢掉原因，消费者只能看到一个笼统的 IllegalStateException，
            // 于是参数错误也会被当成抖动反复重试整条 ASR+LLM 流水线。
            throw new IllegalStateException("所有 ASR 分片均处理失败", lastSegmentError);
        }
        return result;
    }

    public String transcribeToText(String videoPath) {
        Path workDir = Path.of(System.getProperty("java.io.tmpdir"), "transcription-" + UUID.randomUUID());
        try {
            return transcribe(videoPath, workDir, null).stream()
                    .map(TranscriptSegment::text)
                    .filter(text -> !text.isBlank())
                    .collect(java.util.stream.Collectors.joining("\n"));
        } catch (Exception e) {
            throw new IllegalStateException("视频转写失败", e);
        } finally {
            deleteDirectory(workDir);
        }
    }

    /** 60 秒网格 ∪ 章节边界 的并集（升序去重，过滤 0 与结尾，供 -segment_times 使用）。 */
    public static List<Long> audioSliceBoundaries(List<VideoChapter> chapters, Long durationMs) {
        boolean bounded = durationMs != null && durationMs > 0;
        long duration = bounded ? durationMs : Long.MAX_VALUE;
        TreeSet<Long> boundaries = new TreeSet<>();
        // 时长未知时网格最多铺到 24 小时（防御性上限，避免无界循环）
        for (long t = SEGMENT_MS; bounded ? t < duration : t <= 24 * 3600_000L; t += SEGMENT_MS) {
            boundaries.add(t);
        }
        if (chapters != null) {
            for (VideoChapter chapter : chapters) {
                if (chapter.startMs() > 0 && (!bounded || chapter.startMs() < duration)) {
                    boundaries.add(chapter.startMs());
                }
                if (chapter.endMs() > 0 && (!bounded || chapter.endMs() < duration)) {
                    boundaries.add(chapter.endMs());
                }
            }
        }
        return List.copyOf(boundaries);
    }

    static List<Long> normalizeBoundaries(List<Long> boundariesMs, Long durationMs) {
        if (boundariesMs == null) return List.of();
        long duration = durationMs == null ? Long.MAX_VALUE : durationMs;
        TreeSet<Long> set = new TreeSet<>(boundariesMs.stream()
                .filter(boundary -> boundary != null && boundary > 0
                        && (duration == Long.MAX_VALUE || boundary < duration))
                .toList());
        return List.copyOf(set);
    }

    static List<long[]> sliceRanges(List<Long> boundaries, Long durationMs, int fileCount) {
        long[] starts = new long[boundaries.size() + 1];
        starts[0] = 0;
        for (int i = 0; i < boundaries.size(); i++) {
            starts[i + 1] = boundaries.get(i);
        }
        List<long[]> ranges = new ArrayList<>(fileCount);
        for (int i = 0; i < fileCount; i++) {
            long start = i < starts.length ? starts[i] : starts[starts.length - 1]
                    + (long) (i - starts.length + 1) * SEGMENT_MS;
            long end = (i + 1) < starts.length ? starts[i + 1]
                    : durationMs != null ? durationMs : start + SEGMENT_MS;
            if (end <= start) end = start + SEGMENT_MS;
            ranges.add(new long[]{start, end});
        }
        return ranges;
    }

    private void runFfmpeg(String videoPath, Path outputPattern, List<Long> boundariesMs) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                "ffmpeg", "-y", "-i", videoPath,
                "-vn", "-acodec", "libmp3lame",
                "-f", "segment", "-reset_timestamps", "1"));
        if (boundariesMs.isEmpty()) {
            command.add("-segment_time");
            command.add("60");
        } else {
            String times = boundariesMs.stream()
                    .map(ms -> String.format(Locale.ROOT, "%.3f", ms / 1000.0))
                    .collect(Collectors.joining(","));
            command.add("-segment_times");
            command.add(times);
        }
        command.add(outputPattern.toString());
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            if (!process.waitFor(15, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IllegalStateException("FFmpeg 执行超时");
            }
            if (process.exitValue() != 0) throw new IllegalStateException("FFmpeg 执行失败");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private void deleteDirectory(Path directory) {
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception e) {
                    log.warn("transcription_temporary_file_cleanup_failed path={}", path, e);
                }
            });
        } catch (Exception e) {
            log.warn("transcription_temporary_directory_cleanup_failed path={}", directory, e);
        }
    }
}
