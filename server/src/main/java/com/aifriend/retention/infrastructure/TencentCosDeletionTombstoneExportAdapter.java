package com.aifriend.retention.infrastructure;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.BasicSessionCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.exception.CosClientException;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.model.BucketVersioningConfiguration;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.region.Region;

import com.aifriend.retention.application.DeletionTombstoneExportPort;
import com.aifriend.retention.application.DeletionTombstoneExportReceipt;
import com.aifriend.retention.application.DisasterRecoveryProperties;
import com.aifriend.retention.application.EncryptedDeletionTombstoneEnvelope;
import com.aifriend.retention.application.TencentCosDisasterRecoveryProperties;
import com.aifriend.shared.error.UpstreamFailureException;
import com.aifriend.shared.security.DigestService;

/**
 * 腾讯云 COS 删除墓碑独立灾备介质生产适配器。
 *
 * <p>对象键仅由随机墓碑 UUID 派生。适配器先读取既有对象实现幂等恢复，缺失时使用
 * 禁止覆盖请求头和 SSE-COS AES256 上传，再完整回读并校验摘要。Bucket 版本控制、
 * 服务端加密、内容长度或摘要任一事实不满足时均失败关闭。</p>
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
@Profile("!test")
@ConditionalOnProperty(
        prefix = "ai-friend.retention.disaster-recovery.cos",
        name = "enabled",
        havingValue = "true")
public class TencentCosDeletionTombstoneExportAdapter
        implements DeletionTombstoneExportPort, AutoCloseable {

    private static final int MAXIMUM_ENVELOPE_BYTES = 2048;
    private static final int MAXIMUM_ETAG_LENGTH = 128;
    private static final String OBJECT_KEY_PREFIX = "deletion-tombstones/v1/";
    private static final String OBJECT_KEY_SUFFIX = ".envelope";
    private static final String CONTENT_TYPE = "application/octet-stream";
    private static final String CACHE_CONTROL = "no-store";
    private static final String ENCRYPTION_ALGORITHM = "AES256";
    private static final String HEADER_FORBID_OVERWRITE =
            "x-cos-forbid-overwrite";
    private static final Pattern ETAG_PATTERN =
            Pattern.compile("[A-Za-z0-9+/=_-]{1,128}");

    private final TencentCosDisasterRecoveryProperties properties;
    private final DigestService digestService;
    private final COSClient cosClient;

    /**
     * 创建生产 COS 适配器并立即验证核心门禁与 Bucket 版本控制。
     *
     * @param properties 已完成格式校验的 COS 配置
     * @param disasterRecoveryProperties 灾备信封与总开关配置
     * @param digestService SHA-256 服务
     */
    @Autowired
    public TencentCosDeletionTombstoneExportAdapter(
            TencentCosDisasterRecoveryProperties properties,
            DisasterRecoveryProperties disasterRecoveryProperties,
            DigestService digestService) {
        this.properties = Objects.requireNonNull(properties, "COS 灾备配置不能为空");
        this.digestService = Objects.requireNonNull(digestService, "摘要服务不能为空");
        requireCoreExportEnabled(disasterRecoveryProperties);
        this.cosClient = createClient(properties);
        try {
            verifyBucketVersioningOff();
        } catch (RuntimeException exception) {
            cosClient.shutdown();
            throw exception;
        }
    }

    TencentCosDeletionTombstoneExportAdapter(
            TencentCosDisasterRecoveryProperties properties,
            DisasterRecoveryProperties disasterRecoveryProperties,
            DigestService digestService,
            COSClient cosClient) {
        this.properties = Objects.requireNonNull(properties, "COS 灾备配置不能为空");
        this.digestService = Objects.requireNonNull(digestService, "摘要服务不能为空");
        this.cosClient = Objects.requireNonNull(cosClient, "COS 客户端不能为空");
        requireCoreExportEnabled(disasterRecoveryProperties);
        try {
            verifyBucketVersioningOff();
        } catch (RuntimeException exception) {
            cosClient.shutdown();
            throw exception;
        }
    }

    /** {@inheritDoc} */
    @Override
    public DeletionTombstoneExportReceipt export(
            UUID tombstoneId,
            EncryptedDeletionTombstoneEnvelope envelope) {
        Objects.requireNonNull(tombstoneId, "墓碑编号不能为空");
        Objects.requireNonNull(envelope, "删除墓碑密文包不能为空");
        byte[] expectedContent = envelope.encryptedEnvelope();
        byte[] expectedHash = envelope.envelopeHash();
        if (!digestService.constantTimeEquals(
                expectedHash, digestService.sha256(expectedContent))) {
            throw new IllegalArgumentException("灾备墓碑密文包摘要不匹配");
        }

        String objectKey = objectKey(tombstoneId);
        ReadObject existing = readExisting(objectKey);
        if (existing != null) {
            return verifyAndCreateReceipt(
                    objectKey, expectedContent, expectedHash, existing);
        }

        try {
            putOnce(objectKey, expectedContent);
        } catch (CosServiceException exception) {
            if (!isAlreadyExists(exception)) {
                throw unavailable();
            }
        } catch (CosClientException exception) {
            throw unavailable();
        }

        ReadObject stored = readExisting(objectKey);
        if (stored == null) {
            throw unavailable();
        }
        return verifyAndCreateReceipt(
                objectKey, expectedContent, expectedHash, stored);
    }

    /**
     * 关闭 COS 客户端及其连接池。
     */
    @Override
    @PreDestroy
    public void close() {
        cosClient.shutdown();
    }

    private void putOnce(String objectKey, byte[] content) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(content.length);
        metadata.setContentType(CONTENT_TYPE);
        metadata.setCacheControl(CACHE_CONTROL);
        metadata.setServerSideEncryption(ENCRYPTION_ALGORITHM);
        try (InputStream input = new ByteArrayInputStream(content)) {
            PutObjectRequest request = new PutObjectRequest(
                    properties.bucket(), objectKey, input, metadata);
            request.putCustomRequestHeader(HEADER_FORBID_OVERWRITE, "true");
            cosClient.putObject(request);
        } catch (IOException exception) {
            throw unavailable();
        }
    }

    private ReadObject readExisting(String objectKey) {
        try (COSObject storedObject = cosClient.getObject(
                properties.bucket(), objectKey)) {
            if (storedObject == null) {
                throw unavailable();
            }
            ObjectMetadata metadata = storedObject.getObjectMetadata();
            if (metadata == null
                    || metadata.getContentLength() < 64
                    || metadata.getContentLength() > MAXIMUM_ENVELOPE_BYTES) {
                throw unavailable();
            }
            String encryption = firstNonBlank(
                    metadata.getServerSideEncryption(),
                    metadata.getSSEAlgorithm());
            if (!ENCRYPTION_ALGORITHM.equalsIgnoreCase(encryption)) {
                throw unavailable();
            }
            String etag = normalizeEtag(metadata.getETag());
            InputStream objectContent = storedObject.getObjectContent();
            if (objectContent == null) {
                throw unavailable();
            }
            byte[] content = objectContent.readNBytes(MAXIMUM_ENVELOPE_BYTES + 1);
            if (content.length != metadata.getContentLength()) {
                throw unavailable();
            }
            return new ReadObject(content, etag, encryption);
        } catch (CosServiceException exception) {
            if (isNoSuchKey(exception)) {
                return null;
            }
            throw unavailable();
        } catch (CosClientException | IOException exception) {
            throw unavailable();
        }
    }

    private DeletionTombstoneExportReceipt verifyAndCreateReceipt(
            String objectKey,
            byte[] expectedContent,
            byte[] expectedHash,
            ReadObject stored) {
        byte[] actualHash = digestService.sha256(stored.content());
        if (!digestService.constantTimeEquals(expectedHash, actualHash)
                || !java.security.MessageDigest.isEqual(
                        expectedContent, stored.content())) {
            throw new IllegalStateException("灾备墓碑对象与固定信封冲突");
        }
        String proofSource = "cos-deletion-tombstone-v1\n"
                + properties.region() + "\n"
                + properties.bucket() + "\n"
                + objectKey + "\n"
                + stored.content().length + "\n"
                + stored.etag() + "\n"
                + stored.encryption() + "\n"
                + HexFormat.of().formatHex(actualHash);
        return new DeletionTombstoneExportReceipt(
                actualHash,
                digestService.sha256(proofSource.getBytes(UTF_8)));
    }

    private void verifyBucketVersioningOff() {
        try {
            BucketVersioningConfiguration versioning =
                    cosClient.getBucketVersioningConfiguration(properties.bucket());
            String status = versioning == null ? null : versioning.getStatus();
            if (status != null
                    && !BucketVersioningConfiguration.OFF.equals(status)) {
                throw new IllegalStateException(
                        "COS 灾备 Bucket 必须保持版本控制从未开启");
            }
        } catch (CosClientException exception) {
            throw new IllegalStateException(
                    "无法验证 COS 灾备 Bucket 版本控制状态");
        }
    }

    private static COSClient createClient(
            TencentCosDisasterRecoveryProperties properties) {
        COSCredentials credentials = properties.sessionToken().isBlank()
                ? new BasicCOSCredentials(
                        properties.secretId(), properties.secretKey())
                : new BasicSessionCredentials(
                        properties.secretId(),
                        properties.secretKey(),
                        properties.sessionToken());
        ClientConfig clientConfig = new ClientConfig(
                new Region(properties.region()));
        clientConfig.setHttpProtocol(HttpProtocol.https);
        clientConfig.setConnectionRequestTimeout(
                toMilliseconds(properties.connectTimeout()));
        clientConfig.setConnectionTimeout(
                toMilliseconds(properties.connectTimeout()));
        clientConfig.setSocketTimeout(
                toMilliseconds(properties.readTimeout()));
        clientConfig.setRequestTimeout(
                toMilliseconds(properties.readTimeout()));
        clientConfig.setRequestTimeOutEnable(true);
        clientConfig.setMaxConnectionsCount(8);
        clientConfig.setMaxErrorRetry(0);
        clientConfig.setCheckSSLCertificate(true);
        clientConfig.setRedirectsEnabled(false);
        return new COSClient(credentials, clientConfig);
    }

    private static int toMilliseconds(Duration duration) {
        return Math.toIntExact(duration.toMillis());
    }

    private static void requireCoreExportEnabled(
            DisasterRecoveryProperties properties) {
        if (properties == null || !properties.exportEnabled()) {
            throw new IllegalStateException("灾备墓碑总导出开关未开启");
        }
    }

    private static String objectKey(UUID tombstoneId) {
        return OBJECT_KEY_PREFIX + tombstoneId + OBJECT_KEY_SUFFIX;
    }

    private static boolean isAlreadyExists(CosServiceException exception) {
        return "FileAlreadyExists".equals(exception.getErrorCode());
    }

    private static boolean isNoSuchKey(CosServiceException exception) {
        return "NoSuchKey".equals(exception.getErrorCode());
    }

    private static String normalizeEtag(String etag) {
        String normalized = Objects.requireNonNullElse(etag, "")
                .replace("\"", "")
                .trim();
        if (normalized.length() > MAXIMUM_ETAG_LENGTH
                || !ETAG_PATTERN.matcher(normalized).matches()) {
            throw unavailable();
        }
        return normalized;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return Objects.requireNonNullElse(second, "").trim();
    }

    private static UpstreamFailureException unavailable() {
        return new UpstreamFailureException("可信灾备墓碑存储暂不可用");
    }

    private record ReadObject(
            byte[] content,
            String etag,
            String encryption) {
    }
}
