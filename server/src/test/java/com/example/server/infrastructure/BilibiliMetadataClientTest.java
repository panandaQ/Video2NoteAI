package com.example.server.infrastructure;

import com.example.server.config.BilibiliAccessProperties;
import com.example.server.config.VideoImportProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * B 站 view 接口解析契约，全部使用固定 fixture，不访问公网。
 *
 * <p>关键点：CID 只在这里产生，缺失时必须显式报错而不是猜测；平台业务码要原样保留，
 * 由 Adapter 翻译成契约错误码。
 */
class BilibiliMetadataClientTest {

    private final BilibiliMetadataClient client = new BilibiliMetadataClient(
            new ObjectMapper(), new VideoImportProperties(), new BilibiliAccessProperties());

    @Test
    void parsesPagesAndNormalizesDurations() {
        String body = """
                {
                  "code": 0,
                  "message": "0",
                  "data": {
                    "bvid": "BV1LtY968EcB",
                    "aid": 114514191,
                    "title": "谁说傲娇退环境了",
                    "duration": 13,
                    "pic": "https://i0.hdslb.com/bfs/archive/cover.jpg",
                    "owner": {"name": "小西饱饱-"},
                    "pages": [
                      {"cid": 41820686637, "page": 1, "part": "正片", "duration": 13}
                    ]
                  }
                }
                """;

        BilibiliVideoMetadata metadata = client.parse(body);

        assertEquals("BV1LtY968EcB", metadata.bvid());
        assertEquals(114514191L, metadata.aid());
        assertEquals("谁说傲娇退环境了", metadata.title());
        assertEquals("小西饱饱-", metadata.author());
        // 平台封面地址：获取阶段会被转存为受管对象，本地库条目才有缩略图。
        assertEquals("https://i0.hdslb.com/bfs/archive/cover.jpg", metadata.coverUrl());
        assertEquals(13_000L, metadata.durationMs());
        assertEquals(1, metadata.pages().size());
        assertEquals("41820686637", metadata.pages().get(0).cid());
        assertEquals(13_000L, metadata.pages().get(0).durationMs());
    }

    @Test
    void keepsPagesWithoutCidSoAdapterCanFailTheWholeJob() {
        String body = """
                {"code":0,"data":{"bvid":"BV1xx411c7mD","aid":1,"title":"t","pages":[
                  {"page":1,"part":"缺少 cid","duration":10},
                  {"cid":"30000002","page":2,"part":"有 cid","duration":20}
                ]}}
                """;

        BilibiliVideoMetadata metadata = client.parse(body);

        // 契约要求“任一条目缺少 Unit ID 时整单失败”，因此这里必须保留缺 cid 的条目，
        // 由 Adapter 判定失败；静默跳过会让用户以为缺失的那一集不存在。
        assertEquals(2, metadata.pages().size());
        assertNull(metadata.pages().get(0).cid());
        assertEquals(1, metadata.pages().get(0).page());
        assertEquals("30000002", metadata.pages().get(1).cid());
    }

    @Test
    void keepsPlatformBusinessCodeForAdapterTranslation() {
        BilibiliApiException error = assertThrows(BilibiliApiException.class,
                () -> client.parse("{\"code\":-404,\"message\":\"啥都木有\",\"data\":null}"));

        assertEquals(BilibiliApiException.NOT_FOUND, error.code());
    }

    @Test
    void rejectsResponseWithoutBvid() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> client.parse("{\"code\":0,\"data\":{\"aid\":1,\"pages\":[]}}"));

        assertEquals("B 站元数据缺少稿件 ID", error.getMessage());
    }

    @Test
    void rejectsMalformedJson() {
        assertThrows(IllegalStateException.class, () -> client.parse("not json"));
    }

    @Test
    void missingAuthorIsAllowedButTitleIsNot() {
        BilibiliVideoMetadata metadata = client.parse("""
                {"code":0,"data":{"bvid":"BV1xx411c7mD","aid":1,"duration":0,
                  "pages":[{"cid":"1","page":1}]}}
                """);

        assertNull(metadata.author());
        assertNull(metadata.durationMs());
    }
}
