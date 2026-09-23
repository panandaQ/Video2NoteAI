package com.example.server.evaluation.runner;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** 最佳努力读取当前 Git 提交；失败时明确返回 UNKNOWN。 */
@Component
public class CodeCommitResolver {

    public String resolve() {
        Process process = null;
        try {
            process = new ProcessBuilder("git", "rev-parse", "HEAD")
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "UNKNOWN";
            }
            String output = new String(process.getInputStream().readNBytes(128), StandardCharsets.UTF_8).trim();
            return process.exitValue() == 0 && output.matches("[0-9a-fA-F]{40,64}")
                    ? output : "UNKNOWN";
        } catch (Exception e) {
            if (process != null) process.destroyForcibly();
            return "UNKNOWN";
        }
    }
}
