package com.aifriend.retention.application;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 腾讯云 COS 删除墓碑可信恢复源配置。
 *
 * <p>恢复读取身份必须与日常导出身份分离，只授予目标私有 Bucket 的版本控制查询和
 * 确定对象读取权限。Ed25519 私钥不得进入服务端，服务端只持有离线签名公钥。</p>
 *
 * @param enabled 是否启用 COS 可信恢复源
 * @param region COS 中国大陆灾备地域标识
 * @param bucket 包含应用编号后缀的完整私有 Bucket 名称
 * @param secretId 只读 CAM 身份 SecretId
 * @param secretKey 只读 CAM 身份 SecretKey
 * @param sessionToken 可选临时安全令牌
 * @param manifestPublicKeyBase64 Ed25519 X.509 公钥标准 Base64
 * @param connectTimeout 建连超时
 * @param readTimeout 读取超时
 * @author Codex
 * @since 1.0.0
 */
@Validated
@ConfigurationProperties(prefix = "ai-friend.retention.disaster-recovery.cos-restore")
public record TencentCosDisasterRecoveryRestoreProperties(
        boolean enabled,
        String region,
        String bucket,
        String secretId,
        String secretKey,
        String sessionToken,
        String manifestPublicKeyBase64,
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
     * 校验启用状态下的独立只读身份、签名公钥和网络超时边界。
     */
    public TencentCosDisasterRecoveryRestoreProperties {
        region = Objects.requireNonNullElse(region, "").trim();
        bucket = Objects.requireNonNullElse(bucket, "").trim();
        secretId = Objects.requireNonNullElse(secretId, "").trim();
        secretKey = Objects.requireNonNullElse(secretKey, "").trim();
        sessionToken = Objects.requireNonNullElse(sessionToken, "").trim();
        manifestPublicKeyBase64 = Objects.requireNonNullElse(
                manifestPublicKeyBase64, "").trim();
        if (enabled) {
            if (!ALLOWED_REGIONS.contains(region)) {
                throw new IllegalArgumentException(
                        "COS 恢复地域必须是非广州的中国大陆地域");
            }
            if (bucket.length() > 60 || !BUCKET_PATTERN.matcher(bucket).matches()) {
                throw new IllegalArgumentException("COS 恢复 Bucket 名称格式错误");
            }
            rejectMissingOrPlaceholder(secretId, "COS 恢复 SecretId");
            rejectMissingOrPlaceholder(secretKey, "COS 恢复 SecretKey");
            if (!sessionToken.isBlank()) {
                rejectMissingOrPlaceholder(sessionToken, "COS 恢复临时安全令牌");
            }
            decodePublicKey(manifestPublicKeyBase64);
            validateTimeout(connectTimeout, "COS 恢复建连超时");
            validateTimeout(readTimeout, "COS 恢复读取超时");
        }
    }

    /**
     * 解析只用于快照清单认证的 Ed25519 公钥。
     *
     * @return Ed25519 公钥
     * @throws IllegalStateException 恢复源未启用时抛出
     */
    public PublicKey requireManifestPublicKey() {
        if (!enabled) {
            throw new IllegalStateException("COS 可信恢复源未启用");
        }
        return decodePublicKey(manifestPublicKeyBase64);
    }

    /**
     * 返回不包含 Bucket、凭据、令牌和公钥内容的配置描述。
     *
     * @return 脱敏配置描述
     */
    @Override
    public String toString() {
        return "TencentCosDisasterRecoveryRestoreProperties[enabled=" + enabled
                + ", region=" + region
                + ", bucket=***"
                + ", secretId=***"
                + ", secretKey=***"
                + ", sessionToken=***"
                + ", manifestPublicKeyBase64=***"
                + ", connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout + "]";
    }

    private static PublicKey decodePublicKey(String encodedKey) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(encodedKey);
            if (keyBytes.length < 32 || keyBytes.length > 128) {
                throw new IllegalArgumentException("COS 恢复清单公钥无效");
            }
            return KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(keyBytes));
        } catch (IllegalArgumentException | GeneralSecurityException exception) {
            throw new IllegalArgumentException("COS 恢复清单公钥无效", exception);
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
