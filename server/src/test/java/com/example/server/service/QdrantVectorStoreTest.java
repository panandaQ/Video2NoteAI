package com.example.server.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.server.dto.VideoChunk;
import com.example.server.dto.VideoContext;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Qdrant payload 与版本/章节过滤契约（计划 §5.1 / §6.2 / §7.1）：
 * 用 JDK HttpServer 起真实 HTTP 端点，不依赖公网与容器。
 */
class QdrantVectorStoreTest {

    private static final String QUERY_RESULT = """
            {"result":{"points":[{"score":0.91,"payload":{
              "startMs":0,"endMs":60000,"mediaId":7,
              "analysisVersion":"VIDEO_CONTEXT_V2","chapterId":"vp-0-0"}}]}}
            """;

    private HttpServer server;
    private QdrantVectorStore store;
    private final AtomicReference<String> upsertBody = new AtomicReference<>();
    private final AtomicReference<String> queryBody = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/collections/video_chunks", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())
                    && "/collections/video_chunks".equals(exchange.getRequestURI().getPath())) {
                respond(exchange, 200, "{\"result\":{}}");
                return;
            }
            respond(exchange, 404, "{}");
        });
        server.createContext("/collections/video_chunks/points", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if ("PUT".equals(exchange.getRequestMethod())) {
                upsertBody.set(body);
                respond(exchange, 200, "{\"result\":{\"status\":\"completed\"}}");
            } else if ("POST".equals(exchange.getRequestMethod()) && path.endsWith("/query")) {
                queryBody.set(body);
                respond(exchange, 200, QUERY_RESULT);
            } else if ("POST".equals(exchange.getRequestMethod()) && path.endsWith("/delete")) {
                respond(exchange, 200, "{\"result\":{}}");
            } else {
                respond(exchange, 404, "{}");
            }
        });
        server.start();
        store = new QdrantVectorStore(true,
                "http://127.0.0.1:" + server.getAddress().getPort(), "", "video_chunks");
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static VideoChunk chunk(String chapterId) {
        return new VideoChunk(0L, 60_000L, "摘要", List.of("关键词"), List.of(), List.of(0.1, 0.2),
                VideoContext.ANALYSIS_VERSION_V2,
                chapterId, chapterId == null ? null : "章节标题",
                chapterId == null ? null : 0L, chapterId == null ? null : 60_000L);
    }

    @Test
    void upsertWritesVersionAndChapterPayload() {
        store.upsert(7L, List.of(chunk("vp-0-0")));

        JSONObject body = JSON.parseObject(upsertBody.get());
        JSONObject point = body.getJSONArray("points").getJSONObject(0);
        assertEquals(7L, point.getJSONObject("payload").getLongValue("mediaId"));
        assertEquals("VIDEO_CONTEXT_V2",
                point.getJSONObject("payload").getString("analysisVersion"));
        assertEquals("vp-0-0", point.getJSONObject("payload").getString("chapterId"));
        assertEquals("章节标题", point.getJSONObject("payload").getString("chapterTitle"));
    }

    @Test
    void legacyChunkWithoutChapterOmitsNullPayloadFields() {
        store.upsert(7L, List.of(chunk(null)));

        JSONObject payload = JSON.parseObject(upsertBody.get())
                .getJSONArray("points").getJSONObject(0).getJSONObject("payload");
        assertFalse(payload.containsKey("chapterId"));
        assertTrue(payload.containsKey("analysisVersion"));
    }

    @Test
    void searchFiltersByMediaVersionAndChapter() {
        List<QdrantVectorStore.VectorHit> hits =
                store.search(7L, VideoContext.ANALYSIS_VERSION_V2, "vp-0-0", List.of(0.1, 0.2), 5);

        JSONObject body = JSON.parseObject(queryBody.get());
        JSONArray must = body.getJSONObject("filter").getJSONArray("must");
        assertEquals(3, must.size());
        assertEquals("mediaId", must.getJSONObject(0).getString("key"));
        assertEquals("analysisVersion", must.getJSONObject(1).getString("key"));
        assertEquals("VIDEO_CONTEXT_V2", must.getJSONObject(1).getJSONObject("match").getString("value"));
        assertEquals("chapterId", must.getJSONObject(2).getString("key"));
        assertEquals("vp-0-0", must.getJSONObject(2).getJSONObject("match").getString("value"));

        assertEquals(1, hits.size());
        assertEquals(0L, hits.get(0).startMs());
        assertEquals(60_000L, hits.get(0).endMs());
    }

    @Test
    void nullVersionFallsBackToMediaOnlyFilter() {
        store.search(7L, null, null, List.of(0.1, 0.2), 5);
        JSONObject body = JSON.parseObject(queryBody.get());
        assertEquals(1, body.getJSONObject("filter").getJSONArray("must").size());
    }

    @Test
    void disabledStoreShortCircuitsWithoutHttp() throws IOException {
        server.stop(0);
        QdrantVectorStore disabled = new QdrantVectorStore(false,
                "http://127.0.0.1:1", "", "video_chunks");
        disabled.upsert(7L, List.of(chunk("vp-0-0")));
        assertTrue(upsertBody.get() == null || upsertBody.get().isBlank());
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
