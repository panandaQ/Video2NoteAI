package com.example.server.infrastructure;

import java.util.List;

/**
 * B 站稿件元数据（平台 web API 的领域化结果）。
 *
 * @param bvid      归一后的稿件 ID；av 输入也由平台归一为 BVID
 * @param aid       数字稿件 ID，仅用于诊断
 * @param title     稿件标题
 * @param author    作者昵称，可能为空
 * @param coverUrl  平台封面地址，可能为空；需要登录或防盗链，只能由服务端抓取后转存，不能直接给浏览器
 * @param pages     分 P 列表；每个元素是一个可播放单元，CID 就在其中
 */
public record BilibiliVideoMetadata(
        String bvid,
        long aid,
        String title,
        String author,
        String coverUrl,
        Long durationMs,
        List<Page> pages
) {
    public BilibiliVideoMetadata {
        pages = pages == null ? List.of() : List.copyOf(pages);
    }

    /**
     * 一个分 P，也就是契约里的可播放单元。
     *
     * @param cid 平台单元 ID，进入媒体唯一键；**允许为空**（平台未返回时保留为空，
     *            由来源 Adapter 判定整单失败），任何情况下都不得用 BVID 或序号顶替
     */
    public record Page(
            String cid,
            int page,
            String part,
            Long durationMs
    ) {
    }
}
