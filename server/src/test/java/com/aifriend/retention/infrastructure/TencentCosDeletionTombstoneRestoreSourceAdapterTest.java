package com.aifriend.retention.infrastructure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.http.client.methods.HttpRequestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.model.BucketVersioningConfiguration;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.COSObjectInputStream;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;

import com.aifriend.retention.application.DeletionTombstoneRestoreBatch;
import com.aifriend.retention.application.DeletionTombstoneRestoreManifest;
import com.aifriend.retention.application.DisasterRecoveryProperties;
import com.aifriend.retention.application.TencentCosDisasterRecoveryProperties;
import com.aifriend.retention.application.TencentCosDisasterRecoveryRestoreProperties;
import com.aifriend.shared.error.UpstreamFailureException;
import com.aifriend.shared.security.DigestService;

class TencentCosDeletionTombstoneRestoreSourceAdapterTest {

    private static final String SNAPSHOT_ID = "snapshot:2026-08-28:001";
    private static final String ROOT_KEY =
            "deletion-tombstone-snapshots/v1/" + SNAPSHOT_ID + "/manifest.json";
    private static final String PAGE_KEY =
            "deletion-tombstone-snapshots/v1/" + SNAPSHOT_ID
                    + "/pages/00000000.index";
    private static final UUID FIRST_ID = UUID.fromString(
            "11111111-2222-4333-8444-555555555551");
    private static final UUID SECOND_ID = UUID.fromString(
            "11111111-2222-4333-8444-555555555552");

    private ObjectMapper objectMapper;
    private DigestService digestService;
    private COSClient cosClient;
    private KeyPair manifestKeyPair;
    private byte[] firstEnvelope;
    private byte[] secondEnvelope;
    private byte[] page;
    private byte[] root;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        digestService = new DigestService();
        cosClient = mock(COSClient.class);
        manifestKeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        firstEnvelope = envelope((byte) 1);
        secondEnvelope = envelope((byte) 2);
        page = page(List.of(
                new DeletionTombstoneSnapshotPageCodec.IndexedEnvelope(
                        FIRST_ID, digestService.sha256(firstEnvelope)),
                new DeletionTombstoneSnapshotPageCodec.IndexedEnvelope(
                        SECOND_ID, digestService.sha256(secondEnvelope))));
        root = signedRoot(page, manifestKeyPair.getPrivate(), Instant.parse(
                "2026-08-28T00:00:00Z"));
        when(cosClient.getBucketVersioningConfiguration(bucket()))
                .thenReturn(new BucketVersioningConfiguration(
                        BucketVersioningConfiguration.OFF));
        prepareObject(ROOT_KEY, root);
        prepareObject(PAGE_KEY, page);
        prepareObject(envelopeKey(FIRST_ID), firstEnvelope);
        prepareObject(envelopeKey(SECOND_ID), secondEnvelope);
    }

    @Test
    void shouldOnlyExcludeAutomatedTestProfile() {
        Profile profile = TencentCosDeletionTombstoneRestoreSourceAdapter.class
                .getAnnotation(Profile.class);

        assertArrayEquals(new String[] {"!test"}, profile.value());
    }

    @Test
    void shouldVerifySignedManifestAndReadOnlyBoundedPages() {
        TencentCosDeletionTombstoneRestoreSourceAdapter adapter = adapter();

        DeletionTombstoneRestoreManifest manifest = adapter.openSnapshot(SNAPSHOT_ID);
        DeletionTombstoneRestoreBatch firstBatch = adapter.readBatch(
                SNAPSHOT_ID, null, 1);
        DeletionTombstoneRestoreBatch secondBatch = adapter.readBatch(
                SNAPSHOT_ID, firstBatch.nextCursor(), 1);

        assertEquals(SNAPSHOT_ID, manifest.snapshotId());
        assertEquals(2L, manifest.expectedItemCount());
        assertEquals(32, manifest.sourceProofHash().length);
        assertEquals(List.of(FIRST_ID), firstBatch.entries().stream()
                .map(entry -> entry.tombstoneId()).toList());
        assertFalse(firstBatch.complete());
        assertEquals(List.of(SECOND_ID), secondBatch.entries().stream()
                .map(entry -> entry.tombstoneId()).toList());
        assertTrue(secondBatch.complete());
        verify(cosClient, never()).putObject(any(PutObjectRequest.class));
    }

    @Test
    void shouldAdvanceCursorAcrossSignedPageBoundary() throws Exception {
        byte[] firstPage = DeletionTombstoneSnapshotPageCodec.encode(
                SNAPSHOT_ID,
                0,
                List.of(new DeletionTombstoneSnapshotPageCodec.IndexedEnvelope(
                        FIRST_ID, digestService.sha256(firstEnvelope))));
        byte[] secondPage = DeletionTombstoneSnapshotPageCodec.encode(
                SNAPSHOT_ID,
                1,
                List.of(new DeletionTombstoneSnapshotPageCodec.IndexedEnvelope(
                        SECOND_ID, digestService.sha256(secondEnvelope))));
        byte[] payload = SignedDeletionTombstoneManifestVerifier.encodePayload(
                SNAPSHOT_ID,
                Instant.parse("2026-08-28T00:00:00Z"),
                2L,
                1,
                aggregateHash(),
                List.of(
                        new SignedDeletionTombstoneManifestVerifier.PageDescriptor(
                                0, 1, digestService.sha256(firstPage)),
                        new SignedDeletionTombstoneManifestVerifier.PageDescriptor(
                                1, 1, digestService.sha256(secondPage))));
        prepareObject(ROOT_KEY, objectMapper.writeValueAsBytes(rootDocument(
                payload,
                signature(payload, manifestKeyPair.getPrivate()))));
        prepareObject(PAGE_KEY, firstPage);
        prepareObject(
                "deletion-tombstone-snapshots/v1/" + SNAPSHOT_ID
                        + "/pages/00000001.index",
                secondPage);
        TencentCosDeletionTombstoneRestoreSourceAdapter adapter = adapter();

        adapter.openSnapshot(SNAPSHOT_ID);
        DeletionTombstoneRestoreBatch firstBatch = adapter.readBatch(
                SNAPSHOT_ID, null, 1);
        DeletionTombstoneRestoreBatch secondBatch = adapter.readBatch(
                SNAPSHOT_ID, firstBatch.nextCursor(), 1);

        assertEquals(FIRST_ID, firstBatch.entries().get(0).tombstoneId());
        assertEquals(SECOND_ID, secondBatch.entries().get(0).tombstoneId());
        assertTrue(secondBatch.complete());
    }

    @Test
    void shouldFailClosedForInvalidSignatureOrUnknownRootField() throws Exception {
        byte[] manifestPayload = payload(page);
        KeyPair wrongKeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        prepareObject(ROOT_KEY, objectMapper.writeValueAsBytes(rootDocument(
                manifestPayload,
                signature(manifestPayload, wrongKeyPair.getPrivate()))));
        assertThrows(IllegalStateException.class, () -> adapter().openSnapshot(SNAPSHOT_ID));

        Map<String, Object> document = rootDocument(
                manifestPayload,
                signature(manifestPayload, manifestKeyPair.getPrivate()));
        document.put("unexpected", true);
        prepareObject(ROOT_KEY, objectMapper.writeValueAsBytes(document));
        assertThrows(IllegalStateException.class, () -> adapter().openSnapshot(SNAPSHOT_ID));
    }

    @Test
    void shouldRejectDuplicateSignedDocumentFields() {
        String rootText = new String(root, java.nio.charset.StandardCharsets.UTF_8);
        String duplicateRoot = rootText.replaceFirst(
                "\\{",
                "{\\\"schemaVersion\\\":\\\"AIFDRS01\\\",");
        prepareObject(
                ROOT_KEY,
                duplicateRoot.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThrows(IllegalStateException.class, () -> adapter().openSnapshot(SNAPSHOT_ID));
    }

    @Test
    void shouldRejectNonSequentialSignedPageDescriptor() throws Exception {
        byte[] payload = SignedDeletionTombstoneManifestVerifier.encodePayload(
                SNAPSHOT_ID,
                Instant.parse("2026-08-28T00:00:00Z"),
                2L,
                2,
                aggregateHash(),
                List.of(new SignedDeletionTombstoneManifestVerifier.PageDescriptor(
                        1, 2, digestService.sha256(page))));
        byte[] invalidRoot = objectMapper.writeValueAsBytes(
                rootDocument(payload, signature(payload, manifestKeyPair.getPrivate())));
        prepareObject(ROOT_KEY, invalidRoot);

        assertThrows(IllegalStateException.class, () -> adapter().openSnapshot(SNAPSHOT_ID));
    }

    @Test
    void shouldRejectDuplicateUuidInsideSignedPage() throws Exception {
        byte[] duplicatePage = duplicateUuidPage();
        byte[] payload = SignedDeletionTombstoneManifestVerifier.encodePayload(
                SNAPSHOT_ID,
                Instant.parse("2026-08-28T00:00:00Z"),
                2L,
                2,
                aggregateHash(),
                List.of(new SignedDeletionTombstoneManifestVerifier.PageDescriptor(
                        0, 2, digestService.sha256(duplicatePage))));
        prepareObject(ROOT_KEY, objectMapper.writeValueAsBytes(rootDocument(
                payload,
                signature(payload, manifestKeyPair.getPrivate()))));
        prepareObject(PAGE_KEY, duplicatePage);
        TencentCosDeletionTombstoneRestoreSourceAdapter adapter = adapter();

        adapter.openSnapshot(SNAPSHOT_ID);

        assertThrows(
                IllegalStateException.class,
                () -> adapter.readBatch(SNAPSHOT_ID, null, 2));
    }

    @Test
    void shouldFailClosedForTamperedPageOrEnvelope() {
        TencentCosDeletionTombstoneRestoreSourceAdapter pageAdapter = adapter();
        pageAdapter.openSnapshot(SNAPSHOT_ID);
        byte[] tamperedPage = page.clone();
        tamperedPage[tamperedPage.length - 1] ^= 1;
        prepareObject(PAGE_KEY, tamperedPage);
        assertThrows(
                IllegalStateException.class,
                () -> pageAdapter.readBatch(SNAPSHOT_ID, null, 2));

        prepareObject(PAGE_KEY, page);
        TencentCosDeletionTombstoneRestoreSourceAdapter envelopeAdapter = adapter();
        envelopeAdapter.openSnapshot(SNAPSHOT_ID);
        byte[] tamperedEnvelope = firstEnvelope.clone();
        tamperedEnvelope[0] ^= 1;
        prepareObject(envelopeKey(FIRST_ID), tamperedEnvelope);
        assertThrows(
                IllegalStateException.class,
                () -> envelopeAdapter.readBatch(SNAPSHOT_ID, null, 2));
    }

    @Test
    void shouldRejectTamperedCursorAndRootChangeDuringPaging() throws Exception {
        TencentCosDeletionTombstoneRestoreSourceAdapter cursorAdapter = adapter();
        cursorAdapter.openSnapshot(SNAPSHOT_ID);
        DeletionTombstoneRestoreBatch firstBatch = cursorAdapter.readBatch(
                SNAPSHOT_ID, null, 1);
        char replacement = firstBatch.nextCursor().charAt(0) == 'A' ? 'B' : 'A';
        String tamperedCursor = replacement + firstBatch.nextCursor().substring(1);
        assertThrows(
                IllegalStateException.class,
                () -> cursorAdapter.readBatch(SNAPSHOT_ID, tamperedCursor, 1));

        byte[] changedRoot = signedRoot(
                page,
                manifestKeyPair.getPrivate(),
                Instant.parse("2026-08-28T00:00:01Z"));
        AtomicInteger rootReads = new AtomicInteger();
        doAnswer(invocation -> storedObject(
                rootReads.getAndIncrement() == 0 ? root : changedRoot))
                .when(cosClient).getObject(bucket(), ROOT_KEY);
        TencentCosDeletionTombstoneRestoreSourceAdapter changedRootAdapter = adapter();
        changedRootAdapter.openSnapshot(SNAPSHOT_ID);
        assertThrows(
                IllegalStateException.class,
                () -> changedRootAdapter.readBatch(SNAPSHOT_ID, null, 1));
    }

    @Test
    void shouldFailClosedForMissingPermissionOrUnencryptedObject() {
        CosServiceException denied = new CosServiceException("denied");
        denied.setStatusCode(403);
        when(cosClient.getObject(bucket(), ROOT_KEY)).thenThrow(denied);
        assertThrows(UpstreamFailureException.class, () -> adapter().openSnapshot(SNAPSHOT_ID));

        prepareObject(ROOT_KEY, root, "");
        assertThrows(UpstreamFailureException.class, () -> adapter().openSnapshot(SNAPSHOT_ID));
    }

    @Test
    void shouldRejectVersionedBucketAndReusedExportIdentity() {
        when(cosClient.getBucketVersioningConfiguration(bucket()))
                .thenReturn(new BucketVersioningConfiguration(
                        BucketVersioningConfiguration.ENABLED));
        assertThrows(IllegalStateException.class, this::adapter);
        verify(cosClient).shutdown();

        COSClient separateClient = mock(COSClient.class);
        when(separateClient.getBucketVersioningConfiguration(bucket()))
                .thenReturn(new BucketVersioningConfiguration(
                        BucketVersioningConfiguration.OFF));
        assertThrows(
                IllegalStateException.class,
                () -> new TencentCosDeletionTombstoneRestoreSourceAdapter(
                        restoreProperties(),
                        exportProperties("restore-secret-id"),
                        coreProperties(),
                        digestService,
                        objectMapper,
                        separateClient));
    }

    private TencentCosDeletionTombstoneRestoreSourceAdapter adapter() {
        return new TencentCosDeletionTombstoneRestoreSourceAdapter(
                restoreProperties(),
                exportProperties("export-secret-id"),
                coreProperties(),
                digestService,
                objectMapper,
                cosClient);
    }

    private TencentCosDisasterRecoveryRestoreProperties restoreProperties() {
        return new TencentCosDisasterRecoveryRestoreProperties(
                true,
                "ap-shanghai",
                bucket(),
                "restore-secret-id",
                "restore-secret-key",
                "",
                Base64.getEncoder().encodeToString(
                        manifestKeyPair.getPublic().getEncoded()),
                Duration.ofSeconds(2),
                Duration.ofSeconds(5));
    }

    private static TencentCosDisasterRecoveryProperties exportProperties(
            String secretId) {
        return new TencentCosDisasterRecoveryProperties(
                true,
                "ap-shanghai",
                bucket(),
                secretId,
                "export-secret-key",
                "",
                Duration.ofSeconds(2),
                Duration.ofSeconds(5));
    }

    private static DisasterRecoveryProperties coreProperties() {
        return new DisasterRecoveryProperties(
                true,
                SNAPSHOT_ID,
                false,
                "dr-key-v1",
                Base64.getEncoder().encodeToString(new byte[32]));
    }

    private byte[] signedRoot(
            byte[] pageContent,
            PrivateKey privateKey,
            Instant createdAt) throws Exception {
        byte[] payload = SignedDeletionTombstoneManifestVerifier.encodePayload(
                SNAPSHOT_ID,
                createdAt,
                2L,
                2,
                aggregateHash(),
                List.of(new SignedDeletionTombstoneManifestVerifier.PageDescriptor(
                        0, 2, digestService.sha256(pageContent))));
        return objectMapper.writeValueAsBytes(
                rootDocument(payload, signature(payload, privateKey)));
    }

    private byte[] payload(byte[] pageContent) {
        return SignedDeletionTombstoneManifestVerifier.encodePayload(
                SNAPSHOT_ID,
                Instant.parse("2026-08-28T00:00:00Z"),
                2L,
                2,
                aggregateHash(),
                List.of(new SignedDeletionTombstoneManifestVerifier.PageDescriptor(
                        0, 2, digestService.sha256(pageContent))));
    }

    private Map<String, Object> rootDocument(byte[] payload, byte[] signature) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("schemaVersion", SignedDeletionTombstoneManifestVerifier.SCHEMA_VERSION);
        document.put("payloadBase64", Base64.getEncoder().encodeToString(payload));
        document.put("signatureAlgorithm", "Ed25519");
        document.put("signatureBase64", Base64.getEncoder().encodeToString(signature));
        return document;
    }

    private static byte[] signature(byte[] payload, PrivateKey privateKey) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(privateKey);
        signer.update(payload);
        return signer.sign();
    }

    private byte[] page(
            List<DeletionTombstoneSnapshotPageCodec.IndexedEnvelope> entries) {
        return DeletionTombstoneSnapshotPageCodec.encode(SNAPSHOT_ID, 0, entries);
    }

    private byte[] duplicateUuidPage() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.write("AIFDRP01".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            byte[] snapshotBytes = SNAPSHOT_ID.getBytes(
                    java.nio.charset.StandardCharsets.UTF_8);
            output.writeInt(snapshotBytes.length);
            output.write(snapshotBytes);
            output.writeInt(0);
            output.writeInt(2);
            output.writeLong(FIRST_ID.getMostSignificantBits());
            output.writeLong(FIRST_ID.getLeastSignificantBits());
            output.write(digestService.sha256(firstEnvelope));
            output.writeLong(FIRST_ID.getMostSignificantBits());
            output.writeLong(FIRST_ID.getLeastSignificantBits());
            output.write(digestService.sha256(secondEnvelope));
        }
        return bytes.toByteArray();
    }

    private byte[] aggregateHash() {
        return digestService.sha256("aggregate-v1");
    }

    private void prepareObject(String objectKey, byte[] content) {
        prepareObject(objectKey, content, "AES256");
    }

    private void prepareObject(String objectKey, byte[] content, String encryption) {
        doAnswer(invocation -> storedObject(content, encryption))
                .when(cosClient).getObject(bucket(), objectKey);
    }

    private static COSObject storedObject(byte[] content) {
        return storedObject(content, "AES256");
    }

    private static COSObject storedObject(byte[] content, String encryption) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(content.length);
        metadata.setServerSideEncryption(encryption);
        metadata.setETag("0123456789abcdef0123456789abcdef");
        COSObject object = new COSObject();
        object.setObjectMetadata(metadata);
        object.setObjectContent(new COSObjectInputStream(
                new ByteArrayInputStream(content), mock(HttpRequestBase.class)));
        return object;
    }

    private static byte[] envelope(byte value) {
        byte[] content = new byte[96];
        java.util.Arrays.fill(content, value);
        return content;
    }

    private static String envelopeKey(UUID tombstoneId) {
        return "deletion-tombstones/v1/" + tombstoneId + ".envelope";
    }

    private static String bucket() {
        return "ai-friend-tombstones-1250000000";
    }
}
