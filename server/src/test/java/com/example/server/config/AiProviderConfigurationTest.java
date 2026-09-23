package com.example.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiProviderConfigurationTest {

    @Test
    void officialDeepSeekChatDoesNotRedirectSiliconFlowEmbeddingOrAsr() throws IOException {
        StandardEnvironment environment = environment(Map.ofEntries(
                Map.entry("SILICONFLOW_API_KEY", "silicon-key"),
                Map.entry("SILICONFLOW_BASE_URL", "https://api.siliconflow.cn/v1"),
                Map.entry("DEEPSEEK_API_KEY", "deepseek-key"),
                Map.entry("CHAT_BASE_URL", "https://api.deepseek.com"),
                Map.entry("CHAT_MODEL", "deepseek-v4-flash"),
                Map.entry("EMBEDDING_MODEL", "BAAI/bge-m3"),
                Map.entry("ASR_MODEL", "TeleAI/TeleSpeechASR")
        ));

        ChatModelProperties chat = bind(environment, "ai.chat", ChatModelProperties.class);
        EmbeddingModelProperties embedding = bind(environment, "ai.embedding", EmbeddingModelProperties.class);
        AsrProperties asr = bind(environment, "ai.asr", AsrProperties.class);

        assertEquals("deepseek-key", chat.getApiKey());
        assertEquals("https://api.deepseek.com", chat.getBaseUrl());
        assertEquals("deepseek-v4-flash", chat.getModel());
        assertEquals("silicon-key", embedding.getApiKey());
        assertEquals("https://api.siliconflow.cn/v1", embedding.getBaseUrl());
        assertEquals("silicon-key", asr.getApiKey());
        assertEquals("https://api.siliconflow.cn/v1/audio/transcriptions", asr.getUrl());
        assertEquals("TeleAI/TeleSpeechASR", asr.getModel());
    }

    @Test
    void explicitKeysCanRouteAllThreeWorkloadsToDifferentProviders() throws IOException {
        StandardEnvironment environment = environment(Map.ofEntries(
                Map.entry("SILICONFLOW_API_KEY", "legacy-key"),
                Map.entry("CHAT_API_KEY", "chat-key"),
                Map.entry("CHAT_BASE_URL", "https://chat.example/v1"),
                Map.entry("CHAT_MODEL", "chat-model"),
                Map.entry("EMBEDDING_API_KEY", "embedding-key"),
                Map.entry("EMBEDDING_BASE_URL", "https://embedding.example/v1"),
                Map.entry("EMBEDDING_MODEL", "embedding-model"),
                Map.entry("ASR_API_KEY", "asr-key"),
                Map.entry("ASR_URL", "https://asr.example/v1/audio/transcriptions"),
                Map.entry("ASR_MODEL", "asr-model")
        ));

        ChatModelProperties chat = bind(environment, "ai.chat", ChatModelProperties.class);
        EmbeddingModelProperties embedding = bind(environment, "ai.embedding", EmbeddingModelProperties.class);
        AsrProperties asr = bind(environment, "ai.asr", AsrProperties.class);

        assertEquals("chat-key", chat.getApiKey());
        assertEquals("https://chat.example/v1", chat.getBaseUrl());
        assertEquals("embedding-key", embedding.getApiKey());
        assertEquals("https://embedding.example/v1", embedding.getBaseUrl());
        assertEquals("asr-key", asr.getApiKey());
        assertEquals("https://asr.example/v1/audio/transcriptions", asr.getUrl());
    }

    private static StandardEnvironment environment(Map<String, Object> overrides) throws IOException {
        Properties application = PropertiesLoaderUtils.loadAllProperties("application.properties");
        StandardEnvironment environment = new StandardEnvironment();
        Map<String, Object> orderedOverrides = new LinkedHashMap<>(overrides);
        environment.getPropertySources().addFirst(new MapPropertySource("test-overrides", orderedOverrides));
        environment.getPropertySources().addLast(new PropertiesPropertySource("application", application));
        return environment;
    }

    private static <T> T bind(StandardEnvironment environment, String prefix, Class<T> type) {
        return Binder.get(environment).bind(prefix, Bindable.of(type))
                .orElseThrow(() -> new IllegalStateException("Missing configuration prefix: " + prefix));
    }
}
