package com.example.server.source;

import com.example.server.dto.VideoImportErrorCode;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;

/**
 * 按 Spring 注入的 Adapter 列表选择来源实现。
 *
 * <p>这是为“增加视频平台”这一明确变化点建立的最小扩展边界：新增平台只新增一个
 * {@link VideoSourceAdapter} 实现与测试，导入 Service、Consumer 和 AI 主链都不修改。
 * 不建设插件中心、不做优先级配置，按注入顺序取第一个支持者。
 */
@Component
public final class SourceAdapterRegistry {

    private final List<VideoSourceAdapter> adapters;

    public SourceAdapterRegistry(List<VideoSourceAdapter> adapters) {
        this.adapters = List.copyOf(adapters);
    }

    /**
     * 选择能够处理该输入的 Adapter。
     *
     * @throws VideoSourceException 没有 Adapter 支持该输入（{@code SOURCE_UNSUPPORTED}，不可重试）
     */
    public VideoSourceAdapter requireAdapter(URI input) {
        if (input == null) {
            throw new VideoSourceException(VideoImportErrorCode.SOURCE_UNSUPPORTED, "缺少视频链接");
        }
        return adapters.stream()
                .filter(adapter -> adapter.supports(input))
                .findFirst()
                .orElseThrow(() -> new VideoSourceException(
                        VideoImportErrorCode.SOURCE_UNSUPPORTED, "当前来源不受支持"));
    }

    /** 已注册的 Adapter 数量，供启动期诊断和测试使用。 */
    public int size() {
        return adapters.size();
    }
}
