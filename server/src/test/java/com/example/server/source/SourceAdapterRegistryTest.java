package com.example.server.source;

import com.example.server.dto.VideoImportErrorCode;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 来源扩展边界契约：Registry 按注入顺序选择支持者，无法处理时给出稳定的不可重试错误码。
 *
 * <p>新增平台只新增 Adapter 实现，本测试证明导入侧不依赖任何平台特有类型。
 */
class SourceAdapterRegistryTest {

    private static final URI BILIBILI = URI.create("https://www.bilibili.com/video/BV1xx411c7mD");

    @Test
    void selectsFirstAdapterThatSupportsInput() {
        VideoSourceAdapter unsupported = adapter(uri -> false);
        VideoSourceAdapter bilibili = adapter(uri -> true);

        SourceAdapterRegistry registry = new SourceAdapterRegistry(List.of(unsupported, bilibili));

        assertSame(bilibili, registry.requireAdapter(BILIBILI));
        assertEquals(2, registry.size());
    }

    @Test
    void rejectsInputWithoutAdapterUsingNonRetryableSourceCode() {
        SourceAdapterRegistry registry = new SourceAdapterRegistry(List.of(adapter(uri -> false)));

        VideoSourceException error = assertThrows(VideoSourceException.class,
                () -> registry.requireAdapter(URI.create("https://example.com/video/1")));

        assertEquals(VideoImportErrorCode.SOURCE_UNSUPPORTED, error.errorCode());
        assertFalse(error.retryable());
    }

    @Test
    void rejectsNullInputInsteadOfThrowingNullPointer() {
        SourceAdapterRegistry registry = new SourceAdapterRegistry(List.of(adapter(uri -> true)));

        VideoSourceException error = assertThrows(VideoSourceException.class,
                () -> registry.requireAdapter(null));

        assertEquals(VideoImportErrorCode.SOURCE_UNSUPPORTED, error.errorCode());
    }

    @Test
    void sourceKeyCarriesPlatformNamespaceAndUnitIdentity() {
        VideoSourceUnit unit = new VideoSourceUnit(VideoPlatform.BILIBILI, "UGC_VIDEO",
                "BV1xx411c7mD", "30000001",
                "https://www.bilibili.com/video/BV1xx411c7mD", "标题", "作者", 1_000L, null, 1);

        assertEquals("BILIBILI:UGC_VIDEO:BV1xx411c7mD:30000001", unit.sourceKey());
    }

    private VideoSourceAdapter adapter(Predicate<URI> supports) {
        return new VideoSourceAdapter() {
            @Override
            public boolean supports(URI input) {
                return supports.test(input);
            }

            @Override
            public VideoImportPlan resolve(URI input, String cookie) {
                throw new UnsupportedOperationException("本测试不使用 resolve");
            }

            @Override
            public AcquiredMedia acquire(VideoSourceUnit source, Integer height, String cookie) {
                throw new UnsupportedOperationException("本测试不使用 acquire");
            }
        };
    }
}
