package com.aifriend.retention.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.http.client.methods.HttpRequestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.model.BucketVersioningConfiguration;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.COSObjectInputStream;
import com.qcloud.cos.model.COSObjectSummary;
import com.qcloud.cos.model.ListObjectsRequest;
import com.qcloud.cos.model.ObjectListing;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.model.PutObjectResult;

import com.aifriend.shared.error.UpstreamFailureException;
import com.aifriend.shared.security.DigestService;

class TencentCosDeletionTombstoneSnapshotPublisherTest {

    private static final UUID FIRST_ID = UUID.fromString(
            "11111111-2222-4333-8444-555555555555");
    private static final UUID SECOND_ID = UUID.fromString(
            "22222222-3333-4444-8555-666666666666");
    private static final String SNAPSHOT_ID = "snapshot-20260828";
    private static final String MANIFEST_KEY =
            "deletion-tombstone-snapshots/v1/" + SNAPSHOT_ID + "/manifest.json";

    private final Map<String, byte[]> objects = new LinkedHashMap<>();
    private final Map<String, String> encryptions = new LinkedHashMap<>();
    private final List<StoredPut> storedPuts = new ArrayList<>();
    private DigestService digestService;
    private ObjectMapper objectMapper;
    private COSClient cosClient;
    private KeyPair keyPair;

    @BeforeEach
    void setUp() throws Exception {
        digestService = new DigestService();
        objectMapper = new ObjectMapper();
        cosClient = mock(COSClient.class);
        keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        when(cosClient.getBucketVersioningConfiguration(bucket()))
                .thenReturn(new BucketVersioningConfiguration(
                        BucketVersioningConfiguration.OFF));
        when(cosClient.listObjects(any(ListObjectsRequest.class)))
                .thenAnswer(invocation -> listingFromObjects());
        when(cosClient.getObject(eq(bucket()), anyString()))
                .thenAnswer(invocation -> {
                    String key = invocation.getArgument(1);
                    byte[] content = objects.get(key);
                    if (content == null) {
                        throw TestCosResponses.noSuchKey();
                    }
                    return storedObject(
                            content,
                            encryptions.getOrDefault(key, "AES256"));
                });
        when(cosClient.putObject(any(PutObjectRequest.class)))
                .thenAnswer(invocation -> {
                    PutObjectRequest request = invocation.getArgument(0);
                    byte[] content = request.getInputStream().readAllBytes();
                    objects.put(request.getKey(), content);
                    encryptions.put(
                            request.getKey(),
                            request.getMetadata().getServerSideEncryption());
                    storedPuts.add(new StoredPut(
                            request.getKey(),
                            request.getCustomRequestHeaders()
                                    .get("x-cos-forbid-overwrite"),
                            request.getMetadata().getServerSideEncryption(),
                            request.getMetadata().getContentType(),
                            content));
                    return new PutObjectResult();
                });
    }

    @Test
    void shouldPublishPagesBeforeSignedManifestWithImmutableAesReadback() {
        addEnvelope(SECOND_ID, content((byte) 2));
        addEnvelope(FIRST_ID, content((byte) 1));

        TencentCosDeletionTombstoneSnapshotPublisher.SnapshotPublicationReceipt receipt;
        try (TencentCosDeletionTombstoneSnapshotPublisher publisher =
                publisher(options(keyPair, 1))) {
            receipt = publisher.publish();
        }

        assertThat(receipt.snapshotId()).isEqualTo(SNAPSHOT_ID);
        assertThat(receipt.itemCount()).isEqualTo(2);
        assertThat(receipt.pageCount()).isEqualTo(2);
        assertThat(receipt.manifestHash()).hasSize(32);
        assertThat(storedPuts).extracting(StoredPut::objectKey).containsExactly(
                pageKey(0), pageKey(1), MANIFEST_KEY);
        assertThat(storedPuts).allSatisfy(stored -> {
            assertThat(stored.forbidOverwrite()).isEqualTo("true");
            assertThat(stored.encryption()).isEqualTo("AES256");
        });
        assertThat(storedPuts.get(0).contentType()).isEqualTo("application/octet-stream");
        assertThat(storedPuts.get(2).contentType()).isEqualTo("application/json");

        SignedDeletionTombstoneManifestVerifier.VerifiedManifest manifest =
                new SignedDeletionTombstoneManifestVerifier(objectMapper).verify(
                        objects.get(MANIFEST_KEY), keyPair.getPublic(), SNAPSHOT_ID);
        assertThat(manifest.expectedItemCount()).isEqualTo(2);
        assertThat(manifest.pages()).hasSize(2);
        assertThat(DeletionTombstoneSnapshotPageCodec.decode(
                objects.get(pageKey(0)), SNAPSHOT_ID, 0, 1).get(0).tombstoneId())
                .isEqualTo(FIRST_ID);
        verify(cosClient, times(2)).listObjects(any(ListObjectsRequest.class));
        verify(cosClient, never()).deleteObject(anyString(), anyString());
    }

    @Test
    void shouldPublishValidEmptySnapshotWithoutPageObjects() {
        TencentCosDeletionTombstoneSnapshotPublisher.SnapshotPublicationReceipt receipt;
        try (TencentCosDeletionTombstoneSnapshotPublisher publisher =
                publisher(options(keyPair, 100))) {
            receipt = publisher.publish();
        }

        assertThat(receipt.itemCount()).isZero();
        assertThat(receipt.pageCount()).isZero();
        assertThat(storedPuts).extracting(StoredPut::objectKey)
                .containsExactly(MANIFEST_KEY);
        SignedDeletionTombstoneManifestVerifier.VerifiedManifest manifest =
                new SignedDeletionTombstoneManifestVerifier(objectMapper).verify(
                        objects.get(MANIFEST_KEY), keyPair.getPublic(), SNAPSHOT_ID);
        assertThat(manifest.pages()).isEmpty();
    }

    @Test
    void shouldTraverseEveryListingPageInBothStableInventoryPasses() {
        addEnvelope(FIRST_ID, content((byte) 1));
        addEnvelope(SECOND_ID, content((byte) 2));
        ObjectListing firstPage = listing(
                List.of(FIRST_ID), true, "marker-1");
        ObjectListing secondPage = listing(
                List.of(SECOND_ID), false, null);
        when(cosClient.listObjects(any(ListObjectsRequest.class)))
                .thenAnswer(invocation -> {
                    ListObjectsRequest request = invocation.getArgument(0);
                    return request.getMarker() == null ? firstPage : secondPage;
                });

        try (TencentCosDeletionTombstoneSnapshotPublisher publisher =
                publisher(options(keyPair, 100))) {
            assertThat(publisher.publish().itemCount()).isEqualTo(2);
        }

        verify(cosClient, times(4)).listObjects(any(ListObjectsRequest.class));
    }

    @Test
    void shouldRejectNonAdvancingListingMarkerBeforeUpload() {
        addEnvelope(FIRST_ID, content((byte) 1));
        addEnvelope(SECOND_ID, content((byte) 2));
        ObjectListing firstPage = listing(
                List.of(FIRST_ID), true, "marker-1");
        ObjectListing loopingPage = listing(
                List.of(SECOND_ID), true, "marker-1");
        when(cosClient.listObjects(any(ListObjectsRequest.class)))
                .thenReturn(firstPage, loopingPage);

        try (TencentCosDeletionTombstoneSnapshotPublisher publisher =
                publisher(options(keyPair, 100))) {
            assertThatThrownBy(publisher::publish)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("分页游标");
        }

        verify(cosClient, never()).putObject(any(PutObjectRequest.class));
    }

    @Test
    void shouldFailBeforeUploadWhenDoubleInventoryPassChanges() {
        addEnvelope(FIRST_ID, content((byte) 1));
        ObjectListing first = listingFromObjects();
        addEnvelope(SECOND_ID, content((byte) 2));
        ObjectListing second = listingFromObjects();
        when(cosClient.listObjects(any(ListObjectsRequest.class)))
                .thenReturn(first, second);

        try (TencentCosDeletionTombstoneSnapshotPublisher publisher =
                publisher(options(keyPair, 100))) {
            assertThatThrownBy(publisher::publish)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("对象集合");
        }

        verify(cosClient, never()).putObject(any(PutObjectRequest.class));
    }

    @Test
    void shouldFailClosedForMissingAesOrChangedEnvelopeMetadata() {
        addEnvelope(FIRST_ID, content((byte) 1));
        encryptions.put(envelopeKey(FIRST_ID), "");

        try (TencentCosDeletionTombstoneSnapshotPublisher publisher =
                publisher(options(keyPair, 100))) {
            assertThatThrownBy(publisher::publish)
                    .isInstanceOf(UpstreamFailureException.class);
        }

        assertThat(storedPuts).isEmpty();
    }

    @Test
    void shouldRejectUnexpectedObjectUnderEnvelopePrefix() {
        objects.put("deletion-tombstones/v1/not-an-envelope.txt", content((byte) 7));

        try (TencentCosDeletionTombstoneSnapshotPublisher publisher =
                publisher(options(keyPair, 100))) {
            assertThatThrownBy(publisher::publish)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("非信封对象");
        }

        assertThat(storedPuts).isEmpty();
    }

    @Test
    void shouldRefuseToEncodeUnsortedOrDuplicatePageEntries() {
        byte[] hash = digestService.sha256(content((byte) 1));

        assertThatThrownBy(() -> DeletionTombstoneSnapshotPageCodec.encode(
                SNAPSHOT_ID,
                0,
                List.of(
                        new DeletionTombstoneSnapshotPageCodec.IndexedEnvelope(
                                SECOND_ID, hash),
                        new DeletionTombstoneSnapshotPageCodec.IndexedEnvelope(
                                FIRST_ID, hash))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DeletionTombstoneSnapshotPageCodec.encode(
                SNAPSHOT_ID,
                0,
                List.of(
                        new DeletionTombstoneSnapshotPageCodec.IndexedEnvelope(
                                FIRST_ID, hash),
                        new DeletionTombstoneSnapshotPageCodec.IndexedEnvelope(
                                FIRST_ID, hash))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldRejectExistingConflictingPageAndNeverPublishManifest() {
        addEnvelope(FIRST_ID, content((byte) 1));
        objects.put(pageKey(0), new byte[] {1, 2, 3});
        encryptions.put(pageKey(0), "AES256");

        try (TencentCosDeletionTombstoneSnapshotPublisher publisher =
                publisher(options(keyPair, 100))) {
            assertThatThrownBy(publisher::publish)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("固定内容冲突");
        }

        assertThat(objects).doesNotContainKey(MANIFEST_KEY);
    }

    @Test
    void shouldRejectMismatchedSigningPairBeforeAnyCosOperation() throws Exception {
        KeyPair other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();

        assertThatThrownBy(() -> options(
                keyPair.getPrivate().getEncoded(),
                other.getPublic().getEncoded(),
                100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不匹配");
        verify(cosClient, never()).listObjects(any(ListObjectsRequest.class));
        verify(cosClient, never()).putObject(any(PutObjectRequest.class));
    }

    @Test
    void shouldRejectVersionedBucketAndCloseClient() {
        when(cosClient.getBucketVersioningConfiguration(bucket()))
                .thenReturn(new BucketVersioningConfiguration(
                        BucketVersioningConfiguration.ENABLED));

        assertThatThrownBy(() -> publisher(options(keyPair, 100)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("版本控制");
        verify(cosClient).shutdown();
    }

    private TencentCosDeletionTombstoneSnapshotPublisher publisher(
            TencentCosSnapshotPublisherOptions options) {
        return new TencentCosDeletionTombstoneSnapshotPublisher(
                options, digestService, objectMapper, cosClient);
    }

    private void addEnvelope(UUID tombstoneId, byte[] content) {
        objects.put(envelopeKey(tombstoneId), content);
        encryptions.put(envelopeKey(tombstoneId), "AES256");
    }

    private ObjectListing listingFromObjects() {
        ObjectListing listing = new ObjectListing();
        objects.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("deletion-tombstones/v1/"))
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> listing.getObjectSummaries().add(summary(
                        entry.getKey(), entry.getValue())));
        listing.setTruncated(false);
        return listing;
    }

    private ObjectListing listing(
            List<UUID> tombstoneIds,
            boolean truncated,
            String nextMarker) {
        ObjectListing listing = new ObjectListing();
        tombstoneIds.stream()
                .sorted(Comparator.naturalOrder())
                .forEach(tombstoneId -> {
                    String key = envelopeKey(tombstoneId);
                    listing.getObjectSummaries().add(summary(key, objects.get(key)));
                });
        listing.setTruncated(truncated);
        listing.setNextMarker(nextMarker);
        return listing;
    }

    private COSObjectSummary summary(String key, byte[] content) {
        COSObjectSummary summary = new COSObjectSummary();
        summary.setKey(key);
        summary.setSize(content.length);
        summary.setETag(etag(content));
        return summary;
    }

    private COSObject storedObject(byte[] content, String encryption) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(content.length);
        metadata.setServerSideEncryption(encryption);
        metadata.setETag(etag(content));
        COSObject object = new COSObject();
        object.setObjectMetadata(metadata);
        object.setObjectContent(new COSObjectInputStream(
                new ByteArrayInputStream(content), mock(HttpRequestBase.class)));
        return object;
    }

    private String etag(byte[] content) {
        return java.util.HexFormat.of().formatHex(digestService.sha256(content))
                .substring(0, 32);
    }

    private TencentCosSnapshotPublisherOptions options(KeyPair pair, int pageSize) {
        return options(pair.getPrivate().getEncoded(), pair.getPublic().getEncoded(), pageSize);
    }

    private TencentCosSnapshotPublisherOptions options(
            byte[] privateKey,
            byte[] publicKey,
            int pageSize) {
        return new TencentCosSnapshotPublisherOptions(
                "ap-shanghai",
                bucket(),
                "publisher-secret-id",
                "publisher-secret-key",
                "",
                "export-secret-id",
                "restore-secret-id",
                SNAPSHOT_ID,
                Instant.parse("2026-08-28T00:00:00Z"),
                pageSize,
                Base64.getEncoder().encodeToString(privateKey),
                Base64.getEncoder().encodeToString(publicKey),
                Duration.ofSeconds(2),
                Duration.ofSeconds(5));
    }

    private static byte[] content(byte value) {
        byte[] content = new byte[96];
        java.util.Arrays.fill(content, value);
        return content;
    }

    private static String envelopeKey(UUID tombstoneId) {
        return "deletion-tombstones/v1/" + tombstoneId + ".envelope";
    }

    private static String pageKey(int pageNumber) {
        return "deletion-tombstone-snapshots/v1/" + SNAPSHOT_ID
                + "/pages/" + "%08d".formatted(pageNumber) + ".index";
    }

    private static String bucket() {
        return "ai-friend-tombstones-1250000000";
    }

    private record StoredPut(
            String objectKey,
            String forbidOverwrite,
            String encryption,
            String contentType,
            byte[] content) {
    }

    private static final class TestCosResponses {

        private TestCosResponses() {
        }

        private static com.qcloud.cos.exception.CosServiceException noSuchKey() {
            com.qcloud.cos.exception.CosServiceException exception =
                    new com.qcloud.cos.exception.CosServiceException("missing");
            exception.setErrorCode("NoSuchKey");
            exception.setStatusCode(404);
            return exception;
        }
    }
}
