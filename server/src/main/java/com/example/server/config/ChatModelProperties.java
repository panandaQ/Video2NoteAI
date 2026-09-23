package com.example.server.config;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * OpenAI-compatible chat model connection.
 *
 * <p>The chat provider is intentionally independent from Embedding and ASR so switching
 * between SiliconFlow, Alibaba Cloud Model Studio, and the official DeepSeek API cannot
 * redirect the other two workloads or reuse the wrong credential.
 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "ai.chat")
public class ChatModelProperties {

    @NotBlank
    private String apiKey;

    @NotBlank
    private String baseUrl;

    @NotBlank
    private String model;

    @Min(1)
    private long timeoutSeconds = 300;

    @DecimalMin("0")
    private double inputPricePerMillion;

    @DecimalMin("0")
    private double outputPricePerMillion;
}
