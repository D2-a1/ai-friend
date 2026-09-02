package com.aifriend.retention.application;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 腾讯云 COS 删除墓碑独立灾备介质配置。
 *
 * <p>开关关闭时不创建 COS 客户端；开启时只允许使用与广州音频主存储分离的
 * 中国大陆地域、专用私有 Bucket 和受限 CAM 凭据。Bucket 版本控制必须从未开启，
 * 以便禁止同名覆盖请求头可以提供稳定幂等语义。</p>
 *
 * @param enabled 是否启用 COS 删除墓碑导出适配器
 * @param region COS 中国大陆地域标识
 * @param bucket 包含应用编号后缀的完整私有 Bucket 名称
 * @param secretId 受限 CAM 身份 SecretId
 * @param secretKey 受限 CAM 身份 SecretKey
 * @param sessionToken 可选临时安全令牌
 * @param connectTimeout 建连超时
 * @param readTimeout 读取超时
 * @author Codex
 * @since 1.0.0
 */
@Validated
@ConfigurationProperties(prefix = "ai-friend.retention.disaster-recovery.cos")
public record TencentCosDisasterRecoveryProperties(
        boolean enabled,
        String region,
        String bucket,
        String secretId,
        String secretKey,
        String sessionToken,
        Duration connectTimeout,
        Duration readTimeout) {

    private static final Set<String> ALLOWED_REGIONS = Set.of(
            "ap-beijing",
            "ap-shanghai",
            "ap-nanjing",
            "ap-chengdu",
            "ap-chongqing");
    private static final Pattern BUCKET_PATTERN = Pattern.compile(
            "[a-z0-9][a-z0-9-]{1,45}[a-z0-9]-[1-9][0-9]{4,19}");
    private static final Duration MINIMUM_TIMEOUT = Duration.ofMillis(100);
    private static final Duration MAXIMUM_TIMEOUT = Duration.ofSeconds(10);

    /**
     * 校验启用状态下的独立地域、完整 Bucket 名、受限凭据和超时边界。
     */
    public TencentCosDisasterRecoveryProperties {
        region = Objects.requireNonNullElse(region, "").trim();
        bucket = Objects.requireNonNullElse(bucket, "").trim();
        secretId = Objects.requireNonNullElse(secretId, "").trim();
        secretKey = Objects.requireNonNullElse(secretKey, "").trim();
        sessionToken = Objects.requireNonNullElse(sessionToken, "").trim();
        if (enabled) {
            if (!ALLOWED_REGIONS.contains(region)) {
                throw new IllegalArgumentException(
                        "COS 灾备地域必须是非广州的中国大陆地域");
            }
            if (bucket.length() > 60
                    || !BUCKET_PATTERN.matcher(bucket).matches()) {
                throw new IllegalArgumentException("COS 灾备 Bucket 名称格式错误");
            }
            rejectMissingOrPlaceholder(secretId, "COS 灾备 SecretId");
            rejectMissingOrPlaceholder(secretKey, "COS 灾备 SecretKey");
            if (!sessionToken.isBlank()) {
                rejectMissingOrPlaceholder(sessionToken, "COS 灾备临时安全令牌");
            }
            validateTimeout(connectTimeout, "COS 灾备建连超时");
            validateTimeout(readTimeout, "COS 灾备读取超时");
        }
    }

    /**
     * 返回不包含 Bucket、凭据和临时令牌的配置描述。
     *
     * @return 脱敏配置描述
     */
    @Override
    public String toString() {
        return "TencentCosDisasterRecoveryProperties[enabled=" + enabled
                + ", region=" + region
                + ", bucket=***"
                + ", secretId=***"
                + ", secretKey=***"
                + ", sessionToken=***"
                + ", connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout + "]";
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
