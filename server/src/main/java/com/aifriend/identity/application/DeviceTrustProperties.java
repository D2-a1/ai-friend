package com.aifriend.identity.application;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 自用设备公钥白名单配置。
 *
 * <p>白名单只保存 Android Keystore 公钥的 SHA-256 十六进制摘要，不保存 IMEI、MAC、
 * 手机序列号或其他硬件标识。设备数量不设上限；门禁开启时空白名单拒绝启动。</p>
 *
 * @param enabled 是否启用设备放行门禁
 * @param allowedPublicKeySha256 允许的设备公钥 SHA-256 摘要列表
 * @author Codex
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "ai-friend.security.device-trust")
public record DeviceTrustProperties(
        boolean enabled,
        List<String> allowedPublicKeySha256) {

    private static final String SHA256_HEX_PATTERN = "^[0-9a-f]{64}$";

    /**
     * 规范化并校验设备公钥摘要白名单。
     */
    public DeviceTrustProperties {
        List<String> source = allowedPublicKeySha256 == null ? List.of() : allowedPublicKeySha256;
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : source) {
            String digest = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            if (!digest.isEmpty() && !digest.matches(SHA256_HEX_PATTERN)) {
                throw new IllegalArgumentException("设备公钥摘要必须为 64 位小写 SHA-256 十六进制");
            }
            if (!digest.isEmpty()) {
                normalized.add(digest);
            }
        }
        if (enabled && normalized.isEmpty()) {
            throw new IllegalArgumentException("设备白名单开启时至少需要一个公钥摘要");
        }
        allowedPublicKeySha256 = List.copyOf(normalized);
    }

    /**
     * 判断规范化摘要是否在当前白名单中。
     *
     * @param digestHex 64 位小写 SHA-256 十六进制
     * @return 已放行时返回 true
     */
    public boolean isAllowed(String digestHex) {
        return digestHex != null && allowedPublicKeySha256.contains(digestHex);
    }
}
