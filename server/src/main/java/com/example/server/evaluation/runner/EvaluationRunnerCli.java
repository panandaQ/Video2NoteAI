package com.example.server.evaluation.runner;

import com.example.server.ServerApplication;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.PrintStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 实际可执行的离线评测入口。
 *
 * <p>调用形式：
 * {@code EvaluationRunnerCli --config run.json [--dataset golden.json] [--output report.json]}。
 * 配置和报告都是 JSON；失败返回非零退出码，报告通过临时文件原子替换，避免留下半截 JSON。
 */
public final class EvaluationRunnerCli {

    private EvaluationRunnerCli() { }

    public static void main(String[] args) {
        int exitCode = execute(args, System.out, System.err);
        if (exitCode != 0) System.exit(exitCode);
    }

    static int execute(String[] args, PrintStream out, PrintStream err) {
        CliPaths cli;
        try {
            cli = parsePaths(args);
        } catch (IllegalArgumentException e) {
            err.println("evaluation_runner_usage_error: " + e.getMessage());
            err.println("usage: --config <run.json> [--dataset <golden.json>] [--output <report.json>]");
            return 2;
        }

        ObjectMapper bootstrapMapper = JsonMapper.builder().findAndAddModules().build();
        EvaluationRunConfig config;
        try {
            ObjectNode configJson = (ObjectNode) bootstrapMapper.readTree(
                    Path.of(cli.configPath()).toFile());
            if (cli.datasetPath() != null) configJson.put("datasetPath", cli.datasetPath());
            if (cli.outputPath() != null) configJson.put("outputPath", cli.outputPath());
            config = bootstrapMapper.treeToValue(configJson, EvaluationRunConfig.class);
        } catch (Exception e) {
            err.println("evaluation_runner_config_error: " + controlledMessage(e));
            return 2;
        }

        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(ServerApplication.class)
                .web(WebApplicationType.NONE)
                .properties(Map.of("spring.main.banner-mode", "off"))
                .run()) {
            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);
            byte[] datasetBytes = Files.readAllBytes(Path.of(config.datasetPath()));
            EvaluationDataset dataset = objectMapper.readValue(datasetBytes, EvaluationDataset.class);
            String commit = context.getBean(CodeCommitResolver.class).resolve();
            EvaluationRunRequest request = config.toRunRequest(
                    commit, dataset.provenance().productionAnswerModel());
            EvaluationReport report = context.getBean(KnowledgeEvaluationRunner.class)
                    .run(datasetBytes, request);
            Path output = Path.of(config.outputPath()).toAbsolutePath().normalize();
            writeAtomically(objectMapper, report, output);
            out.println(output);
            return 0;
        } catch (Exception e) {
            err.println("evaluation_runner_failed: " + controlledMessage(e));
            return 1;
        }
    }

    static CliPaths parsePaths(String[] args) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String argument = args[i];
            if (!argument.startsWith("--")) {
                throw new IllegalArgumentException("unexpected argument: " + argument);
            }
            int equals = argument.indexOf('=');
            String key;
            String value;
            if (equals > 2) {
                key = argument.substring(2, equals);
                value = argument.substring(equals + 1);
            } else {
                key = argument.substring(2);
                if (i + 1 >= args.length) throw new IllegalArgumentException("missing value for --" + key);
                value = args[++i];
            }
            if (!ListOfKeys.SUPPORTED.containsKey(key)) {
                throw new IllegalArgumentException("unknown option --" + key);
            }
            if (value.isBlank()) throw new IllegalArgumentException("blank value for --" + key);
            if (values.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("duplicate option --" + key);
            }
        }
        String config = values.get("config");
        if (config == null) throw new IllegalArgumentException("--config is required");
        return new CliPaths(config, values.get("dataset"), values.get("output"));
    }

    private static void writeAtomically(ObjectMapper objectMapper,
                                        EvaluationReport report,
                                        Path output) throws Exception {
        Path parent = output.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = output.resolveSibling(output.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), report);
            try {
                Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String controlledMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) message = error.getClass().getSimpleName();
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    record CliPaths(String configPath, String datasetPath, String outputPath) { }

    private static final class ListOfKeys {
        private static final Map<String, Boolean> SUPPORTED = Map.of(
                "config", true, "dataset", true, "output", true);

        private ListOfKeys() { }
    }
}
