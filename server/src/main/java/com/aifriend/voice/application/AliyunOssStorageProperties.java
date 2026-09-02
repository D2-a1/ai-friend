package com.aifriend.voice.application;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 阿里云 OSS 私有音频存储配置。
 *
 * <p>开关关闭时不创建 OSS 客户端；开启时必须使用中国地域官方 HTTPS
 * Endpoint、独立私有 Bucket、OSS 托管 AES256 加密和服务器侧受限凭据。
 *
 * @param enabled 是否启用生产 OSS 适配器
 * @param region OSS 中国地域标识
 * @param serviceEndpoint 后端读取和删除使用的官方 Endpoint
 * @param uploadEndpoint Android 直传签名使用的公网官方 Endpoint
 * @param bucket 私有音频 Bucket
 * @param accessKeyId 受限 RAM 身份 AccessKey ID
 * @param accessKeySecret 受限 RAM 身份 AccessKey Secret
 * @param securityToken 可选 STS 安全令牌
 * @param connectTimeout 建连超时
 * @param readTimeout 读取超时
 * @author Codex
 * @since 1.0.0
 */
@Validated
@ConfigurationProperties(prefix = "ai-friend.audio.oss")
public record AliyunOssStorageProperties(
        boolean enabled,
        String region,
        URI serviceEndpoint,
        URI uploadEndpoint,
        String bucket,
        String accessKeyId,
        String accessKeySecret,
        String securityToken,
        Duration connectTimeout,
        Duration readTimeout) {

    private static final Pattern REGION_PATTERN =
            Pattern.compile("cn-[a-z0-9-]+");
    private static final Pattern BUCKET_PATTERN =
            Pattern.compile("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]");
    private static final Duration MINIMUM_TIMEOUT =
            Duration.ofMillis(100);
    private static final Duration MAXIMUM_TIMEOUT =
            Duration.ofSeconds(10);

    /**
     * 校验生产 OSS 配置不能被弱化为非官方端点、明文链路或占位凭据。
     */
    public AliyunOssStorageProperties {
        region = Objects.requireNonNullElse(region, "").trim();
        bucket = Objects.requireNonNullElse(bucket, "").trim();
        accessKeyId = Objects.requireNonNullElse(accessKeyId, "").trim();
        accessKeySecret = Objects.requireNonNullElse(accessKeySecret, "").trim();
        securityToken = Objects.requireNonNullElse(securityToken, "").trim();
        if (enabled) {
            if (!REGION_PATTERN.matcher(region).matches()) {
                throw new IllegalArgumentException("OSS 地域必须是中国地域标识");
            }
            if (!BUCKET_PATTERN.matcher(bucket).matches()) {
                throw new IllegalArgumentException("OSS Bucket 名称格式错误");
            }
            rejectMissingOrPlaceholder(accessKeyId, "OSS AccessKey ID");
            rejectMissingOrPlaceholder(accessKeySecret, "OSS AccessKey Secret");
            validateEndpoint(serviceEndpoint, region, true, "OSS 服务端 Endpoint");
            validateEndpoint(uploadEndpoint, region, false, "OSS 直传 Endpoint");
            validateTimeout(connectTimeout, "OSS 建连超时");
            validateTimeout(readTimeout, "OSS 读取超时");
        }
    }

    private static void validateEndpoint(
            URI endpoint,
            String region,
            boolean internalAllowed,
            String label) {
        if (endpoint == null
                || !"https".equalsIgnoreCase(endpoint.getScheme())
                || endpoint.getUserInfo() != null
                || endpoint.getPort() != -1
                || endpoint.getQuery() != null
                || endpoint.getFragment() != null
                || (endpoint.getPath() != null
                    && !endpoint.getPath().isBlank()
                    && !"/".equals(endpoint.getPath()))) {
            throw new IllegalArgumentException(label + " 格式错误");
        }
        String publicHost = "oss-" + region + ".aliyuncs.com";
        String internalHost = "oss-" + region + "-internal.aliyuncs.com";
        String actualHost = endpoint.getHost();
        boolean official = publicHost.equalsIgnoreCase(actualHost)
                || (internalAllowed && internalHost.equalsIgnoreCase(actualHost));
        if (!official) {
            throw new IllegalArgumentException(label + " 必须使用对应地域官方地址");
        }
    }

    private static void validateTimeout(Duration timeout, String label) {
        if (timeout == null
                || timeout.compareTo(MINIMUM_TIMEOUT) < 0
                || timeout.compareTo(MAXIMUM_TIMEOUT) > 0) {
            throw new IllegalArgumentException(label + " 必须在 100 毫秒到 10 秒之间");
        }
    }

    private static void rejectMissingOrPlaceholder(String value, String label) {
        if (value.isBlank() || value.startsWith("REPLACE_")) {
            throw new IllegalArgumentException(label + " 未配置");
        }
    }
}
