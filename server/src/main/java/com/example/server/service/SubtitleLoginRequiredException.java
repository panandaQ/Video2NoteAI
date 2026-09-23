package com.example.server.service;

/**
 * B 站字幕需要登录态但当前 Cookie 失效/未登录，拉不到字幕。
 *
 * <p>这是"登录态问题"，不是"视频无字幕"：VideoContext 构建不降级 ASR，直接失败，
 * 让用户更新 Cookie 后重新导入。与"无字幕走 ASR 兜底"严格区分。
 */
public class SubtitleLoginRequiredException extends IllegalStateException {

    public SubtitleLoginRequiredException(String contentHash) {
        super("B 站字幕需要登录态但 Cookie 已过期或未登录，请更新 Cookie 后重新导入 contentHash="
                + contentHash);
    }
}
