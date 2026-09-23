package com.example.server.controller;

import com.example.server.common.Result;
import com.example.server.dto.VideoImportRequest;
import com.example.server.dto.VideoImportSubmissionResponse;
import com.example.server.service.AuthService;
import com.example.server.service.ingest.ImportJobService;
import com.example.server.utils.ClientIpUtils;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 在线视频导入入口。
 *
 * <p>只负责接收原始 URL、触发 Bean Validation 和调用一次应用服务。请求线程不访问视频平台、不跟随短链接、
 * 不运行 yt-dlp、不解析 BV/av/CID；平台身份一律由异步 Adapter 生成。
 */
@RestController
@RequestMapping("/videos")
public class VideoImportController {

    private final ImportJobService importJobService;

    public VideoImportController(ImportJobService importJobService) {
        this.importJobService = importJobService;
    }

    /** 受理成功返回 202 与 importId；重复提交返回同一活跃任务，{@code reused=true}。 */
    @PostMapping("/import")
    public ResponseEntity<Result<VideoImportSubmissionResponse>> createImport(
            @Valid @RequestBody VideoImportRequest request,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId,
            @RequestAttribute(value = ClientIpUtils.REQUEST_CLIENT_IP, required = false) String clientIp) {
        VideoImportSubmissionResponse body = importJobService.submit(userId, request.url(), request.quality(), clientIp);
        return ResponseEntity.accepted().body(Result.ok(body));
    }
}
