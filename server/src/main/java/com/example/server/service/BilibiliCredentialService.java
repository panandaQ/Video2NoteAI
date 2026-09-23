package com.example.server.service;

import com.example.server.common.ErrorCode;
import com.example.server.entity.UserBilibiliCredential;
import com.example.server.exception.BusinessException;
import com.example.server.infrastructure.CookieCrypto;
import com.example.server.infrastructure.CookieFileParser;
import com.example.server.mapper.UserBilibiliCredentialMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 用户级 B 站 Cookie 的保存、读取与清除。
 *
 * <p>Cookie 是登录凭据：明文只在内存中出现，落库前经 {@link CookieCrypto} 加密；任何路径都不打印
 * 值、键名或密文，日志只记录 {@code hasCookie} 与 {@code userId}。保存即覆盖（每用户至多一条）。
 *
 * <p>未配置加密密钥时：{@link #save} 抛明确错误（不静默明文落库），{@link #getCookie} 返回空（下游回退
 * 到全局 {@code cookie-file} 或匿名），不阻断启动。
 */
@Service
public class BilibiliCredentialService {

    private static final Logger log = LoggerFactory.getLogger(BilibiliCredentialService.class);
    private static final int MAX_COOKIE_LENGTH = 8192;

    private final UserBilibiliCredentialMapper credentialMapper;
    private final CookieCrypto cookieCrypto;

    public BilibiliCredentialService(UserBilibiliCredentialMapper credentialMapper,
                                     CookieCrypto cookieCrypto) {
        this.credentialMapper = credentialMapper;
        this.cookieCrypto = cookieCrypto;
    }

    /** 保存（覆盖）当前用户的 Cookie。 */
    public void save(Long userId, String cookie) {
        String normalized = normalize(cookie);
        if (!cookieCrypto.isAvailable()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                    "服务端未配置 Cookie 加密密钥，无法保存");
        }
        String encrypted = cookieCrypto.encrypt(normalized);
        UserBilibiliCredential existing = credentialMapper.selectById(userId);
        if (existing == null) {
            UserBilibiliCredential credential = new UserBilibiliCredential();
            credential.setUserId(userId);
            credential.setCookieEncrypted(encrypted);
            credentialMapper.insert(credential);
        } else {
            existing.setCookieEncrypted(encrypted);
            credentialMapper.updateById(existing);
        }
        log.info("bilibili_cookie_saved userId={}", userId);
    }

    /** 读取并解密当前用户的 Cookie；未保存、未配置密钥或解密失败返回 {@code null}。 */
    public String getCookie(Long userId) {
        if (userId == null) {
            return null;
        }
        UserBilibiliCredential credential = credentialMapper.selectById(userId);
        if (credential == null) {
            return null;
        }
        return cookieCrypto.decrypt(credential.getCookieEncrypted());
    }

    /** 清除当前用户的 Cookie。 */
    public void clear(Long userId) {
        credentialMapper.deleteById(userId);
        log.info("bilibili_cookie_cleared userId={}", userId);
    }

    /** 当前用户是否已保存 Cookie（不含有效性，仅判断是否存在记录）。 */
    public boolean hasCookie(Long userId) {
        return userId != null && credentialMapper.selectById(userId) != null;
    }

    /** 当前用户的保存状态；只回传是否存在与更新时间，绝不回传明文。 */
    public Status status(Long userId) {
        UserBilibiliCredential credential = credentialMapper.selectById(userId);
        return new Status(credential != null, credential == null ? null : credential.getUpdatedAt());
    }

    /** 保存状态快照（对外只含非敏感字段）。 */
    public record Status(boolean hasCookie, LocalDateTime updatedAt) {
    }

    /** 基本校验并归一化：非空、长度受限，并把原始头串或 cookies.txt 统一成 {@code k=v; k2=v2} 头串。 */
    private String normalize(String cookie) {
        if (cookie == null || cookie.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "Cookie 不能为空");
        }
        String trimmed = cookie.trim();
        if (trimmed.length() > MAX_COOKIE_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "Cookie 过长，最多 " + MAX_COOKIE_LENGTH + " 个字符");
        }
        Map<String, String> parsed = CookieFileParser.parseAny(trimmed);
        if (parsed.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "Cookie 格式无法识别：请粘贴 key=value 串（SESSDATA=...; bili_jct=...）或 cookies.txt 全文");
        }
        return CookieFileParser.toCookieHeader(parsed);
    }
}
