package com.aifriend.retention.infrastructure;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 腾讯云 COS 三身份只读预检参数。
 *
 * <p>三套 CAM 身份必须相互独立。全部凭据只允许由本机交互脚本瞬时注入当前进程，
 * 字符串表示始终隐藏 Bucket、身份和凭据。</p>
 *
 * @param region 非广州的中国大陆 COS 地域
 * @param bucket 完整私有 Bucket 名称
 * @param publisherSecretId 快照发布 CAM SecretId
 * @param publisherSecretKey 快照发布 CAM SecretKey
 * @param publisherSessionToken 快照发布 CAM 可选临时令牌
 * @param exportSecretId 日常导出 CAM SecretId
 * @param exportSecretKey 日常导出 CAM SecretKey
 * @param exportSessionToken 日常导出 CAM 可选临时令牌
 * @param restoreSecretId 恢复 CAM SecretId
 * @param restoreSecretKey 恢复 CAM SecretKey
 * @param restoreSessionToken 恢复 CAM 可选临时令牌
 * @param connectTimeout 建连超时
 * @param readTimeout 读取超时
 * @author codex
 * @since 1.0.0
 */
public record TencentCosDisasterRecoveryPreflightOptions(
        String region,
        String bucket,
        String publisherSecretId,
        String publisherSecretKey,
        String publisherSessionToken,
        String exportSecretId,
        String exportSecretKey,
        String exportSessionToken,
        String restoreSecretId,
        String restoreSecretKey,
        String restoreSessionToken,
        Duration connectTimeout,
        Duration readTimeout) {

    /** 允许使用的非广州中国大陆 COS 地域。 */
    private static final Set<String> ALLOWED_REGIONS = Set.of(
            "ap-beijing",
            "ap-shanghai",
            "ap-nanjing",
            "ap-chengdu",
            "ap-chongqing");
    /** 包含 APPID 后缀的完整 COS Bucket 名称格式。 */
    private static final Pattern BUCKET_PATTERN = Pattern.compile(
            "(?=.{1,60}$)[a-z0-9](?:[a-z0-9-]*[a-z0-9])?-[1-9][0-9]{4,19}");

    /** 规范化并校验全部只读预检参数。 */
    public TencentCosDisasterRecoveryPreflightOptions {
        region = normalized(region);
        bucket = normalized(bucket);
        publisherSecretId = normalized(publisherSecretId);
        publisherSecretKey = normalized(publisherSecretKey);
        publisherSessionToken = normalized(publisherSessionToken);
        exportSecretId = normalized(exportSecretId);
        exportSecretKey = normalized(exportSecretKey);
        exportSessionToken = normalized(exportSessionToken);
        restoreSecretId = normalized(restoreSecretId);
        restoreSecretKey = normalized(restoreSecretKey);
        restoreSessionToken = normalized(restoreSessionToken);
        if (!ALLOWED_REGIONS.contains(region)) {
            throw new IllegalArgumentException("COS 灾备预检地域必须是非广州的中国大陆地域");
        }
        if (!BUCKET_PATTERN.matcher(bucket).matches()) {
            throw new IllegalArgumentException("COS 灾备预检 Bucket 名称无效");
        }
        rejectMissingOrPlaceholder(publisherSecretId, "COS 快照发布 SecretId");
        rejectMissingOrPlaceholder(publisherSecretKey, "COS 快照发布 SecretKey");
        rejectOptionalPlaceholder(publisherSessionToken, "COS 快照发布临时安全令牌");
        rejectMissingOrPlaceholder(exportSecretId, "COS 日常导出 SecretId");
        rejectMissingOrPlaceholder(exportSecretKey, "COS 日常导出 SecretKey");
        rejectOptionalPlaceholder(exportSessionToken, "COS 日常导出临时安全令牌");
        rejectMissingOrPlaceholder(restoreSecretId, "COS 恢复 SecretId");
        rejectMissingOrPlaceholder(restoreSecretKey, "COS 恢复 SecretKey");
        rejectOptionalPlaceholder(restoreSessionToken, "COS 恢复临时安全令牌");
        if (publisherSecretId.equals(exportSecretId)
                || publisherSecretId.equals(restoreSecretId)
                || exportSecretId.equals(restoreSecretId)) {
            throw new IllegalArgumentException("COS 导出、恢复与快照发布身份必须相互独立");
        }
        validateTimeout(connectTimeout, "COS 灾备预检建连超时");
        validateTimeout(readTimeout, "COS 灾备预检读取超时");
    }

    /**
     * 返回不包含 Bucket、身份和凭据的脱敏描述。
     *
     * @return 脱敏参数描述
     */
    @Override
    public String toString() {
        return "TencentCosDisasterRecoveryPreflightOptions[region=" + region
                + ", bucket=***, publisherSecretId=***, publisherSecretKey=***"
                + ", publisherSessionToken=***, exportSecretId=***"
                + ", exportSecretKey=***, exportSessionToken=***"
                + ", restoreSecretId=***, restoreSecretKey=***"
                + ", restoreSessionToken=***, connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout + "]";
    }

    private static void validateTimeout(Duration timeout, String name) {
        if (timeout == null
                || timeout.compareTo(Duration.ofMillis(100)) < 0
                || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException(name + "必须在 100 毫秒到 30 秒之间");
        }
    }

    private static void rejectOptionalPlaceholder(String value, String name) {
        if (!value.isBlank()) {
            rejectMissingOrPlaceholder(value, name);
        }
    }

    private static void rejectMissingOrPlaceholder(String value, String name) {
        String upper = value.toUpperCase(Locale.ROOT);
        if (value.isBlank() || upper.contains("REPLACE") || upper.contains("CHANGEME")) {
            throw new IllegalArgumentException(name + "未配置");
        }
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
