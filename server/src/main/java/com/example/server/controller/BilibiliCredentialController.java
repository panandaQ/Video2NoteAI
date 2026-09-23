package com.example.server.controller;

import com.example.server.common.Result;
import com.example.server.dto.BilibiliCookieRequest;
import com.example.server.service.AuthService;
import com.example.server.service.BilibiliCredentialService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户 B 站 Cookie 的保存与查询入口。
 *
 * <p>只负责校验入参、取得当前用户并调用应用服务；加密、解密与落库细节都在
 * {@link BilibiliCredentialService}。对外只回传保存状态（是否存在 + 更新时间），绝不回传明文。
 */
@RestController
@RequestMapping("/user/bilibili-cookie")
public class BilibiliCredentialController {

    private final BilibiliCredentialService credentialService;

    public BilibiliCredentialController(BilibiliCredentialService credentialService) {
        this.credentialService = credentialService;
    }

    /** 保存（覆盖）当前用户的 Cookie。 */
    @PostMapping
    public Result<Void> save(@Valid @RequestBody BilibiliCookieRequest request,
                             @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        credentialService.save(userId, request.cookie());
        return Result.ok();
    }

    /** 查询保存状态（不回传明文）。 */
    @GetMapping
    public Result<BilibiliCredentialService.Status> status(
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return Result.ok(credentialService.status(userId));
    }

    /** 清除当前用户的 Cookie。 */
    @DeleteMapping
    public Result<Void> clear(@RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        credentialService.clear(userId);
        return Result.ok();
    }
}
