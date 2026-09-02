package com.aifriend.retention.infrastructure;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.qcloud.cos.model.COSObjectSummary;
import com.qcloud.cos.model.ListObjectsRequest;
import com.qcloud.cos.model.ObjectListing;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.region.Region;

import com.aifriend.retention.application.DeletionTombstoneRestoreDigest;
import com.aifriend.shared.error.UpstreamFailureException;
import com.aifriend.shared.security.DigestService;

/**
 * 腾讯云 COS 删除墓碑离线快照发布器。
 *
 * <p>发布器使用与运行时导出、恢复均分离的第三个 CAM 身份。它对墓碑前缀执行两次
 * 有界稳定枚举，完整读取并核对全部 AES256 信封，生成顺序分页索引和 Ed25519 根清单。
 * 页索引先发布，根清单最后发布；每个对象都禁止覆盖并在成功后完整回读。</p>
 *
 * <p>该类不是 Spring Bean，不会随服务启动或定时执行。</p>
 *
 * @author codex
 * @since 1.0.0
 */
public final class TencentCosDeletionTombstoneSnapshotPublisher implements AutoCloseable {

    private static final int MAXIMUM_ENVELOPE_BYTES = 2_048;
    private static final int MINIMUM_ENVELOPE_BYTES = 64;
    private static final int MAXIMUM_ITEM_COUNT = 1_000_000;
    private static final int LIST_PAGE_SIZE = 1_000;
    private static final int MAXIMUM_LIST_PAGES = 1_001;
    private static final int MAXIMUM_ETAG_LENGTH = 128;
    private static final String ENVELOPE_PREFIX = "deletion-tombstones/v1/";
    private static final String ENVELOPE_SUFFIX = ".envelope";
    private static final String SNAPSHOT_PREFIX = "deletion-tombstone-snapshots/v1/";
    private static final String PAGE_PREFIX = "/pages/";
    private static final String PAGE_SUFFIX = ".index";
    private static final String MANIFEST_SUFFIX = "/manifest.json";
    private static final String ENCRYPTION_ALGORITHM = "AES256";
    private static final String HEADER_FORBID_OVERWRITE = "x-cos-forbid-overwrite";
    private static final String BINARY_CONTENT_TYPE = "application/octet-stream";
    private static final String JSON_CONTENT_TYPE = "application/json";
    private static final String CACHE_CONTROL = "no-store";
    private static final Pattern ETAG_PATTERN =
            Pattern.compile("[A-Za-z0-9+/=_-]{1,128}");

    private final TencentCosSnapshotPublisherOptions options;
    private final DigestService digestService;
    private final COSClient cosClient;
    private final SignedDeletionTombstoneManifestWriter manifestWriter;
    private final SignedDeletionTombstoneManifestVerifier manifestVerifier;

    /**
     * 使用已校验的离线参数创建 COS 发布器。
     *
     * @param options 离线发布参数
     * @param digestService SHA-256 服务
     * @param objectMapper 严格 JSON 编解码器来源
     */
    public TencentCosDeletionTombstoneSnapshotPublisher(
            TencentCosSnapshotPublisherOptions options,
            DigestService digestService,
            ObjectMapper objectMapper) {
        this(options, digestService, objectMapper, createClient(options));
    }

    TencentCosDeletionTombstoneSnapshotPublisher(
            TencentCosSnapshotPublisherOptions options,
            DigestService digestService,
            ObjectMapper objectMapper,
            COSClient cosClient) {
        this.options = Objects.requireNonNull(options, "COS 快照发布参数不能为空");
        this.digestService = Objects.requireNonNull(digestService, "摘要服务不能为空");
        this.cosClient = Objects.requireNonNull(cosClient, "COS 客户端不能为空");
        this.manifestWriter = new SignedDeletionTombstoneManifestWriter(objectMapper);
        this.manifestVerifier = new SignedDeletionTombstoneManifestVerifier(objectMapper);
        try {
            verifyBucketVersioningOff();
        } catch (RuntimeException exception) {
            cosClient.shutdown();
            throw exception;
        }
    }

    /**
     * 构建、签名并发布一个不可变删除墓碑快照。
     *
     * @return 不含凭据和对象明细的发布回执
     */
    public SnapshotPublicationReceipt publish() {
        List<EnvelopeDescriptor> firstInventory = listEnvelopeInventory();
        List<EnvelopeDescriptor> stableInventory = listEnvelopeInventory();
        if (!firstInventory.equals(stableInventory)) {
            throw new IllegalStateException("COS 墓碑对象集合在快照枚举期间发生变化");
        }

        DeletionTombstoneRestoreDigest aggregateDigest =
                new DeletionTombstoneRestoreDigest(
                        options.snapshotId(), stableInventory.size());
        List<SignedDeletionTombstoneManifestVerifier.PageDescriptor> pages =
                new ArrayList<>();
        List<DeletionTombstoneSnapshotPageCodec.IndexedEnvelope> pageEntries =
                new ArrayList<>(options.pageSize());

        for (EnvelopeDescriptor descriptor : stableInventory) {
            ReadObject envelope = readRequiredEncryptedObject(
                    descriptor.objectKey(),
                    MINIMUM_ENVELOPE_BYTES,
                    MAXIMUM_ENVELOPE_BYTES);
            if (envelope.content().length != descriptor.contentLength()
                    || !envelope.etag().equals(descriptor.etag())) {
                throw new IllegalStateException("COS 墓碑对象在快照读取期间发生变化");
            }
            byte[] envelopeHash = digestService.sha256(envelope.content());
            aggregateDigest.update(descriptor.tombstoneId(), envelopeHash);
            pageEntries.add(new DeletionTombstoneSnapshotPageCodec.IndexedEnvelope(
                    descriptor.tombstoneId(), envelopeHash));
            if (pageEntries.size() == options.pageSize()) {
                publishPage(pages, pageEntries);
                pageEntries.clear();
            }
        }
        if (!pageEntries.isEmpty()) {
            publishPage(pages, pageEntries);
        }

        byte[] aggregateHash = aggregateDigest.finish();
        byte[] manifestDocument = manifestWriter.write(
                options.snapshotId(),
                options.createdAt(),
                stableInventory.size(),
                options.pageSize(),
                aggregateHash,
                pages,
                options.requirePrivateKey(),
                options.requirePublicKey());
        String manifestKey = manifestObjectKey(options.snapshotId());
        ReadObject storedManifest = putImmutableEncryptedObject(
                manifestKey,
                manifestDocument,
                JSON_CONTENT_TYPE,
                1,
                SignedDeletionTombstoneManifestVerifier.MAXIMUM_DOCUMENT_BYTES);
        SignedDeletionTombstoneManifestVerifier.VerifiedManifest verifiedManifest =
                manifestVerifier.verify(
                        storedManifest.content(),
                        options.requirePublicKey(),
                        options.snapshotId());
        if (verifiedManifest.expectedItemCount() != stableInventory.size()
                || !digestService.constantTimeEquals(
                        aggregateHash, verifiedManifest.aggregateHash())) {
            throw new IllegalStateException("COS 快照根清单回读验证失败");
        }
        return new SnapshotPublicationReceipt(
                options.snapshotId(),
                options.createdAt(),
                stableInventory.size(),
                pages.size(),
                digestService.sha256(storedManifest.content()));
    }

    /**
     * 关闭离线发布 COS 客户端及连接池。
     */
    @Override
    public void close() {
        cosClient.shutdown();
    }

    private void publishPage(
            List<SignedDeletionTombstoneManifestVerifier.PageDescriptor> pages,
            List<DeletionTombstoneSnapshotPageCodec.IndexedEnvelope> entries) {
        int pageNumber = pages.size();
        byte[] pageContent = DeletionTombstoneSnapshotPageCodec.encode(
                options.snapshotId(), pageNumber, List.copyOf(entries));
        byte[] pageHash = digestService.sha256(pageContent);
        putImmutableEncryptedObject(
                pageObjectKey(options.snapshotId(), pageNumber),
                pageContent,
                BINARY_CONTENT_TYPE,
                1,
                DeletionTombstoneSnapshotPageCodec.MAXIMUM_PAGE_BYTES);
        pages.add(new SignedDeletionTombstoneManifestVerifier.PageDescriptor(
                pageNumber, entries.size(), pageHash));
    }

    private List<EnvelopeDescriptor> listEnvelopeInventory() {
        List<EnvelopeDescriptor> descriptors = new ArrayList<>();
        String marker = null;
        for (int page = 0; page < MAXIMUM_LIST_PAGES; page++) {
            ObjectListing listing = listObjects(marker);
            List<COSObjectSummary> summaries = listing.getObjectSummaries();
            if (summaries == null) {
                throw unavailable();
            }
            for (COSObjectSummary summary : summaries) {
                descriptors.add(validateSummary(summary));
                if (descriptors.size() > MAXIMUM_ITEM_COUNT) {
                    throw new IllegalStateException("COS 墓碑对象数量超过快照上限");
                }
            }
            if (!listing.isTruncated()) {
                return sortedUnique(descriptors);
            }
            String nextMarker = Objects.requireNonNullElse(
                    listing.getNextMarker(), "").trim();
            if (nextMarker.isEmpty()
                    || marker != null && nextMarker.compareTo(marker) <= 0) {
                throw new IllegalStateException("COS 墓碑对象分页游标无效");
            }
            marker = nextMarker;
        }
        throw new IllegalStateException("COS 墓碑对象分页数量超过快照上限");
    }

    private ObjectListing listObjects(String marker) {
        ListObjectsRequest request = new ListObjectsRequest();
        request.setBucketName(options.bucket());
        request.setPrefix(ENVELOPE_PREFIX);
        request.setMarker(marker);
        request.setMaxKeys(LIST_PAGE_SIZE);
        try {
            ObjectListing listing = cosClient.listObjects(request);
            if (listing == null) {
                throw unavailable();
            }
            return listing;
        } catch (CosClientException exception) {
            throw unavailable();
        }
    }

    private EnvelopeDescriptor validateSummary(COSObjectSummary summary) {
        if (summary == null) {
            throw unavailable();
        }
        String objectKey = Objects.requireNonNullElse(summary.getKey(), "").trim();
        if (!objectKey.startsWith(ENVELOPE_PREFIX)
                || !objectKey.endsWith(ENVELOPE_SUFFIX)) {
            throw new IllegalStateException("COS 墓碑前缀包含非信封对象");
        }
        String idText = objectKey.substring(
                ENVELOPE_PREFIX.length(),
                objectKey.length() - ENVELOPE_SUFFIX.length());
        UUID tombstoneId;
        try {
            tombstoneId = UUID.fromString(idText);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("COS 墓碑对象键无效");
        }
        if (!objectKey.equals(envelopeObjectKey(tombstoneId))
                || summary.getSize() < MINIMUM_ENVELOPE_BYTES
                || summary.getSize() > MAXIMUM_ENVELOPE_BYTES) {
            throw new IllegalStateException("COS 墓碑对象摘要无效");
        }
        return new EnvelopeDescriptor(
                tombstoneId,
                objectKey,
                summary.getSize(),
                normalizeEtag(summary.getETag()));
    }

    private static List<EnvelopeDescriptor> sortedUnique(
            List<EnvelopeDescriptor> descriptors) {
        descriptors.sort(Comparator.comparing(EnvelopeDescriptor::tombstoneId));
        UUID previousId = null;
        for (EnvelopeDescriptor descriptor : descriptors) {
            if (previousId != null
                    && descriptor.tombstoneId().compareTo(previousId) <= 0) {
                throw new IllegalStateException("COS 墓碑快照包含重复 UUID");
            }
            previousId = descriptor.tombstoneId();
        }
        return List.copyOf(descriptors);
    }

    private ReadObject putImmutableEncryptedObject(
            String objectKey,
            byte[] expectedContent,
            String contentType,
            int minimumBytes,
            int maximumBytes) {
        ReadObject existing = readOptionalEncryptedObject(
                objectKey, minimumBytes, maximumBytes);
        if (existing != null) {
            verifySameObject(expectedContent, existing);
            return existing;
        }
        try {
            putOnce(objectKey, expectedContent, contentType);
        } catch (CosServiceException exception) {
            if (!isAlreadyExists(exception)) {
                throw unavailable();
            }
        } catch (CosClientException exception) {
            throw unavailable();
        }
        ReadObject stored = readOptionalEncryptedObject(
                objectKey, minimumBytes, maximumBytes);
        if (stored == null) {
            throw unavailable();
        }
        verifySameObject(expectedContent, stored);
        return stored;
    }

    private void putOnce(String objectKey, byte[] content, String contentType) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(content.length);
        metadata.setContentType(contentType);
        metadata.setCacheControl(CACHE_CONTROL);
        metadata.setServerSideEncryption(ENCRYPTION_ALGORITHM);
        try (InputStream input = new ByteArrayInputStream(content)) {
            PutObjectRequest request = new PutObjectRequest(
                    options.bucket(), objectKey, input, metadata);
            request.putCustomRequestHeader(HEADER_FORBID_OVERWRITE, "true");
            cosClient.putObject(request);
        } catch (IOException exception) {
            throw unavailable();
        }
    }

    private ReadObject readRequiredEncryptedObject(
            String objectKey,
            int minimumBytes,
            int maximumBytes) {
        ReadObject object = readOptionalEncryptedObject(
                objectKey, minimumBytes, maximumBytes);
        if (object == null) {
            throw unavailable();
        }
        return object;
    }

    private ReadObject readOptionalEncryptedObject(
            String objectKey,
            int minimumBytes,
            int maximumBytes) {
        try (COSObject storedObject = cosClient.getObject(options.bucket(), objectKey)) {
            if (storedObject == null) {
                throw unavailable();
            }
            ObjectMetadata metadata = storedObject.getObjectMetadata();
            if (metadata == null
                    || metadata.getContentLength() < minimumBytes
                    || metadata.getContentLength() > maximumBytes) {
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
            byte[] content = objectContent.readNBytes(maximumBytes + 1);
            if (content.length != metadata.getContentLength()) {
                throw unavailable();
            }
            return new ReadObject(content, etag);
        } catch (CosServiceException exception) {
            if (isNoSuchKey(exception)) {
                return null;
            }
            throw unavailable();
        } catch (CosClientException | IOException exception) {
            throw unavailable();
        }
    }

    private void verifySameObject(byte[] expectedContent, ReadObject actualObject) {
        byte[] expectedHash = digestService.sha256(expectedContent);
        byte[] actualHash = digestService.sha256(actualObject.content());
        if (!digestService.constantTimeEquals(expectedHash, actualHash)
                || !MessageDigest.isEqual(expectedContent, actualObject.content())) {
            throw new IllegalStateException("COS 快照对象与固定内容冲突");
        }
    }

    private void verifyBucketVersioningOff() {
        try {
            BucketVersioningConfiguration versioning =
                    cosClient.getBucketVersioningConfiguration(options.bucket());
            String status = versioning == null ? null : versioning.getStatus();
            if (status != null && !BucketVersioningConfiguration.OFF.equals(status)) {
                throw new IllegalStateException("COS 快照 Bucket 必须保持版本控制从未开启");
            }
        } catch (CosClientException exception) {
            throw new IllegalStateException("无法验证 COS 快照 Bucket 版本控制状态");
        }
    }

    private static COSClient createClient(TencentCosSnapshotPublisherOptions options) {
        COSCredentials credentials = options.publisherSessionToken().isBlank()
                ? new BasicCOSCredentials(
                        options.publisherSecretId(), options.publisherSecretKey())
                : new BasicSessionCredentials(
                        options.publisherSecretId(),
                        options.publisherSecretKey(),
                        options.publisherSessionToken());
        ClientConfig clientConfig = new ClientConfig(new Region(options.region()));
        clientConfig.setHttpProtocol(HttpProtocol.https);
        clientConfig.setConnectionRequestTimeout(toMilliseconds(options.connectTimeout()));
        clientConfig.setConnectionTimeout(toMilliseconds(options.connectTimeout()));
        clientConfig.setSocketTimeout(toMilliseconds(options.readTimeout()));
        clientConfig.setRequestTimeout(toMilliseconds(options.readTimeout()));
        clientConfig.setRequestTimeOutEnable(true);
        clientConfig.setMaxConnectionsCount(4);
        clientConfig.setMaxErrorRetry(0);
        clientConfig.setCheckSSLCertificate(true);
        clientConfig.setRedirectsEnabled(false);
        return new COSClient(credentials, clientConfig);
    }

    private static int toMilliseconds(Duration duration) {
        return Math.toIntExact(duration.toMillis());
    }

    private static String envelopeObjectKey(UUID tombstoneId) {
        return ENVELOPE_PREFIX + tombstoneId + ENVELOPE_SUFFIX;
    }

    private static String pageObjectKey(String snapshotId, int pageNumber) {
        return SNAPSHOT_PREFIX + snapshotId + PAGE_PREFIX
                + "%08d".formatted(pageNumber) + PAGE_SUFFIX;
    }

    private static String manifestObjectKey(String snapshotId) {
        return SNAPSHOT_PREFIX + snapshotId + MANIFEST_SUFFIX;
    }

    private static boolean isNoSuchKey(CosServiceException exception) {
        return "NoSuchKey".equals(exception.getErrorCode());
    }

    private static boolean isAlreadyExists(CosServiceException exception) {
        return "FileAlreadyExists".equals(exception.getErrorCode());
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
        return new UpstreamFailureException("可信 COS 快照发布服务暂不可用");
    }

    /**
     * 已完成本地签名和云端完整回读的快照发布回执。
     *
     * @param snapshotId 快照编号
     * @param createdAt 固定生成时间
     * @param itemCount 信封总数
     * @param pageCount 索引页数量
     * @param manifestHash 根清单 SHA-256
     */
    public record SnapshotPublicationReceipt(
            String snapshotId,
            java.time.Instant createdAt,
            long itemCount,
            int pageCount,
            byte[] manifestHash) {

        /**
         * 隔离可变摘要数组。
         */
        public SnapshotPublicationReceipt {
            manifestHash = java.util.Arrays.copyOf(manifestHash, manifestHash.length);
        }

        /**
         * 返回根清单摘要副本。
         *
         * @return 32 字节摘要副本
         */
        @Override
        public byte[] manifestHash() {
            return java.util.Arrays.copyOf(manifestHash, manifestHash.length);
        }
    }

    private record EnvelopeDescriptor(
            UUID tombstoneId,
            String objectKey,
            long contentLength,
            String etag) {
    }

    private record ReadObject(byte[] content, String etag) {
    }
}
