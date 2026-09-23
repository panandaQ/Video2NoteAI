package com.example.server.service.ingest;

import com.example.server.common.ErrorCode;
import com.example.server.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * 提交 URL 的同步校验与规范化。
 *
 * <p>同步阶段只做“这是不是一个可提交的 HTTP/HTTPS 地址”：不访问平台、不跟随短链接、不判断 BV/av/CID。
 * 规范化结果参与 {@code requestHash}，所以规则必须稳定：
 * <ol>
 *   <li>去除首尾空白；</li>
 *   <li>Scheme 与 Host 转小写；</li>
 *   <li>删除 Fragment；</li>
 *   <li>删除默认端口 80/443；</li>
 *   <li>保留 Path 和完整 Query，不删除 {@code p} 等可能改变语义的参数；</li>
 *   <li>丢弃 userinfo：视频地址不应携带凭据，保留会让凭据进入日志与去重键。</li>
 * </ol>
 */
@Component
public class ImportUrlNormalizer {

    /**
     * 校验并规范化 URL。
     *
     * @throws BusinessException 参数非法（HTTP 400）：无法解析、非 HTTP/HTTPS、缺少 Host
     */
    public String normalize(String rawUrl) {
        String trimmed = rawUrl == null ? "" : rawUrl.trim();
        if (trimmed.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "视频链接不能为空");
        }

        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "视频链接格式不正确");
        }

        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null || host.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "视频链接缺少主机名");
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "仅支持 HTTP 或 HTTPS 视频链接");
        }

        StringBuilder normalized = new StringBuilder(scheme)
                .append("://")
                .append(host.toLowerCase(Locale.ROOT));
        int port = uri.getPort();
        boolean defaultPort = (port == 80 && "http".equals(scheme))
                || (port == 443 && "https".equals(scheme));
        if (port > 0 && !defaultPort) {
            normalized.append(':').append(port);
        }
        String path = uri.getRawPath();
        if (path != null && !path.isEmpty()) {
            normalized.append(path);
        }
        String query = uri.getRawQuery();
        if (query != null && !query.isEmpty()) {
            normalized.append('?').append(query);
        }
        return normalized.toString();
    }

    /** 规范化后的地址必然可解析；供消费者选择 Adapter 使用。 */
    public URI toUri(String normalizedUrl) {
        try {
            return new URI(normalizedUrl);
        } catch (URISyntaxException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "视频链接格式不正确");
        }
    }
}
