package com.example.server.evaluation.dataset;

import com.example.server.ServerApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** 可由 Maven/IDE/组装后的 classpath 直接启动的统一 CLI main。 */
public final class DatasetToolCli {

    private DatasetToolCli() {
    }

    public static void main(String[] args) {
        if (java.util.Arrays.stream(args)
                .noneMatch(argument -> argument.startsWith("--evaluation.dataset.command="))) {
            System.err.println("dataset_tool_failed code=OPTION_REQUIRED_EVALUATION_DATASET_COMMAND");
            System.exit(1);
        }
        int exitCode = 0;
        ConfigurableApplicationContext context = null;
        try {
            context = new SpringApplicationBuilder(ServerApplication.class)
                    .web(WebApplicationType.NONE)
                    .logStartupInfo(false)
                    .run(args);
        } catch (Throwable error) {
            DatasetGateFailedException gate = cause(error, DatasetGateFailedException.class);
            if (gate != null) {
                exitCode = 2;
                System.err.println("dataset_tool_gate_failed code=" + gate.getMessage());
            } else {
                exitCode = 1;
                System.err.println("dataset_tool_failed code=" + diagnosticCode(error));
            }
        } finally {
            if (context != null) context.close();
        }
        if (exitCode != 0) System.exit(exitCode);
    }

    private static String diagnosticCode(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.matches("[A-Z0-9_]+")) return message;
            if (current.getCause() == current) break;
            current = current.getCause();
        }
        return "UNEXPECTED_" + error.getClass().getSimpleName().toUpperCase(java.util.Locale.ROOT);
    }

    private static <T extends Throwable> T cause(Throwable error, Class<T> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) return type.cast(current);
            if (current.getCause() == current) break;
            current = current.getCause();
        }
        return null;
    }
}
