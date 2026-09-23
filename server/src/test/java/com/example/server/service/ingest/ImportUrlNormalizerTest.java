package com.example.server.service.ingest;

import com.example.server.common.ErrorCode;
import com.example.server.exception.BusinessException;
import com.example.server.utils.VideoImportKeys;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * URL 同步校验与指纹契约。
 *
 * <p>规范化规则决定 {@code requestHash}：同一视频的不同写法必须收敛到相同指纹，而带 {@code p=N}
 * 的地址必须与不带参数的地址区分开，否则会错误复用同一个父任务。
 */
class ImportUrlNormalizerTest {

    private final ImportUrlNormalizer normalizer = new ImportUrlNormalizer();

    @Test
    void lowersSchemeAndHostAndDropsFragmentAndDefaultPort() {
        assertEquals("https://www.bilibili.com/video/BV1xx411c7mD?p=2",
                normalizer.normalize("  HTTPS://WWW.Bilibili.COM:443/video/BV1xx411c7mD?p=2#comments  "));
        assertEquals("http://www.bilibili.com/video/BV1",
                normalizer.normalize("http://www.bilibili.com:80/video/BV1"));
    }

    @Test
    void keepsExplicitNonDefaultPortAndFullQuery() {
        assertEquals("https://www.bilibili.com:8443/video/BV1?p=3&t=1",
                normalizer.normalize("https://www.bilibili.com:8443/video/BV1?p=3&t=1"));
    }

    @Test
    void dropsUserInfoSoCredentialsNeverReachLogsOrHashes() {
        assertEquals("https://www.bilibili.com/video/BV1",
                normalizer.normalize("https://user:secret@www.bilibili.com/video/BV1"));
    }

    @Test
    void sameContentConvergesToSameRequestHashWhilePParameterStaysDistinct() {
        String canonical = normalizer.normalize("https://www.bilibili.com/video/BV1?p=2");
        String noisy = normalizer.normalize("HTTPS://www.bilibili.com:443/video/BV1?p=2#reply");

        assertEquals(VideoImportKeys.requestHash(canonical), VideoImportKeys.requestHash(noisy));
        assertNotEquals(VideoImportKeys.requestHash(canonical),
                VideoImportKeys.requestHash(normalizer.normalize("https://www.bilibili.com/video/BV1?p=3")));
    }

    @Test
    void activeRequestKeyIsolatesUsers() {
        String requestHash = VideoImportKeys.requestHash("https://www.bilibili.com/video/BV1");

        assertNotEquals(VideoImportKeys.activeRequestKey(1L, requestHash),
                VideoImportKeys.activeRequestKey(2L, requestHash));
        assertEquals(64, VideoImportKeys.activeRequestKey(1L, requestHash).length());
    }

    @Test
    void rejectsUnparseableOrNonHttpInput() {
        assertRejected("   ");
        assertRejected("not a url");
        assertRejected("ftp://www.bilibili.com/video/BV1");
        assertRejected("https:///video/BV1");
    }

    @Test
    void sourceHashHidesRawIdentityFromRedisKeys() {
        String hash = VideoImportKeys.sourceHash("BILIBILI:UGC_VIDEO:BV1xx411c7mD:30000001");

        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"));
        assertEquals(hash, VideoImportKeys.sourceHash("BILIBILI:UGC_VIDEO:BV1xx411c7mD:30000001"));
    }

    private void assertRejected(String url) {
        BusinessException error = assertThrows(BusinessException.class, () -> normalizer.normalize(url));
        // 契约规定 URL 格式错误使用 40000/40001；规范化阶段统一使用 VALIDATION_FAILED。
        assertEquals(ErrorCode.VALIDATION_FAILED, error.errorCode(), "URL: " + url);
    }
}
