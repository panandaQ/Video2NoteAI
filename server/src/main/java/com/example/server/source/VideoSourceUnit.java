package com.example.server.source;

/**
 * 一个可以独立获取、处理和问答的可播放单元。
 *
 * <p>四段身份 {@code platform + resourceType + externalResourceId + externalUnitId} 是媒体唯一键的来源；
 * {@code itemOrder} 只是本次解析的顺序快照，不参与唯一键，合集顺序变化不影响身份。
 *
 * <p>{@code coverUrl} 只用于服务端转存：抓到的图片会写进受管对象并成为媒体条目的封面，
 * 平台地址本身不会出现在任何对外响应里（防盗链 + 外链会失效）。
 */
public record VideoSourceUnit(
        VideoPlatform platform,
        String resourceType,
        String externalResourceId,
        String externalUnitId,
        String canonicalUrl,
        String title,
        String author,
        Long durationMs,
        String coverUrl,
        Integer itemOrder
) {
    public String sourceKey() {
        return String.join(":", platform.name(), resourceType,
                externalResourceId, externalUnitId);
    }
}
