package com.example.server.service.ingest;

import com.example.server.ServerApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** CLI entry point for a one-shot retrieval-index rebuild. */
public final class RagIndexRebuildCli {

    private RagIndexRebuildCli() {
    }

    public static void main(String[] args) {
        int exitCode = 0;
        ConfigurableApplicationContext context = null;
        try {
            context = new SpringApplicationBuilder(ServerApplication.class)
                    .web(WebApplicationType.NONE)
                    .logStartupInfo(false)
                    .run(args);
        } catch (Throwable error) {
            exitCode = 1;
            System.err.println("rag_index_rebuild_failed code=" + diagnosticCode(error));
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
            current = current.getCause();
        }
        return "UNEXPECTED_" + error.getClass().getSimpleName().toUpperCase(java.util.Locale.ROOT);
    }
}
