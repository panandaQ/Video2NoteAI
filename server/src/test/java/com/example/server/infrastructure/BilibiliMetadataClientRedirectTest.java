package com.example.server.infrastructure;

import com.example.server.config.BilibiliAccessProperties;
import com.example.server.config.VideoImportProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 短链探测契约：**只读一跳、不跟随重定向**。
 *
 * <p>为什么必须由服务端自己看 {@code Location}：跟随重定向是 HTTP 客户端的行为，
 * 一旦交出去就无法逐跳校验主机（跟随重定向会变成任意出站请求）。
 *
 * <p>用 JDK 自带 HttpServer 起真实的 302 响应，不依赖公网、不新增依赖。
 */
class BilibiliMetadataClientRedirectTest {

    private final BilibiliMetadataClient client = new BilibiliMetadataClient(
            new ObjectMapper(), new VideoImportProperties(), new BilibiliAccessProperties());

    private final AtomicInteger shortLinkHits = new AtomicInteger();

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/short", exchange -> {
            shortLinkHits.incrementAndGet();
            exchange.getResponseHeaders().add("Location",
                    "https://www.bilibili.com/video/BV1GJ411x7h7");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/landing", exchange -> {
            byte[] body = "landing page".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/missing", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.createContext("/chain", exchange -> {
            exchange.getResponseHeaders().add("Location", baseUrl + "/short");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void readsLocationWithoutFollowingIt() {
        String location = client.fetchRedirectLocation(baseUrl + "/short");

        assertEquals("https://www.bilibili.com/video/BV1GJ411x7h7", location);
        // 只读一跳：客户端不能自己继续请求 Location 指向的地址。
        assertEquals(1, shortLinkHits.get());
    }

    /** 实测行为：无效短链码返回 200 落地页、没有 Location——这是正常结果，不是异常。 */
    @Test
    void returnsNullWhenResponseHasNoLocation() {
        assertNull(client.fetchRedirectLocation(baseUrl + "/landing"));
        assertNull(client.fetchRedirectLocation(baseUrl + "/missing"));
    }

    @Test
    void nonHttpInputIsIgnoredWithoutRequest() {
        assertNull(client.fetchRedirectLocation(null));
        assertNull(client.fetchRedirectLocation("  "));
        assertNull(client.fetchRedirectLocation("ftp://b23.tv/abc"));
    }

    /** 网络失败必须抛出让 Adapter 翻译成可重试错误的异常，而不是静默返回 null。 */
    @Test
    void transportFailureIsReportedAsIllegalState() {
        String unreachable = "http://127.0.0.1:1/short";

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> client.fetchRedirectLocation(unreachable));

        assertTrue(error.getMessage().contains("短链接跳转读取失败"));
    }
}
