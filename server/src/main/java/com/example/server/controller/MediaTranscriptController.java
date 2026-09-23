package com.example.server.controller;

import com.example.server.common.Result;
import com.example.server.dto.MediaTranscriptResponse;
import com.example.server.service.AuthService;
import com.example.server.service.MediaTranscriptQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 单视频工作台的完整字幕读取入口。 */
@RestController
@RequestMapping("/media")
public class MediaTranscriptController {

    private final MediaTranscriptQueryService transcriptQueryService;

    public MediaTranscriptController(MediaTranscriptQueryService transcriptQueryService) {
        this.transcriptQueryService = transcriptQueryService;
    }

    @GetMapping("/{mediaId}/transcript")
    public Result<MediaTranscriptResponse> transcript(
            @PathVariable Long mediaId,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return Result.ok(transcriptQueryService.transcript(userId, mediaId));
    }
}
