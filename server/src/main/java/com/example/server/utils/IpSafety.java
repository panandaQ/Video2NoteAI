package com.example.server.utils;

import java.net.InetAddress;

/**
 * 出站地址安全判定：拦截不应从服务端访问的地址（SSRF 防护的共享底座）。
 *
 * <p>回环、任意本地、链路本地（含云元数据端点 169.254.169.254）、站点内网（RFC1918）、
 * IPv6 ULA、运营商级 NAT、组播与保留网段。yt-dlp 下载与字幕 CDN 校验共用同一套规则，
 * 禁止两处各自演化（开发规范 §16）。
 */
public final class IpSafety {

    private IpSafety() {
    }

    public static boolean isDisallowedAddress(InetAddress address) {
        if (address.isAnyLocalAddress()          // 0.0.0.0 / ::
                || address.isLoopbackAddress()   // 127.0.0.0/8 / ::1
                || address.isLinkLocalAddress()  // 169.254.0.0/16（含云元数据端点）/ fe80::/10
                || address.isSiteLocalAddress()  // 10/8、172.16/12、192.168/16
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            if (first == 0) return true;                                     // 0.0.0.0/8 “本网络”
            if (first == 100 && second >= 64 && second <= 127) return true;  // 100.64.0.0/10 运营商级 NAT
            if (first == 169 && second == 254) return true;                  // 169.254.0.0/16 兜底
            return first >= 240;                                             // 240.0.0.0/4 保留段
        }
        if (bytes.length == 16) {
            return (bytes[0] & 0xFE) == 0xFC;                                // fc00::/7 IPv6 唯一本地地址(ULA)
        }
        return false;
    }
}
