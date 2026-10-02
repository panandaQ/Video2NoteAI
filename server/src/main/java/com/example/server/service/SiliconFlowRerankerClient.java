package com.example.server.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.server.config.EmbeddingModelProperties;
import com.example.server.config.HybridRetrievalProperties;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** SiliconFlow BGE Cross-Encoder client. Failure is intentionally visible to the caller for RRF fallback. */
@Service
public class SiliconFlowRerankerClient {
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    private final EmbeddingModelProperties connection;
    private final HybridRetrievalProperties properties;
    private final OkHttpClient client;

    public SiliconFlowRerankerClient(EmbeddingModelProperties connection,
                                     HybridRetrievalProperties properties) {
        this.connection = connection;
        this.properties = properties;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(properties.getRerankerTimeoutMs(), TimeUnit.MILLISECONDS)
                .readTimeout(properties.getRerankerTimeoutMs(), TimeUnit.MILLISECONDS)
                .writeTimeout(properties.getRerankerTimeoutMs(), TimeUnit.MILLISECONDS)
                .callTimeout(properties.getRerankerTimeoutMs(), TimeUnit.MILLISECONDS)
                .build();
    }

    public List<RerankResult> rerank(String query, List<String> documents, int topN) {
        if (query == null || query.isBlank() || documents == null || documents.isEmpty()) return List.of();
        if (connection.getApiKey() == null || connection.getApiKey().isBlank()) {
            throw new IllegalStateException("SiliconFlow reranker API key is not configured");
        }
        RuntimeException failure = null;
        for (int attempt = 0; attempt <= properties.getRerankerMaxRetries(); attempt++) {
            try {
                JSONObject payload = new JSONObject();
                payload.put("model", properties.getRerankerModel());
                payload.put("query", query);
                payload.put("documents", documents);
                payload.put("top_n", Math.min(topN, documents.size()));
                payload.put("return_documents", false);

                Request request = new Request.Builder()
                        .url(connection.getBaseUrl().replaceAll("/+$", "") + "/rerank")
                        .addHeader("Authorization", "Bearer " + connection.getApiKey())
                        .post(RequestBody.create(payload.toString(), JSON_MEDIA_TYPE))
                        .build();
                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        throw new IllegalStateException("SiliconFlow rerank failed: " + response.code());
                    }
                    return parseResults(response.body().string(), documents.size(), topN);
                }
            } catch (RuntimeException e) {
                failure = e;
            } catch (Exception e) {
                failure = new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("SiliconFlow reranker unavailable", failure);
    }

    static List<RerankResult> parseResults(String responseJson, int documentCount, int topN) {
        JSONArray results = JSON.parseObject(responseJson).getJSONArray("results");
        if (results == null || results.isEmpty()) {
            throw new IllegalStateException("SiliconFlow rerank results are empty");
        }
        List<RerankResult> parsed = new ArrayList<>(results.size());
        Set<Integer> seenIndexes = new HashSet<>();
        for (Object item : results) {
            JSONObject row = (JSONObject) item;
            int index = row.getIntValue("index");
            if (index < 0 || index >= documentCount || !seenIndexes.add(index)) {
                throw new IllegalStateException("SiliconFlow rerank returned an invalid result index");
            }
            parsed.add(new RerankResult(index, row.getDoubleValue("relevance_score")));
        }
        return parsed.stream()
                .sorted(Comparator.comparingDouble(RerankResult::score).reversed())
                .limit(Math.max(0, topN))
                .toList();
    }

    public record RerankResult(int index, double score) { }
}
