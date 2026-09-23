package com.example.server.infrastructure;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WBI 签名契约（计划 §4.3 / §7.1）：固定 fixture 的 mixin key 推导、过滤、排序、编码与
 * 签名结果逐字节稳定。
 */
class BilibiliWbiSignerTest {

    /** 64 字符合成 imgKey：位置 0-9 为数字、10-35 为小写字母、36-61 为大写字母、62-63 为 01。 */
    private static final String IMG_KEY =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ01";

    @Test
    void derivesMixinKeyByPublicIndexTable() {
        String mixinKey = BilibiliWbiSigner.deriveMixinKey(IMG_KEY, "z".repeat(64));
        assertEquals(64, mixinKey.length());
        // 索引表前 10 项：46,47,18,2,53,8,23,32,15,50 → K,L,i,2,R,8,n,w,f,O
        assertEquals("KLi2R8nwfO", mixinKey.substring(0, 10));
        // 尾部抽样：索引 59=36('A')、60=20('k')、61=34('y')、62=44('I')、63=52('Q')
        assertEquals("AkyIQ", mixinKey.substring(59));
    }

    @Test
    void shortKeysRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                BilibiliWbiSigner.deriveMixinKey("short", "shorter"));
    }

    @Test
    void signatureMatchesPinnedVector() {
        // 查询串 "bvid=BV1LtY968EcB&cid=137649199" + mixinKey 的 MD5，固定向量防回归
        Map<String, String> params = new LinkedHashMap<>();
        params.put("bvid", "BV1LtY968EcB");
        params.put("cid", "137649199");
        assertEquals("869e27e31fbbd4f72731b3cda594faee",
                BilibiliWbiSigner.sign(params, "testMixinKey123"));
    }

    @Test
    void wridAndWtsAreExcludedFromSignature() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("bvid", "BV1LtY968EcB");
        params.put("cid", "137649199");
        params.put("w_rid", "ignored");
        params.put("wts", "9999999999");
        assertEquals("869e27e31fbbd4f72731b3cda594faee",
                BilibiliWbiSigner.sign(params, "testMixinKey123"));
    }

    @Test
    void specialCharsFilteredBeforeEncoding() {
        assertEquals("abcdef", BilibiliWbiSigner.filterChars("a!b'c(d)e*f"));
        Map<String, String> plain = Map.of("bvid", "BV1LtY968EcB", "cid", "137649199");
        Map<String, String> noisy = Map.of("bvid", "BV1L!t'Y(96)8E*cB", "cid", "137649199");
        assertEquals(BilibiliWbiSigner.sign(plain, "testMixinKey123"),
                BilibiliWbiSigner.sign(noisy, "testMixinKey123"));
    }

    @Test
    void paramsAreSortedAndSignatureIsDeterministic() {
        Map<String, String> unsorted = Map.of("cid", "137649199", "bvid", "BV1LtY968EcB");
        String first = BilibiliWbiSigner.sign(unsorted, "testMixinKey123");
        String second = BilibiliWbiSigner.sign(unsorted, "testMixinKey123");
        assertEquals(first, second);
        assertNotEquals(first, BilibiliWbiSigner.sign(unsorted, "anotherMixinKey"));
        assertTrue(first.matches("[a-f0-9]{32}"));
    }
}
