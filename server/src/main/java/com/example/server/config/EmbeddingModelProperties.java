package com.example.server.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** Embedding provider connection, isolated from the chat and ASR credentials. */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "ai.embedding")
public class EmbeddingModelProperties {

    @NotBlank
    private String apiKey;

    @NotBlank
    private String baseUrl;

    @NotBlank
    private String model = "BAAI/bge-m3";
}
