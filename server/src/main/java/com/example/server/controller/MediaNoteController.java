package com.example.server.controller;

import com.example.server.common.Result;
import com.example.server.dto.VideoNoteResponse;
import com.example.server.service.AuthService;
import com.example.server.service.ingest.VideoNoteQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 默认视频笔记查询入口。
 *
 * <p>路径 {@code /media/{mediaId}/note} 已由现有 {@code /media/**} 鉴权覆盖。
 * 处理中返回 200 与 {@code note=null}，完成后返回结构化结果与时间戳证据。
 */
@RestController
@RequestMapping("/media")
public class MediaNoteController {

    private final VideoNoteQueryService noteQueryService;

    public MediaNoteController(VideoNoteQueryService noteQueryService) {
        this.noteQueryService = noteQueryService;
    }

    @GetMapping("/{mediaId}/note")
    public Result<VideoNoteResponse> note(
            @PathVariable Long mediaId,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return Result.ok(noteQueryService.note(userId, mediaId));
    }
}
