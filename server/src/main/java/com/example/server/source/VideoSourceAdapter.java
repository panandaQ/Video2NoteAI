package com.example.server.source;

import java.net.URI;

/**
 * 视频来源接入边界。
 *
 * <p>实现类负责把一个原始 URL 变成平台无关的可播放单元，并获取单元媒体。接口不得返回
 * {@code Process}、OkHttp {@code Response}、MinIO SDK 类型或第三方 JSON；实现类不得依赖
 * MQ、Redis、Qdrant 或 Agent。
 *
 * <p>调用约束：
 * <ul>
 *   <li>{@code supports} 只判断“是否可能由本 Adapter 处理”，不做网络访问；</li>
 *   <li>{@code resolve} 只探测元数据，必须在异步消费者中执行，禁止在 Controller 请求线程访问平台；</li>
 *   <li>{@code acquire} 只获取一个明确 Unit，同样只能在异步消费者中执行。</li>
 * </ul>
 */
public interface VideoSourceAdapter {

    boolean supports(URI input);

    /**
     * 解析原始 URL 为平台无关的可播放单元。
     *
     * @param cookie 可选用户级 Cookie 头串；可空 = 回退全局/匿名
     */
    VideoImportPlan resolve(URI input, String cookie);

    /** 匿名解析入口：等价于 {@code resolve(input, null)}。 */
    default VideoImportPlan resolve(URI input) {
        return resolve(input, null);
    }

    /**
     * 获取一个明确 Unit 的媒体。
     *
     * @param height 可选目标高度（像素）；可空 = 默认画质
     * @param cookie 可选用户级 Cookie 头串；可空 = 回退全局/匿名
     */
    AcquiredMedia acquire(VideoSourceUnit source, Integer height, String cookie);

    /** 默认画质、匿名获取入口：等价于 {@code acquire(source, null, null)}。 */
    default AcquiredMedia acquire(VideoSourceUnit source) {
        return acquire(source, null, null);
    }

    /**
     * 校验用户级 Cookie 是否仍有效（平台登录态）。
     *
     * <p>强制登录的实现点：B 站 Adapter 用 nav 接口校验登录态；非登录制平台返回 {@code true} 不校验。
     */
    default boolean isCookieValid(String cookie) {
        return true;
    }

    /**
     * 抓取封面内容，交给调用方转存为受管对象。
     *
     * <p>为什么由 Adapter 承担：封面地址是平台概念（B 站图片必须带 Referer 才不返回 403），
     * 平台相关的请求头不能泄漏到导入编排层。默认实现返回 {@code null}，表示该平台没有封面能力。
     *
     * <p>封面是展示增强而不是导入结果：任何失败都只能降级为"没有封面"，绝不能让它影响媒体入库。
     *
     * @param coverUrl 平台封面地址，可能为 {@code null}
     * @return 封面字节与内容类型；不可用时返回 {@code null}
     */
    default AcquiredCover fetchCover(String coverUrl) {
        return null;
    }

    /** 抓取到的封面内容。 */
    record AcquiredCover(byte[] bytes, String contentType, String suffix) {
    }
}
