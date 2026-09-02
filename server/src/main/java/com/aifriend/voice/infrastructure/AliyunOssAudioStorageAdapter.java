package com.aifriend.voice.infrastructure;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.ClientException;
import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.common.auth.CredentialsProvider;
import com.aliyun.oss.common.auth.CredentialsProviderFactory;
import com.aliyun.oss.common.comm.SignVersion;
import com.aliyun.oss.model.BucketVersioningConfiguration;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectRequest;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.error.UpstreamFailureException;
import com.aifriend.shared.security.DigestService;
import com.aifriend.voice.application.AliyunOssStorageProperties;
import com.aifriend.voice.application.AudioObjectStoragePort;
import com.aifriend.voice.application.AudioUploadTarget;
import com.aifriend.voice.application.AudioUploadTargetPort;
import com.aifriend.voice.application.StoredAudioObject;
import com.aifriend.voice.domain.AudioObject;

/**
 * 阿里云 OSS 生产私有音频存储与受限直传适配器。
 *
 * <p>直传固定使用 V4 签名、十分钟业务票据原到期时间、私有对象 ACL、
 * OSS 托管 AES256 服务端加密和禁止覆盖头。后端读取后以内容 SHA-256 作为强版本，
 * 不把 ETag 或对象键写入日志。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
@Profile("!dev & !test")
@ConditionalOnProperty(
        prefix = "ai-friend.audio.oss",
        name = "enabled",
        havingValue = "true")
public class AliyunOssAudioStorageAdapter
        implements AudioObjectStoragePort, AudioUploadTargetPort, AutoCloseable {

    private static final long ABSOLUTE_MAXIMUM_BYTES = 20_971_520L;
    private static final Pattern OBJECT_KEY_PATTERN =
            Pattern.compile("temporary/[0-9a-f]{32}");
    private static final String HEADER_CONTENT_TYPE = "Content-Type";
    private static final String HEADER_CACHE_CONTROL = "Cache-Control";
    private static final String HEADER_FORBID_OVERWRITE =
            "x-oss-forbid-overwrite";
    private static final String HEADER_OBJECT_ACL = "x-oss-object-acl";
    private static final String HEADER_ENCRYPTION =
            "x-oss-server-side-encryption";

    private final AliyunOssStorageProperties properties;
    private final DigestService digestService;
    private final OSS serviceClient;
    private final OSS uploadSigningClient;

    /**
     * 创建生产 OSS 适配器并立即验证 Bucket 版本控制为关闭状态。
     *
     * @param properties 已完成格式校验的 OSS 配置
     * @param digestService SHA-256 服务
     */
    @Autowired
    public AliyunOssAudioStorageAdapter(
            AliyunOssStorageProperties properties,
            DigestService digestService) {
        this(
                properties,
                digestService,
                createClient(properties, properties.serviceEndpoint()),
                createClient(properties, properties.uploadEndpoint()));
    }

    AliyunOssAudioStorageAdapter(
            AliyunOssStorageProperties properties,
            DigestService digestService,
            OSS serviceClient,
            OSS uploadSigningClient) {
        this.properties = properties;
        this.digestService = digestService;
        this.serviceClient = serviceClient;
        this.uploadSigningClient = uploadSigningClient;
        verifyBucketVersioningOff();
    }

    /** {@inheritDoc} */
    @Override
    public AudioUploadTarget createTarget(
            AudioObject audioObject,
            String uploadToken) {
        if (audioObject == null
                || uploadToken == null
                || uploadToken.isBlank()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        validateObjectKey(audioObject.objectKey());
        Map<String, String> requiredHeaders = requiredHeaders(
                audioObject.mediaType());
        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(
                properties.bucket(), audioObject.objectKey(), HttpMethod.PUT);
        request.setExpiration(Date.from(audioObject.uploadExpiresAt()));
        request.setContentType(audioObject.mediaType());
        request.setHeaders(withoutContentType(requiredHeaders));
        requiredHeaders.keySet().forEach(request::addAdditionalHeaderName);
        try {
            URL signedUrl = uploadSigningClient.generatePresignedUrl(request);
            URI uploadUri = signedUrl.toURI();
            validateSignedUploadUri(uploadUri);
            return new AudioUploadTarget(uploadUri, "PUT", requiredHeaders);
        } catch (ClientException | URISyntaxException exception) {
            throw unavailable();
        }
    }

    /** {@inheritDoc} */
    @Override
    public String store(String objectKey, byte[] audioContent) {
        validateObjectKey(objectKey);
        if (audioContent == null
                || audioContent.length < 1
                || audioContent.length > ABSOLUTE_MAXIMUM_BYTES) {
            throw new BusinessException(ErrorCode.AUDIO_INVALID);
        }
        ObjectMetadata metadata = metadata(
                "application/octet-stream", audioContent.length);
        try (InputStream input = new ByteArrayInputStream(audioContent)) {
            serviceClient.putObject(new PutObjectRequest(
                    properties.bucket(), objectKey, input, metadata));
            return storageVersion(audioContent);
        } catch (OSSException exception) {
            if ("FileAlreadyExists".equals(exception.getErrorCode())) {
                throw new BusinessException(ErrorCode.SESSION_CONFLICT);
            }
            throw unavailable();
        } catch (ClientException | IOException exception) {
            throw unavailable();
        }
    }

    /** {@inheritDoc} */
    @Override
    public StoredAudioObject readCurrent(
            String objectKey,
            long maximumBytes) {
        return readBounded(objectKey, maximumBytes);
    }

    /** {@inheritDoc} */
    @Override
    public StoredAudioObject readExact(
            String objectKey,
            String expectedStorageVersion,
            long maximumBytes) {
        StoredAudioObject stored = readBounded(objectKey, maximumBytes);
        if (!matchesStorageVersion(
                stored.storageVersion(), expectedStorageVersion)) {
            stored.close();
            throw new BusinessException(ErrorCode.AUDIO_INVALID);
        }
        return stored;
    }

    /** {@inheritDoc} */
    @Override
    public void delete(String objectKey) {
        validateObjectKey(objectKey);
        try {
            serviceClient.deleteObject(properties.bucket(), objectKey);
            if (serviceClient.doesObjectExist(
                    properties.bucket(), objectKey)) {
                throw unavailable();
            }
        } catch (OSSException exception) {
            if (isMissingObject(exception)) {
                return;
            }
            throw unavailable();
        } catch (ClientException exception) {
            throw unavailable();
        }
    }

    /**
     * 关闭两个 OSS 客户端并释放连接池。
     */
    @Override
    @PreDestroy
    public void close() {
        serviceClient.shutdown();
        uploadSigningClient.shutdown();
    }

    private StoredAudioObject readBounded(
            String objectKey,
            long maximumBytes) {
        validateObjectKey(objectKey);
        validateMaximumBytes(maximumBytes);
        try (OSSObject ossObject = serviceClient.getObject(
                properties.bucket(), objectKey)) {
            long contentLength = ossObject.getObjectMetadata().getContentLength();
            if (contentLength < 1 || contentLength > maximumBytes) {
                throw new BusinessException(ErrorCode.AUDIO_INVALID);
            }
            byte[] audioContent = ossObject.getObjectContent().readNBytes(
                    Math.toIntExact(maximumBytes) + 1);
            if (audioContent.length != contentLength
                    || audioContent.length > maximumBytes) {
                throw new BusinessException(ErrorCode.AUDIO_INVALID);
            }
            return new StoredAudioObject(
                    storageVersion(audioContent), audioContent);
        } catch (BusinessException exception) {
            throw exception;
        } catch (OSSException exception) {
            if (isMissingObject(exception)) {
                throw new BusinessException(ErrorCode.AUDIO_INVALID);
            }
            throw unavailable();
        } catch (ClientException | IOException exception) {
            throw unavailable();
        }
    }

    private void verifyBucketVersioningOff() {
        try {
            BucketVersioningConfiguration versioning =
                    serviceClient.getBucketVersioning(properties.bucket());
            if (!isBucketVersioningOff(versioning)) {
                close();
                throw new IllegalStateException(
                        "OSS 音频 Bucket 必须保持版本控制关闭");
            }
        } catch (OSSException | ClientException exception) {
            close();
            throw new IllegalStateException(
                    "无法验证 OSS 音频 Bucket 安全配置");
        }
    }

    private boolean isBucketVersioningOff(
            BucketVersioningConfiguration versioning) {
        if (versioning == null) {
            return false;
        }
        String status = versioning.getStatus();
        return status == null
                || BucketVersioningConfiguration.OFF.equals(status);
    }

    private void validateSignedUploadUri(URI uploadUri) {
        String expectedHost = properties.bucket() + "."
                + properties.uploadEndpoint().getHost();
        if (!"https".equalsIgnoreCase(uploadUri.getScheme())
                || !expectedHost.equalsIgnoreCase(uploadUri.getHost())
                || uploadUri.getUserInfo() != null
                || uploadUri.getFragment() != null) {
            throw new UpstreamFailureException("音频存储暂不可用，请稍后再试");
        }
    }

    private void validateObjectKey(String objectKey) {
        if (objectKey == null
                || !OBJECT_KEY_PATTERN.matcher(objectKey).matches()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    private void validateMaximumBytes(long maximumBytes) {
        if (maximumBytes < 1 || maximumBytes > ABSOLUTE_MAXIMUM_BYTES) {
            throw new BusinessException(ErrorCode.AUDIO_INVALID);
        }
    }

    private Map<String, String> requiredHeaders(String mediaType) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(HEADER_CONTENT_TYPE, mediaType);
        headers.put(HEADER_CACHE_CONTROL, "no-store");
        headers.put(HEADER_FORBID_OVERWRITE, "true");
        headers.put(HEADER_OBJECT_ACL, "private");
        headers.put(HEADER_ENCRYPTION, "AES256");
        return Map.copyOf(headers);
    }

    private Map<String, String> withoutContentType(
            Map<String, String> requiredHeaders) {
        Map<String, String> signingHeaders =
                new LinkedHashMap<>(requiredHeaders);
        signingHeaders.remove(HEADER_CONTENT_TYPE);
        return signingHeaders;
    }

    private ObjectMetadata metadata(String mediaType, long contentLength) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(contentLength);
        requiredHeaders(mediaType).forEach(metadata::setHeader);
        return metadata;
    }

    private String storageVersion(byte[] audioContent) {
        return "sha256:" + HexFormat.of().formatHex(
                digestService.sha256(audioContent));
    }

    private boolean matchesStorageVersion(
            String actualStorageVersion,
            String expectedStorageVersion) {
        if (expectedStorageVersion == null
                || !expectedStorageVersion.startsWith("sha256:")
                || expectedStorageVersion.length() != 71) {
            return false;
        }
        return MessageDigest.isEqual(
                actualStorageVersion.getBytes(StandardCharsets.US_ASCII),
                expectedStorageVersion.getBytes(StandardCharsets.US_ASCII));
    }
    private boolean isMissingObject(OSSException exception) {
        return "NoSuchKey".equals(exception.getErrorCode());
    }

    private UpstreamFailureException unavailable() {
        return new UpstreamFailureException(
                "音频存储暂不可用，请稍后再试");
    }

    static OSS createClient(
            AliyunOssStorageProperties properties,
            URI endpoint) {
        CredentialsProvider credentialsProvider =
                properties.securityToken().isBlank()
                        ? CredentialsProviderFactory.newDefaultCredentialProvider(
                                properties.accessKeyId(),
                                properties.accessKeySecret())
                        : CredentialsProviderFactory.newDefaultCredentialProvider(
                                properties.accessKeyId(),
                                properties.accessKeySecret(),
                                properties.securityToken());
        ClientBuilderConfiguration configuration =
                new ClientBuilderConfiguration();
        configuration.setSignatureVersion(SignVersion.V4);
        configuration.setConnectionTimeout(Math.toIntExact(
                properties.connectTimeout().toMillis()));
        configuration.setSocketTimeout(Math.toIntExact(
                properties.readTimeout().toMillis()));
        configuration.setRequestTimeoutEnabled(true);
        configuration.setRequestTimeout(Math.toIntExact(
                properties.connectTimeout().plus(
                        properties.readTimeout()).toMillis()));
        configuration.setMaxConnections(16);
        configuration.setMaxErrorRetry(0);
        return OSSClientBuilder.create()
                .endpoint(endpoint.toString())
                .credentialsProvider(credentialsProvider)
                .clientConfiguration(configuration)
                .region(properties.region())
                .build();
    }
}
