package com.example.server.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.server.dto.VideoChunk;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class QdrantVectorStore {

    private static final Logger log = LoggerFactory.getLogger(QdrantVectorStore.class);
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    private final boolean enabled;
    private final String baseUrl;
    private final String apiKey;
    private final String collection;
    private final String denseCollection;
    private final AtomicBoolean collectionReady = new AtomicBoolean();
    private final AtomicBoolean denseCollectionReady = new AtomicBoolean();
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    @Autowired
    public QdrantVectorStore(@Value("${vector.qdrant.enabled:true}") boolean enabled,
                             @Value("${vector.qdrant.url:http://localhost:6333}") String baseUrl,
                             @Value("${vector.qdrant.api-key:}") String apiKey,
                             @Value("${vector.qdrant.collection:video_chunks}") String collection,
                             @Value("${vector.qdrant.dense-collection:video_chunk_dense_bge_m3_v1}") String denseCollection) {
        if (!collection.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("Qdrant collection name is invalid");
        }
        if (!denseCollection.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("Qdrant dense collection name is invalid");
        }
        this.enabled = enabled;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey;
        this.collection = collection;
        this.denseCollection = denseCollection;
    }

    /** Test and embedded callers may keep the historical constructor while using the versioned default. */
    public QdrantVectorStore(boolean enabled, String baseUrl, String apiKey, String collection) {
        this(enabled, baseUrl, apiKey, collection, "video_chunk_dense_bge_m3_v1");
    }

    public void upsert(Long mediaId, List<VideoChunk> chunks) {
        if (!enabled) return;
        List<VideoChunk> vectorized = chunks.stream().filter(chunk -> !chunk.embedding().isEmpty()).toList();
        if (vectorized.isEmpty()) return;
        try {
            ensureCollection(vectorized.get(0).embedding().size());
            JSONArray points = new JSONArray();
            for (VideoChunk chunk : vectorized) {
                JSONObject payload = new JSONObject();
                payload.put("mediaId", mediaId);
                payload.put("startMs", chunk.startTime());
                payload.put("endMs", chunk.endTime());
                if (chunk.analysisVersion() != null) {
                    payload.put("analysisVersion", chunk.analysisVersion());
                }
                if (chunk.chapterId() != null) {
                    payload.put("chapterId", chunk.chapterId());
                    payload.put("chapterTitle", chunk.chapterTitle());
                    payload.put("chapterStartMs", chunk.chapterStartMs());
                    payload.put("chapterEndMs", chunk.chapterEndMs());
                }

                JSONObject point = new JSONObject();
                point.put("id", pointId(mediaId, chunk));
                point.put("vector", chunk.embedding());
                point.put("payload", payload);
                points.add(point);
            }
            JSONObject body = new JSONObject();
            body.put("points", points);
            execute(new Request.Builder()
                    .url(baseUrl + "/collections/" + collection + "/points?wait=true")
                    .put(RequestBody.create(body.toString(), JSON_MEDIA_TYPE)));
        } catch (RuntimeException e) {
            collectionReady.set(false);
            throw new IllegalStateException("Qdrant 分段向量写入失败", e);
        }
    }

    /**
     * 语义检索：强制过滤 {@code mediaId + analysisVersion}，防止 V2 查询命中旧 ASR-only 点；
     * {@code chapterId} 非空时进一步限定单章（逐章检索，计划 §5.3）。
     */
    public List<VectorHit> search(Long mediaId, String analysisVersion, String chapterId,
                                  List<Double> queryEmbedding, int limit) {
        if (!enabled || queryEmbedding.isEmpty()) return List.of();
        try {
            ensureCollection(queryEmbedding.size());
            List<JSONObject> conditions = new ArrayList<>();
            conditions.add(matchCondition("mediaId", mediaId));
            if (analysisVersion != null) {
                conditions.add(matchCondition("analysisVersion", analysisVersion));
            }
            if (chapterId != null && !chapterId.isBlank()) {
                conditions.add(matchCondition("chapterId", chapterId));
            }
            JSONObject filter = new JSONObject();
            filter.put("must", conditions);

            JSONObject body = new JSONObject();
            body.put("query", queryEmbedding);
            body.put("filter", filter);
            body.put("limit", limit);
            body.put("with_payload", true);
            String response = execute(new Request.Builder()
                    .url(baseUrl + "/collections/" + collection + "/points/query")
                    .post(RequestBody.create(body.toString(), JSON_MEDIA_TYPE)));

            JSONObject result = JSON.parseObject(response).getJSONObject("result");
            JSONArray points = result == null ? null : result.getJSONArray("points");
            if (points == null) return List.of();
            List<VectorHit> hits = new ArrayList<>(points.size());
            for (int i = 0; i < points.size(); i++) {
                JSONObject point = points.getJSONObject(i);
                JSONObject payload = point.getJSONObject("payload");
                if (payload == null) continue;
                hits.add(new VectorHit(
                        payload.getLongValue("startMs"),
                        payload.getLongValue("endMs"),
                        point.getDoubleValue("score")));
            }
            return hits;
        } catch (RuntimeException e) {
            collectionReady.set(false);
            throw new IllegalStateException("Qdrant 语义检索失败", e);
        }
    }

    /** 兼容旧调用方：不过滤版本与章节（仅 mediaId）。 */
    public List<VectorHit> search(Long mediaId, List<Double> queryEmbedding, int limit) {
        return search(mediaId, null, null, queryEmbedding, limit);
    }

    /** Writes caller-supplied transcript vectors; the legacy chunk embedding is never reused here. */
    public void upsertDense(Long mediaId, List<VideoChunk> chunks,
                            List<List<Double>> vectors, String targetCollection,
                            String indexVersion) {
        if (!enabled || chunks == null || vectors == null) return;
        List<VideoChunk> selectedChunks = new ArrayList<>();
        List<List<Double>> selectedVectors = new ArrayList<>();
        for (int i = 0; i < Math.min(chunks.size(), vectors.size()); i++) {
            List<Double> vector = vectors.get(i);
            if (vector != null && !vector.isEmpty()) {
                selectedChunks.add(chunks.get(i));
                selectedVectors.add(vector);
            }
        }
        if (selectedChunks.isEmpty()) return;
        String selected = validateCollection(targetCollection);
        try {
            ensureCollection(selected, selectedVectors.get(0).size(), denseCollectionReady);
            JSONArray points = new JSONArray();
            for (int i = 0; i < selectedChunks.size(); i++) {
                VideoChunk chunk = selectedChunks.get(i);
                JSONObject payload = new JSONObject();
                payload.put("mediaId", mediaId);
                payload.put("startMs", chunk.startTime());
                payload.put("endMs", chunk.endTime());
                payload.put("analysisVersion", chunk.analysisVersion());
                payload.put("indexVersion", indexVersion);
                JSONObject point = new JSONObject();
                point.put("id", pointId(mediaId, chunk));
                point.put("vector", selectedVectors.get(i));
                point.put("payload", payload);
                points.add(point);
            }
            JSONObject body = new JSONObject();
            body.put("points", points);
            execute(request(baseUrl + "/collections/" + selected + "/points?wait=true")
                    .put(RequestBody.create(body.toString(), JSON_MEDIA_TYPE)));
        } catch (RuntimeException e) {
            denseCollectionReady.set(false);
            throw new IllegalStateException("Qdrant BGE-M3 向量写入失败", e);
        }
    }

    public List<VectorHit> searchDense(Long mediaId, String analysisVersion,
                                        List<Double> queryEmbedding, int limit,
                                        String targetCollection, String indexVersion) {
        if (!enabled || queryEmbedding == null || queryEmbedding.isEmpty()) return List.of();
        String selected = validateCollection(targetCollection);
        try {
            ensureCollection(selected, queryEmbedding.size(), denseCollectionReady);
            List<JSONObject> conditions = new ArrayList<>();
            conditions.add(matchCondition("mediaId", mediaId));
            if (analysisVersion != null && !analysisVersion.isBlank()) {
                conditions.add(matchCondition("analysisVersion", analysisVersion));
            }
            if (indexVersion != null && !indexVersion.isBlank()) {
                conditions.add(matchCondition("indexVersion", indexVersion));
            }
            JSONObject filter = new JSONObject();
            filter.put("must", conditions);
            JSONObject body = new JSONObject();
            body.put("query", queryEmbedding);
            body.put("filter", filter);
            body.put("limit", limit);
            body.put("with_payload", true);
            String response = execute(request(baseUrl + "/collections/" + selected + "/points/query")
                    .post(RequestBody.create(body.toString(), JSON_MEDIA_TYPE)));
            JSONArray points = JSON.parseObject(response).getJSONObject("result").getJSONArray("points");
            if (points == null) return List.of();
            List<VectorHit> hits = new ArrayList<>(points.size());
            for (Object value : points) {
                JSONObject payload = ((JSONObject) value).getJSONObject("payload");
                if (payload != null) {
                    hits.add(new VectorHit(payload.getLongValue("startMs"),
                            payload.getLongValue("endMs"), ((JSONObject) value).getDoubleValue("score")));
                }
            }
            return hits;
        } catch (RuntimeException e) {
            denseCollectionReady.set(false);
            throw new IllegalStateException("Qdrant BGE-M3 语义检索失败", e);
        }
    }

    private JSONObject matchCondition(String key, Object value) {
        JSONObject match = new JSONObject();
        match.put("value", value);
        JSONObject condition = new JSONObject();
        condition.put("key", key);
        condition.put("match", match);
        return condition;
    }

    public void deleteMedia(Long mediaId) {
        if (!enabled) return;
        try {
            JSONObject match = new JSONObject();
            match.put("value", mediaId);
            JSONObject condition = new JSONObject();
            condition.put("key", "mediaId");
            condition.put("match", match);
            JSONObject filter = new JSONObject();
            filter.put("must", List.of(condition));
            JSONObject body = new JSONObject();
            body.put("filter", filter);
            execute(new Request.Builder()
                    .url(baseUrl + "/collections/" + collection + "/points/delete?wait=true")
                    .post(RequestBody.create(body.toString(), JSON_MEDIA_TYPE)));
            deleteMediaFromCollection(mediaId, denseCollection);
        } catch (RuntimeException e) {
            log.warn("qdrant_media_cleanup_failed mediaId={}", mediaId, e);
        }
    }

    private void deleteMediaFromCollection(Long mediaId, String targetCollection) {
        JSONObject match = new JSONObject();
        match.put("value", mediaId);
        JSONObject condition = new JSONObject();
        condition.put("key", "mediaId");
        condition.put("match", match);
        JSONObject filter = new JSONObject();
        filter.put("must", List.of(condition));
        JSONObject body = new JSONObject();
        body.put("filter", filter);
        execute(new Request.Builder()
                .url(baseUrl + "/collections/" + targetCollection + "/points/delete?wait=true")
                .post(RequestBody.create(body.toString(), JSON_MEDIA_TYPE)));
    }

    private void ensureCollection(int vectorSize) {
        ensureCollection(collection, vectorSize, collectionReady);
    }

    private void ensureCollection(String targetCollection, int vectorSize, AtomicBoolean ready) {
        if (ready.get()) return;
        synchronized (ready) {
            if (ready.get()) return;
            Request.Builder lookup = request(baseUrl + "/collections/" + targetCollection).get();
            try (Response response = client.newCall(lookup.build()).execute()) {
                if (response.isSuccessful()) {
                    ready.set(true);
                    return;
                }
                if (response.code() != 404) {
                    throw new IllegalStateException("Qdrant collection lookup failed: " + response.code());
                }
            } catch (Exception e) {
                throw new IllegalStateException("Qdrant collection lookup failed", e);
            }

            JSONObject vectors = new JSONObject();
            vectors.put("size", vectorSize);
            vectors.put("distance", "Cosine");
            JSONObject body = new JSONObject();
            body.put("vectors", vectors);
            execute(request(baseUrl + "/collections/" + targetCollection)
                    .put(RequestBody.create(body.toString(), JSON_MEDIA_TYPE)));
            ready.set(true);
        }
    }

    private String validateCollection(String targetCollection) {
        String selected = targetCollection == null || targetCollection.isBlank() ? denseCollection : targetCollection;
        if (!selected.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("Qdrant collection name is invalid");
        }
        return selected;
    }

    private String execute(Request.Builder request) {
        try (Response response = client.newCall(withApiKey(request).build()).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IllegalStateException("Qdrant API failed: " + response.code() + " " + body);
            }
            return body;
        } catch (Exception e) {
            throw new IllegalStateException("Qdrant request failed", e);
        }
    }

    private Request.Builder request(String url) {
        return withApiKey(new Request.Builder().url(url));
    }

    private Request.Builder withApiKey(Request.Builder request) {
        if (!apiKey.isBlank()) request.header("api-key", apiKey);
        return request;
    }

    private String pointId(Long mediaId, VideoChunk chunk) {
        String source = mediaId + ":" + (chunk.analysisVersion() == null ? "V1" : chunk.analysisVersion())
                + ":" + chunk.startTime() + ":" + chunk.endTime();
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)).toString();
    }

    public record VectorHit(long startMs, long endMs, double score) {
    }
}
