package com.example.server.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** Speech-to-text provider connection, isolated from chat and Embedding configuration. */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "ai.asr")
public class AsrProperties {

    @NotBlank
    private String apiKey;

    @NotBlank
    private String url;

    @NotBlank
    private String model = "TeleAI/TeleSpeechASR";
}
