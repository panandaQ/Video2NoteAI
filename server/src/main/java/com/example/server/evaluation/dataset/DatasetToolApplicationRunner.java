package com.example.server.evaluation.dataset;

import com.example.server.evaluation.runner.EvaluationDataset;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * 四个数据集构建工具的统一可执行入口。
 *
 * <p>固定子命令：{@code export-chunks/check-retrieval/judge/dedupe}。所有结果显式写 JSON 文件；
 * 检索或 Judge 门禁失败时先写报告，再抛 {@link DatasetGateFailedException} 形成非零退出码。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "evaluation.dataset.command")
public class DatasetToolApplicationRunner implements ApplicationRunner {

    private final ObjectMapper objectMapper;
    private final ChunkDraftExporter chunkDraftExporter;
    private final RetrievalSelfCheckService retrievalSelfCheckService;
    private final QuestionDeduplicationService deduplicationService;

    public DatasetToolApplicationRunner(ObjectMapper objectMapper,
                                        ChunkDraftExporter chunkDraftExporter,
                                        RetrievalSelfCheckService retrievalSelfCheckService,
                                        QuestionDeduplicationService deduplicationService) {
        this.objectMapper = objectMapper;
        this.chunkDraftExporter = chunkDraftExporter;
        this.retrievalSelfCheckService = retrievalSelfCheckService;
        this.deduplicationService = deduplicationService;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String command = required(args, "evaluation.dataset.command").toLowerCase(Locale.ROOT);
        Path input = inputPath(args);
        Path output = outputPath(args, input);
        switch (command) {
            case "export-chunks" -> exportChunks(input, output);
            case "check-retrieval" -> checkRetrieval(args, input, output);
            case "judge" -> judge(args, input, output);
            case "dedupe" -> dedupe(args, input, output);
            default -> throw new IllegalArgumentException("DATASET_COMMAND_UNSUPPORTED");
        }
    }

    private void exportChunks(Path input, Path output) throws IOException {
        ChunkDraftExporter.ExportRequest request = objectMapper.readValue(
                input.toFile(), ChunkDraftExporter.ExportRequest.class);
        // 与已有 chunks-62-66-68.json 保持顶层数组兼容；ExportedVideo 类型本身不含 mediaId。
        writeJson(output, chunkDraftExporter.export(request).videos());
    }

    private void checkRetrieval(ApplicationArguments args, Path input, Path output) throws IOException {
        EvaluationDataset dataset = readDataset(input);
        int topK = integerOption(args, "evaluation.dataset.top-k", 5);
        RetrievalSelfCheckService.Report report = retrievalSelfCheckService.check(dataset, topK);
        writeJson(output, report);
        if (!report.passed()) throw new DatasetGateFailedException("RETRIEVAL_GATE_FAILED");
    }

    private void judge(ApplicationArguments args, Path input, Path output) throws IOException {
        EvaluationDataset dataset = readDataset(input);
        Path chunks = requiredExistingPath(args, "evaluation.dataset.chunks");
        String apiKey = requiredEnvironment("DATASET_JUDGE_API_KEY");
        String baseUrl = environmentOrOption(args, "DATASET_JUDGE_BASE_URL", "evaluation.dataset.judge-base-url");
        String model = environmentOrOption(args, "DATASET_JUDGE_MODEL", "evaluation.dataset.judge-model");
        int timeoutSeconds = integerOption(args, "evaluation.dataset.judge-timeout-seconds", 120);
        EvaluationDataset.Provenance provenance = dataset.provenance();
        JudgeModelConfig.validateIsolation(provenance.generatorModel(), provenance.judgeModel(),
                provenance.productionAnswerModel(), model);
        JudgeModelConfig config = new JudgeModelConfig(apiKey, baseUrl, model,
                Duration.ofSeconds(timeoutSeconds));
        JudgeValidationService service = new JudgeValidationService(
                new LangChainJudgeModelClient(config, objectMapper), objectMapper);
        JudgeValidationService.Report report = service.validate(dataset, ChunkCorpus.read(objectMapper, chunks));
        writeJson(output, report);
        if (!report.passed()) throw new DatasetGateFailedException("JUDGE_GATE_FAILED");
    }

    private void dedupe(ApplicationArguments args, Path input, Path output) throws IOException {
        EvaluationDataset dataset = readDataset(input);
        double threshold = doubleOption(args, "evaluation.dataset.threshold", 0.92);
        writeJson(output, deduplicationService.deduplicate(dataset, threshold));
    }

    private EvaluationDataset readDataset(Path input) throws IOException {
        return objectMapper.readValue(input.toFile(), EvaluationDataset.class);
    }

    private Path inputPath(ApplicationArguments args) {
        return requiredExistingPath(args, "evaluation.dataset.input");
    }

    private Path outputPath(ApplicationArguments args, Path input) {
        Path output = Path.of(required(args, "evaluation.dataset.output")).toAbsolutePath().normalize();
        if (output.equals(input)) throw new IllegalArgumentException("OUTPUT_MUST_NOT_OVERWRITE_INPUT");
        return output;
    }

    private Path requiredExistingPath(ApplicationArguments args, String option) {
        Path path = Path.of(required(args, option)).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("INPUT_FILE_NOT_FOUND");
        return path;
    }

    private String required(ApplicationArguments args, String option) {
        List<String> values = args.getOptionValues(option);
        if (values == null || values.isEmpty() || values.getFirst() == null || values.getFirst().isBlank()) {
            throw new IllegalArgumentException("OPTION_REQUIRED_" + option.toUpperCase(Locale.ROOT)
                    .replace('.', '_').replace('-', '_'));
        }
        return values.getFirst().trim();
    }

    private int integerOption(ApplicationArguments args, String option, int defaultValue) {
        String value = optional(args, option);
        try {
            return value == null ? defaultValue : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("OPTION_INTEGER_INVALID", e);
        }
    }

    private double doubleOption(ApplicationArguments args, String option, double defaultValue) {
        String value = optional(args, option);
        try {
            return value == null ? defaultValue : Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("OPTION_DECIMAL_INVALID", e);
        }
    }

    private String optional(ApplicationArguments args, String option) {
        List<String> values = args.getOptionValues(option);
        if (values == null || values.isEmpty() || values.getFirst() == null || values.getFirst().isBlank()) {
            return null;
        }
        return values.getFirst().trim();
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + "_REQUIRED");
        return value.trim();
    }

    private String environmentOrOption(ApplicationArguments args, String environment, String option) {
        String value = System.getenv(environment);
        if (value != null && !value.isBlank()) return value.trim();
        return required(args, option);
    }

    private void writeJson(Path output, Object value) throws IOException {
        Path parent = output.getParent();
        if (parent != null) Files.createDirectories(parent);
        String filename = output.getFileName().toString();
        String prefix = filename.length() >= 3 ? filename : "dataset";
        Path temp = Files.createTempFile(parent, prefix, ".tmp");
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), value);
            try {
                Files.move(temp, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
