package com.example.server.infrastructure;

import com.example.server.config.BilibiliAccessProperties;
import com.example.server.config.VideoImportProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * player 接口解析契约（计划 §4.3 / §7.1）：固定 fixture，不依赖公网。
 */
class BilibiliMetadataClientPlayerTest {

    private final BilibiliMetadataClient client = new BilibiliMetadataClient(
            new ObjectMapper(), new VideoImportProperties(), new BilibiliAccessProperties());

    @Test
    void parsesTracksViewPointsAndLoginHint() {
        String body = """
                {
                  "code": 0,
                  "message": "0",
                  "data": {
                    "need_login_subtitle": true,
                    "subtitle": {
                      "subtitles": [
                        {
                          "lan": "zh-Hans",
                          "lan_doc": "中文（简体）",
                          "subtitle_url": "//aisubtitle.hdslb.com/bfs/subtitle/c49.json",
                          "ai_type": 0,
                          "ai_status": 0,
                          "is_lock": true
                        },
                        {
                          "lan": "en-US",
                          "lan_doc": "英语（美国）",
                          "subtitle_url": "//aisubtitle.hdslb.com/bfs/subtitle/2b3.json",
                          "ai_type": 0,
                          "ai_status": 0,
                          "is_lock": true
                        }
                      ]
                    },
                    "view_points": [
                      {"content": "开场", "from": 0, "to": 10.5, "type": 1},
                      {"content": "正片", "from": 10.5, "to": 600, "type": 1}
                    ]
                  }
                }
                """;
        BilibiliMetadataClient.PlayerInfo info = client.parsePlayer(body);
        assertTrue(info.needLoginSubtitle());
        assertEquals(2, info.subtitles().size());
        BilibiliMetadataClient.PlayerSubtitle track = info.subtitles().get(0);
        assertEquals("zh-Hans", track.lan());
        assertEquals("//aisubtitle.hdslb.com/bfs/subtitle/c49.json", track.url());
        assertEquals(0, track.aiType());
        assertTrue(track.locked());
        assertEquals(2, info.viewPoints().size());
        assertEquals("开场", info.viewPoints().get(0).content());
        assertEquals(0.0, info.viewPoints().get(0).fromSec());
        assertEquals(10.5, info.viewPoints().get(0).toSec());
        assertEquals(1, info.viewPoints().get(0).type());
    }

    @Test
    void anonymousEmptyDataStillParses() {
        String body = """
                {"code": 0, "message": "0", "data": {
                  "need_login_subtitle": false, "subtitle": {"subtitles": []}, "view_points": []}}
                """;
        BilibiliMetadataClient.PlayerInfo info = client.parsePlayer(body);
        assertTrue(info.subtitles().isEmpty());
        assertTrue(info.viewPoints().isEmpty());
    }

    @Test
    void businessCodePreservedForAdapterTranslation() {
        String body = "{\"code\": -400, \"message\": \"请求错误\", \"data\": null}";
        BilibiliApiException error = assertThrows(BilibiliApiException.class,
                () -> client.parsePlayer(body));
        assertEquals(-400, error.code());
    }

    @Test
    void malformedJsonFails() {
        assertThrows(IllegalStateException.class, () -> client.parsePlayer("not-json"));
    }

    @Test
    void wbiKeysParsedFromNavFixture() throws Exception {
        String body = """
                {"code": 0, "data": {"wbi_img": {
                  "img_url": "https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png",
                  "sub_url": "https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png"}}}
                """;
        String[] keys = client.parseWbiKeys(body);
        assertEquals("7cd084941338484aae1ad9425b84077c", keys[0]);
        assertEquals("4932caff0ff746eab6f01bf08b70ac45", keys[1]);
    }

    @Test
    void wbiKeyUrlExtraction() {
        assertEquals("7cd084941338484aae1ad9425b84077c",
                BilibiliMetadataClient.extractKeyFromUrl(
                        "https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png"));
        assertThrows(IllegalStateException.class,
                () -> BilibiliMetadataClient.extractKeyFromUrl(""));
    }

    @Test
    void navBusinessErrorRejected() {
        assertThrows(IllegalStateException.class, () ->
                client.parseWbiKeys("{\"code\": -101, \"message\": \"未登录\", \"data\": null}"));
    }

    @Test
    void loginStatusParsedFromNavFixture() throws Exception {
        BilibiliMetadataClient.LoginStatus status = client.parseLoginStatus("""
                {"code": 0, "message": "0", "data": {"isLogin": true, "mid": 42258799, "uname": "panda"}}
                """);
        assertTrue(status.loggedIn());
        assertEquals(42258799L, status.mid());
        assertEquals("panda", status.uname());
    }

    @Test
    void notLoggedInCodeMapsToLoggedOut() throws Exception {
        BilibiliMetadataClient.LoginStatus status = client.parseLoginStatus(
                "{\"code\": -101, \"message\": \"账号未登录\", \"data\": {\"isLogin\": false}}");
        assertFalse(status.loggedIn());
    }

    @Test
    void isLoginFalseMapsToLoggedOut() throws Exception {
        BilibiliMetadataClient.LoginStatus status = client.parseLoginStatus(
                "{\"code\": 0, \"message\": \"0\", \"data\": {\"isLogin\": false}}");
        assertFalse(status.loggedIn());
    }
}
