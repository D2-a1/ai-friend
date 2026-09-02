package com.aifriend.retention.infrastructure;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.BasicSessionCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.exception.CosClientException;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.model.BucketVersioningConfiguration;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.region.Region;

import com.aifriend.retention.application.DeletionTombstoneRestoreBatch;
import com.aifriend.retention.application.DeletionTombstoneRestoreEntry;
import com.aifriend.retention.application.DeletionTombstoneRestoreManifest;
import com.aifriend.retention.application.DeletionTombstoneRestoreSourcePort;
import com.aifriend.retention.application.DisasterRecoveryProperties;
import com.aifriend.retention.application.EncryptedDeletionTombstoneEnvelope;
import com.aifriend.retention.application.TencentCosDisasterRecoveryProperties;
import com.aifriend.retention.application.TencentCosDisasterRecoveryRestoreProperties;
import com.aifriend.shared.error.UpstreamFailureException;
import com.aifriend.shared.security.DigestService;

/**
 * 腾讯云 COS 删除墓碑可信只读恢复源适配器。
 *
 * <p>适配器只读取显式绑定快照的签名根清单、确定页索引和确定墓碑信封，不使用对象
 * 列表能力。每批读取前重新认证根清单；页摘要、信封摘要、SSE-COS AES256、版本控制和
 * 不透明游标任一事实异常时均失败关闭。</p>
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
@Profile("!test")
@ConditionalOnProperty(
        prefix = "ai-friend.retention.disaster-recovery.cos-restore",
        name = "enabled",
        havingValue = "true")
public class TencentCosDeletionTombstoneRestoreSourceAdapter
        implements DeletionTombstoneRestoreSourcePort, AutoCloseable {

    private static final int MAXIMUM_ENVELOPE_BYTES = 2_048;
    private static final int MINIMUM_ENVELOPE_BYTES = 64;
    private static final int MAXIMUM_ETAG_LENGTH = 128;
    private static final int CURSOR_PROOF_BYTES = 16;
    private static final String SNAPSHOT_PREFIX = "deletion-tombstone-snapshots/v1/";
    private static final String MANIFEST_SUFFIX = "/manifest.json";
    private static final String PAGE_PREFIX = "/pages/";
    private static final String PAGE_SUFFIX = ".index";
    private static final String ENVELOPE_PREFIX = "deletion-tombstones/v1/";
    private static final String ENVELOPE_SUFFIX = ".envelope";
    private static final String ENCRYPTION_ALGORITHM = "AES256";
    private static final String SOURCE_PROOF_VERSION = "AIFDRC01";
    private static final String CURSOR_PROOF_VERSION = "AIFDRU01";
    private static final Pattern ETAG_PATTERN =
            Pattern.compile("[A-Za-z0-9+/=_-]{1,128}");

    private final TencentCosDisasterRecoveryRestoreProperties properties;
    private final DisasterRecoveryProperties disasterRecoveryProperties;
    private final DigestService digestService;
    private final COSClient cosClient;
    private final SignedDeletionTombstoneManifestVerifier manifestVerifier;
    private volatile OpenedSnapshot openedSnapshot;

    /**
     * 创建 COS 可信恢复源并立即验证恢复门禁、身份分离和 Bucket 版本控制。
     *
     * @param properties COS 只读恢复配置
     * @param exportProperties 日常导出身份配置
     * @param disasterRecoveryProperties 灾备恢复模式与信封配置
     * @param digestService SHA-256 服务
     * @param objectMapper 严格清单 JSON 解析器来源
     */
    @Autowired
    public TencentCosDeletionTombstoneRestoreSourceAdapter(
            TencentCosDisasterRecoveryRestoreProperties properties,
            TencentCosDisasterRecoveryProperties exportProperties,
            DisasterRecoveryProperties disasterRecoveryProperties,
            DigestService digestService,
            ObjectMapper objectMapper) {
        this(
                properties,
                exportProperties,
                disasterRecoveryProperties,
                digestService,
                objectMapper,
                createClient(properties));
    }

    TencentCosDeletionTombstoneRestoreSourceAdapter(
            TencentCosDisasterRecoveryRestoreProperties properties,
            TencentCosDisasterRecoveryProperties exportProperties,
            DisasterRecoveryProperties disasterRecoveryProperties,
            DigestService digestService,
            ObjectMapper objectMapper,
            COSClient cosClient) {
        this.properties = Objects.requireNonNull(properties, "COS 恢复配置不能为空");
        this.disasterRecoveryProperties = Objects.requireNonNull(
                disasterRecoveryProperties, "灾备恢复配置不能为空");
        this.digestService = Objects.requireNonNull(digestService, "摘要服务不能为空");
        this.cosClient = Objects.requireNonNull(cosClient, "COS 客户端不能为空");
        this.manifestVerifier = new SignedDeletionTombstoneManifestVerifier(objectMapper);
        try {
            requireRestoreMode();
            requireSeparateIdentity(exportProperties);
            verifyBucketVersioningOff();
        } catch (RuntimeException exception) {
            cosClient.shutdown();
            throw exception;
        }
    }

    /** {@inheritDoc} */
    @Override
    public synchronized DeletionTombstoneRestoreManifest openSnapshot(
            String expectedSnapshotId) {
        if (!disasterRecoveryProperties.requireRestoreSnapshotId()
                .equals(expectedSnapshotId)) {
            throw new IllegalStateException("COS 恢复快照与启动配置不一致");
        }
        OpenedSnapshot verifiedSnapshot = loadSnapshot(expectedSnapshotId);
        openedSnapshot = verifiedSnapshot;
        return verifiedSnapshot.restoreManifest();
    }

    /** {@inheritDoc} */
    @Override
    public synchronized DeletionTombstoneRestoreBatch readBatch(
            String snapshotId,
            String cursor,
            int maxItems) {
        if (maxItems < 1 || maxItems > 100) {
            throw new IllegalArgumentException("COS 恢复批次大小必须在 1 到 100 之间");
        }
        OpenedSnapshot currentSnapshot = requireOpenedSnapshot(snapshotId);
        OpenedSnapshot reverifiedSnapshot = loadSnapshot(snapshotId);
        if (!digestService.constantTimeEquals(
                currentSnapshot.sourceProofHash(),
                reverifiedSnapshot.sourceProofHash())) {
            throw new IllegalStateException("COS 恢复根清单在分页期间发生变化");
        }

        int position = decodeCursor(cursor, currentSnapshot);
        long expectedItemCount = currentSnapshot.verifiedManifest().expectedItemCount();
        if (position > expectedItemCount) {
            throw new IllegalStateException("COS 恢复分页游标超出快照范围");
        }
        if (position == expectedItemCount) {
            return new DeletionTombstoneRestoreBatch(List.of(), null, true);
        }

        List<DeletionTombstoneRestoreEntry> entries = new ArrayList<>(maxItems);
        int globalPosition = position;
        UUID previousId = null;
        PagePosition pagePosition = locatePage(
                currentSnapshot.verifiedManifest().pages(), position);
        for (int pageIndex = pagePosition.pageIndex();
                pageIndex < currentSnapshot.verifiedManifest().pages().size()
                        && entries.size() < maxItems;
                pageIndex++) {
            SignedDeletionTombstoneManifestVerifier.PageDescriptor descriptor =
                    currentSnapshot.verifiedManifest().pages().get(pageIndex);
            List<DeletionTombstoneSnapshotPageCodec.IndexedEnvelope> pageEntries =
                    readPage(currentSnapshot, descriptor);
            int startIndex = pageIndex == pagePosition.pageIndex()
                    ? pagePosition.entryIndex() : 0;
            for (int entryIndex = startIndex;
                    entryIndex < pageEntries.size() && entries.size() < maxItems;
                    entryIndex++) {
                DeletionTombstoneSnapshotPageCodec.IndexedEnvelope indexedEnvelope =
                        pageEntries.get(entryIndex);
                if (previousId != null
                        && indexedEnvelope.tombstoneId().compareTo(previousId) <= 0) {
                    throw new IllegalStateException("COS 恢复页间 UUID 顺序无效");
                }
                entries.add(readEnvelope(indexedEnvelope));
                previousId = indexedEnvelope.tombstoneId();
                globalPosition++;
            }
        }
        if (entries.isEmpty() || globalPosition > expectedItemCount) {
            throw new IllegalStateException("COS 恢复页索引未产生有效进展");
        }
        boolean complete = globalPosition == expectedItemCount;
        String nextCursor = complete ? null : encodeCursor(globalPosition, currentSnapshot);
        return new DeletionTombstoneRestoreBatch(entries, nextCursor, complete);
    }

    /**
     * 关闭只读 COS 客户端及连接池。
     */
    @Override
    @PreDestroy
    public void close() {
        cosClient.shutdown();
    }

    private OpenedSnapshot loadSnapshot(String expectedSnapshotId) {
        String objectKey = manifestObjectKey(expectedSnapshotId);
        ReadObject manifestObject = readEncryptedObject(
                objectKey,
                1,
                SignedDeletionTombstoneManifestVerifier.MAXIMUM_DOCUMENT_BYTES);
        SignedDeletionTombstoneManifestVerifier.VerifiedManifest verifiedManifest =
                manifestVerifier.verify(
                        manifestObject.content(),
                        properties.requireManifestPublicKey(),
                        expectedSnapshotId);
        byte[] sourceProofHash = sourceProof(
                objectKey, manifestObject, verifiedManifest);
        return new OpenedSnapshot(
                verifiedManifest,
                sourceProofHash,
                new DeletionTombstoneRestoreManifest(
                        verifiedManifest.snapshotId(),
                        verifiedManifest.expectedItemCount(),
                        verifiedManifest.aggregateHash(),
                        sourceProofHash));
    }

    private List<DeletionTombstoneSnapshotPageCodec.IndexedEnvelope> readPage(
            OpenedSnapshot snapshot,
            SignedDeletionTombstoneManifestVerifier.PageDescriptor descriptor) {
        ReadObject pageObject = readEncryptedObject(
                pageObjectKey(snapshot.verifiedManifest().snapshotId(), descriptor.pageNumber()),
                1,
                DeletionTombstoneSnapshotPageCodec.MAXIMUM_PAGE_BYTES);
        if (!digestService.constantTimeEquals(
                descriptor.pageHash(), digestService.sha256(pageObject.content()))) {
            throw new IllegalStateException("COS 恢复页摘要与签名根清单不一致");
        }
        return DeletionTombstoneSnapshotPageCodec.decode(
                pageObject.content(),
                snapshot.verifiedManifest().snapshotId(),
                descriptor.pageNumber(),
                descriptor.itemCount());
    }

    private DeletionTombstoneRestoreEntry readEnvelope(
            DeletionTombstoneSnapshotPageCodec.IndexedEnvelope indexedEnvelope) {
        ReadObject envelopeObject = readEncryptedObject(
                envelopeObjectKey(indexedEnvelope.tombstoneId()),
                MINIMUM_ENVELOPE_BYTES,
                MAXIMUM_ENVELOPE_BYTES);
        byte[] actualHash = digestService.sha256(envelopeObject.content());
        if (!digestService.constantTimeEquals(indexedEnvelope.envelopeHash(), actualHash)) {
            throw new IllegalStateException("COS 恢复信封摘要与签名页索引不一致");
        }
        EncryptedDeletionTombstoneEnvelope envelope =
                new EncryptedDeletionTombstoneEnvelope(
                        disasterRecoveryProperties.exportKeyId(),
                        envelopeObject.content(),
                        actualHash);
        return new DeletionTombstoneRestoreEntry(indexedEnvelope.tombstoneId(), envelope);
    }

    private ReadObject readEncryptedObject(
            String objectKey,
            int minimumBytes,
            int maximumBytes) {
        try (COSObject storedObject = cosClient.getObject(properties.bucket(), objectKey)) {
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
            return new ReadObject(content, etag, encryption);
        } catch (CosClientException | IOException exception) {
            throw unavailable();
        }
    }

    private byte[] sourceProof(
            String objectKey,
            ReadObject manifestObject,
            SignedDeletionTombstoneManifestVerifier.VerifiedManifest manifest) {
        String proof = SOURCE_PROOF_VERSION + "\n"
                + properties.region() + "\n"
                + properties.bucket() + "\n"
                + objectKey + "\n"
                + manifestObject.content().length + "\n"
                + manifestObject.etag() + "\n"
                + manifestObject.encryption() + "\n"
                + HexFormat.of().formatHex(digestService.sha256(manifest.signedPayload())) + "\n"
                + HexFormat.of().formatHex(digestService.sha256(manifest.signature())) + "\n"
                + HexFormat.of().formatHex(digestService.sha256(
                        properties.requireManifestPublicKey().getEncoded()));
        return digestService.sha256(proof.getBytes(UTF_8));
    }

    private String encodeCursor(int position, OpenedSnapshot snapshot) {
        byte[] positionBytes = ByteBuffer.allocate(Integer.BYTES).putInt(position).array();
        byte[] proof = cursorProof(positionBytes, snapshot.sourceProofHash());
        ByteBuffer cursorBytes = ByteBuffer.allocate(Integer.BYTES + CURSOR_PROOF_BYTES);
        cursorBytes.put(positionBytes);
        cursorBytes.put(proof, 0, CURSOR_PROOF_BYTES);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(cursorBytes.array());
    }

    private int decodeCursor(String cursor, OpenedSnapshot snapshot) {
        if (cursor == null) {
            return 0;
        }
        try {
            byte[] cursorBytes = Base64.getUrlDecoder().decode(cursor);
            if (cursorBytes.length != Integer.BYTES + CURSOR_PROOF_BYTES) {
                throw invalidCursor();
            }
            byte[] positionBytes = java.util.Arrays.copyOfRange(
                    cursorBytes, 0, Integer.BYTES);
            byte[] actualProof = java.util.Arrays.copyOfRange(
                    cursorBytes, Integer.BYTES, cursorBytes.length);
            byte[] expectedProof = java.util.Arrays.copyOf(
                    cursorProof(positionBytes, snapshot.sourceProofHash()),
                    CURSOR_PROOF_BYTES);
            if (!MessageDigest.isEqual(expectedProof, actualProof)) {
                throw invalidCursor();
            }
            int position = ByteBuffer.wrap(positionBytes).getInt();
            if (position <= 0) {
                throw invalidCursor();
            }
            return position;
        } catch (IllegalArgumentException exception) {
            throw invalidCursor();
        }
    }

    private byte[] cursorProof(byte[] positionBytes, byte[] sourceProofHash) {
        ByteArrayOutputStream proofInput = new ByteArrayOutputStream();
        try {
            proofInput.write(CURSOR_PROOF_VERSION.getBytes(UTF_8));
            proofInput.write(positionBytes);
            proofInput.write(sourceProofHash);
        } catch (IOException exception) {
            throw new IllegalStateException("COS 恢复游标摘要计算失败", exception);
        }
        return digestService.sha256(proofInput.toByteArray());
    }

    private OpenedSnapshot requireOpenedSnapshot(String snapshotId) {
        OpenedSnapshot snapshot = openedSnapshot;
        if (snapshot == null
                || !snapshot.verifiedManifest().snapshotId().equals(snapshotId)) {
            throw new IllegalStateException("COS 恢复快照尚未完成认证");
        }
        return snapshot;
    }

    private PagePosition locatePage(
            List<SignedDeletionTombstoneManifestVerifier.PageDescriptor> pages,
            int position) {
        int remaining = position;
        for (int pageIndex = 0; pageIndex < pages.size(); pageIndex++) {
            int itemCount = pages.get(pageIndex).itemCount();
            if (remaining < itemCount) {
                return new PagePosition(pageIndex, remaining);
            }
            remaining -= itemCount;
        }
        throw new IllegalStateException("COS 恢复分页游标无法定位到签名页");
    }

    private void verifyBucketVersioningOff() {
        try {
            BucketVersioningConfiguration versioning =
                    cosClient.getBucketVersioningConfiguration(properties.bucket());
            String status = versioning == null ? null : versioning.getStatus();
            if (status != null && !BucketVersioningConfiguration.OFF.equals(status)) {
                throw new IllegalStateException(
                        "COS 恢复 Bucket 必须保持版本控制从未开启");
            }
        } catch (CosClientException exception) {
            throw new IllegalStateException(
                    "无法验证 COS 恢复 Bucket 版本控制状态");
        }
    }

    private void requireRestoreMode() {
        if (!properties.enabled() || !disasterRecoveryProperties.restoreMode()) {
            throw new IllegalStateException("COS 可信恢复源与灾备恢复模式未同时开启");
        }
    }

    private void requireSeparateIdentity(
            TencentCosDisasterRecoveryProperties exportProperties) {
        Objects.requireNonNull(exportProperties, "COS 导出配置不能为空");
        if (!exportProperties.secretId().isBlank()
                && exportProperties.secretId().equals(properties.secretId())) {
            throw new IllegalStateException("COS 恢复身份不得复用日常导出身份");
        }
        if (exportProperties.enabled()
                && (!exportProperties.region().equals(properties.region())
                || !exportProperties.bucket().equals(properties.bucket()))) {
            throw new IllegalStateException("COS 导出与恢复必须指向同一独立灾备 Bucket");
        }
    }

    private static COSClient createClient(
            TencentCosDisasterRecoveryRestoreProperties properties) {
        COSCredentials credentials = properties.sessionToken().isBlank()
                ? new BasicCOSCredentials(properties.secretId(), properties.secretKey())
                : new BasicSessionCredentials(
                        properties.secretId(),
                        properties.secretKey(),
                        properties.sessionToken());
        ClientConfig clientConfig = new ClientConfig(new Region(properties.region()));
        clientConfig.setHttpProtocol(HttpProtocol.https);
        clientConfig.setConnectionRequestTimeout(toMilliseconds(properties.connectTimeout()));
        clientConfig.setConnectionTimeout(toMilliseconds(properties.connectTimeout()));
        clientConfig.setSocketTimeout(toMilliseconds(properties.readTimeout()));
        clientConfig.setRequestTimeout(toMilliseconds(properties.readTimeout()));
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

    private static String manifestObjectKey(String snapshotId) {
        return SNAPSHOT_PREFIX + snapshotId + MANIFEST_SUFFIX;
    }

    private static String pageObjectKey(String snapshotId, int pageNumber) {
        return SNAPSHOT_PREFIX + snapshotId + PAGE_PREFIX
                + "%08d".formatted(pageNumber) + PAGE_SUFFIX;
    }

    private static String envelopeObjectKey(UUID tombstoneId) {
        return ENVELOPE_PREFIX + tombstoneId + ENVELOPE_SUFFIX;
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

    private static IllegalStateException invalidCursor() {
        return new IllegalStateException("COS 恢复分页游标无效");
    }

    private static UpstreamFailureException unavailable() {
        return new UpstreamFailureException("可信 COS 灾备恢复源暂不可用");
    }

    private record ReadObject(byte[] content, String etag, String encryption) {
    }

    private record OpenedSnapshot(
            SignedDeletionTombstoneManifestVerifier.VerifiedManifest verifiedManifest,
            byte[] sourceProofHash,
            DeletionTombstoneRestoreManifest restoreManifest) {

        OpenedSnapshot {
            sourceProofHash = java.util.Arrays.copyOf(
                    sourceProofHash, sourceProofHash.length);
        }

        @Override
        public byte[] sourceProofHash() {
            return java.util.Arrays.copyOf(sourceProofHash, sourceProofHash.length);
        }
    }

    private record PagePosition(int pageIndex, int entryIndex) {
    }
}
