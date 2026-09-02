package com.aifriend.retention.infrastructure;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 腾讯云 COS 删除墓碑离线快照发布参数。
 *
 * <p>发布身份必须与运行时导出、恢复身份都不同。Ed25519 私钥只允许由本机交互脚本
 * 瞬时注入当前子进程；该参数对象的字符串表示不会暴露身份、令牌或密钥。</p>
 *
 * @param region 非广州的中国大陆 COS 地域
 * @param bucket 完整私有 Bucket 名称
 * @param publisherSecretId 快照发布专用 CAM SecretId
 * @param publisherSecretKey 快照发布专用 CAM SecretKey
 * @param publisherSessionToken 可选临时安全令牌
 * @param exportSecretId 运行时导出 CAM SecretId，仅用于身份分离校验
 * @param restoreSecretId 运行时恢复 CAM SecretId，仅用于身份分离校验
 * @param snapshotId 不可复用的快照编号
 * @param createdAt 固定的快照生成时间
 * @param pageSize 每个索引页的墓碑数量
 * @param privateKeyPkcs8Base64 Ed25519 PKCS#8 私钥标准 Base64
 * @param publicKeyX509Base64 Ed25519 X.509 公钥标准 Base64
 * @param connectTimeout 建连超时
 * @param readTimeout 读取超时
 * @author codex
 * @since 1.0.0
 */
public record TencentCosSnapshotPublisherOptions(
        String region,
        String bucket,
        String publisherSecretId,
        String publisherSecretKey,
        String publisherSessionToken,
        String exportSecretId,
        String restoreSecretId,
        String snapshotId,
        Instant createdAt,
        int pageSize,
        String privateKeyPkcs8Base64,
        String publicKeyX509Base64,
        Duration connectTimeout,
        Duration readTimeout) {

    private static final Set<String> ALLOWED_REGIONS = Set.of(
            "ap-beijing",
            "ap-shanghai",
            "ap-nanjing",
            "ap-chengdu",
            "ap-chongqing");
    private static final Pattern BUCKET_PATTERN = Pattern.compile(
            "(?=.{1,60}$)[a-z0-9](?:[a-z0-9-]*[a-z0-9])?-[1-9][0-9]{4,19}");
    private static final Pattern SNAPSHOT_ID_PATTERN = Pattern.compile(
            "[A-Za-z0-9._:-]{8,128}");
    private static final int MAXIMUM_PAGE_SIZE = 100;
    private static final byte[] KEY_PAIR_CHALLENGE =
            "AIFDRK01".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    /**
     * 规范化并校验全部离线发布参数。
     */
    public TencentCosSnapshotPublisherOptions {
        region = normalized(region);
        bucket = normalized(bucket);
        publisherSecretId = normalized(publisherSecretId);
        publisherSecretKey = normalized(publisherSecretKey);
        publisherSessionToken = normalized(publisherSessionToken);
        exportSecretId = normalized(exportSecretId);
        restoreSecretId = normalized(restoreSecretId);
        snapshotId = normalized(snapshotId);
        privateKeyPkcs8Base64 = normalized(privateKeyPkcs8Base64);
        publicKeyX509Base64 = normalized(publicKeyX509Base64);
        if (!ALLOWED_REGIONS.contains(region)) {
            throw new IllegalArgumentException("COS 快照发布地域必须是非广州的中国大陆地域");
        }
        if (!BUCKET_PATTERN.matcher(bucket).matches()) {
            throw new IllegalArgumentException("COS 快照发布 Bucket 名称无效");
        }
        rejectMissingOrPlaceholder(publisherSecretId, "COS 快照发布 SecretId");
        rejectMissingOrPlaceholder(publisherSecretKey, "COS 快照发布 SecretKey");
        rejectMissingOrPlaceholder(exportSecretId, "COS 日常导出 SecretId");
        rejectMissingOrPlaceholder(restoreSecretId, "COS 恢复 SecretId");
        if (!publisherSessionToken.isBlank()) {
            rejectMissingOrPlaceholder(publisherSessionToken, "COS 快照发布临时安全令牌");
        }
        if (publisherSecretId.equals(exportSecretId)
                || publisherSecretId.equals(restoreSecretId)
                || exportSecretId.equals(restoreSecretId)) {
            throw new IllegalArgumentException("COS 导出、恢复与快照发布身份必须相互独立");
        }
        if (!SNAPSHOT_ID_PATTERN.matcher(snapshotId).matches()) {
            throw new IllegalArgumentException("COS 快照编号无效");
        }
        Objects.requireNonNull(createdAt, "COS 快照生成时间不能为空");
        if (pageSize < 1 || pageSize > MAXIMUM_PAGE_SIZE) {
            throw new IllegalArgumentException("COS 快照页大小必须在 1 到 100 之间");
        }
        validateKeyPair(privateKeyPkcs8Base64, publicKeyX509Base64);
        validateTimeout(connectTimeout, "COS 快照发布建连超时");
        validateTimeout(readTimeout, "COS 快照发布读取超时");
    }

    /**
     * 解析只用于当前离线发布进程的 Ed25519 私钥。
     *
     * @return Ed25519 私钥
     */
    public PrivateKey requirePrivateKey() {
        return decodePrivateKey(privateKeyPkcs8Base64);
    }

    /**
     * 解析与恢复服务器配置一致的 Ed25519 公钥。
     *
     * @return Ed25519 公钥
     */
    public PublicKey requirePublicKey() {
        return decodePublicKey(publicKeyX509Base64);
    }

    /**
     * 返回不包含身份、令牌和签名材料的脱敏描述。
     *
     * @return 脱敏配置描述
     */
    @Override
    public String toString() {
        return "TencentCosSnapshotPublisherOptions[region=" + region
                + ", bucket=***, publisherSecretId=***, publisherSecretKey=***"
                + ", publisherSessionToken=***, exportSecretId=***, restoreSecretId=***"
                + ", snapshotId=" + snapshotId + ", createdAt=" + createdAt
                + ", pageSize=" + pageSize + ", privateKeyPkcs8Base64=***"
                + ", publicKeyX509Base64=***, connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout + "]";
    }

    private static PrivateKey decodePrivateKey(String encodedKey) {
        byte[] decoded = decodeBase64(encodedKey, "COS 快照 Ed25519 私钥无效");
        try {
            return KeyFactory.getInstance("Ed25519")
                    .generatePrivate(new PKCS8EncodedKeySpec(decoded));
        } catch (GeneralSecurityException exception) {
            throw new IllegalArgumentException("COS 快照 Ed25519 私钥无效", exception);
        } finally {
            java.util.Arrays.fill(decoded, (byte) 0);
        }
    }

    private static PublicKey decodePublicKey(String encodedKey) {
        byte[] decoded = decodeBase64(encodedKey, "COS 快照 Ed25519 公钥无效");
        try {
            return KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(decoded));
        } catch (GeneralSecurityException exception) {
            throw new IllegalArgumentException("COS 快照 Ed25519 公钥无效", exception);
        } finally {
            java.util.Arrays.fill(decoded, (byte) 0);
        }
    }

    private static void validateKeyPair(String privateKey, String publicKey) {
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(decodePrivateKey(privateKey));
            signer.update(KEY_PAIR_CHALLENGE);
            byte[] signature = signer.sign();
            try {
                Signature verifier = Signature.getInstance("Ed25519");
                verifier.initVerify(decodePublicKey(publicKey));
                verifier.update(KEY_PAIR_CHALLENGE);
                if (!verifier.verify(signature)) {
                    throw new IllegalArgumentException("COS 快照 Ed25519 密钥对不匹配");
                }
            } finally {
                java.util.Arrays.fill(signature, (byte) 0);
            }
        } catch (GeneralSecurityException exception) {
            throw new IllegalArgumentException("COS 快照 Ed25519 密钥对无效", exception);
        }
    }

    private static byte[] decodeBase64(String encodedKey, String message) {
        try {
            byte[] decoded = Base64.getDecoder().decode(encodedKey);
            if (decoded.length == 0) {
                throw new IllegalArgumentException(message);
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(message, exception);
        }
    }

    private static void validateTimeout(Duration timeout, String name) {
        if (timeout == null
                || timeout.compareTo(Duration.ofMillis(100)) < 0
                || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException(name + "必须在 100 毫秒到 30 秒之间");
        }
    }

    private static void rejectMissingOrPlaceholder(String value, String name) {
        String upper = value.toUpperCase(java.util.Locale.ROOT);
        if (value.isBlank() || upper.contains("REPLACE") || upper.contains("CHANGEME")) {
            throw new IllegalArgumentException(name + "未配置");
        }
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
