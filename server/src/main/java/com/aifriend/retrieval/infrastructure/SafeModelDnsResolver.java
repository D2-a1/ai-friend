package com.aifriend.retrieval.infrastructure;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

import org.apache.http.conn.DnsResolver;

/**
 * 在连接操作中解析并直接返回经校验IP，避免预检和连接分开解析。
 * TLS仍使用原始主机名验证；拒绝混合公网/私网回答。
 * @author codex
 * @since 1.0.0
 */
public final class SafeModelDnsResolver implements DnsResolver {
    private final Set<String> allowedHosts;
    private final DnsResolver delegate;

    /**
     * 使用系统DNS进行连接级解析。
     * @param allowedHosts 经配置校验的精确ASCII主机
     */
    public SafeModelDnsResolver(Set<String> allowedHosts) {
        this(allowedHosts, InetAddress::getAllByName);
    }

    SafeModelDnsResolver(Set<String> allowedHosts, DnsResolver delegate) {
        this.allowedHosts = Set.copyOf(allowedHosts);
        this.delegate = java.util.Objects.requireNonNull(delegate, "delegate");
    }

    /** {@inheritDoc} */
    @Override public InetAddress[] resolve(String host) throws UnknownHostException {
        if (host == null || !allowedHosts.contains(host.toLowerCase(Locale.ROOT))) {
            throw new UnknownHostException("ENDPOINT_REJECTED");
        }
        InetAddress[] addresses = delegate.resolve(host);
        if (addresses == null || addresses.length == 0 || addresses.length > 16) {
            throw new UnknownHostException("ENDPOINT_REJECTED");
        }
        for (var address : addresses) {
            if (address == null || !isPublic(address)) {
                throw new UnknownHostException("ENDPOINT_REJECTED");
            }
        }
        return addresses.clone();
    }

    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] raw = address.getAddress();
        int a = raw[0] & 255;
        int b = raw[1] & 255;
        if (raw.length == 4) {
            int c = raw[2] & 255;
            return a != 0 && a != 10 && a != 127 && a < 224
                    && !(a == 100 && b >= 64 && b <= 127)
                    && !(a == 169 && b == 254) && !(a == 172 && b >= 16 && b <= 31)
                    && !(a == 192 && (b == 168 || (b == 0 && (c == 0 || c == 2)) || (b == 88 && c == 99)))
                    && !(a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100)))
                    && !(a == 203 && b == 0 && c == 113);
        }
        // 只接纳全球单播2000::/3，并拒绝特殊协议、文档及6to4映射范围。
        int c = raw[2] & 255;
        int d = raw[3] & 255;
        return raw.length == 16 && (a & 0xe0) == 0x20
                && !(a == 0x20 && b == 0x01 && c < 2)
                && !(a == 0x20 && b == 0x01 && c == 0x0d && d == 0xb8)
                && !(a == 0x20 && b == 0x02)
                && !(a == 0x3f && b == 0xff && (c & 0xf0) == 0);
    }
}
