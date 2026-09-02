package com.aifriend.retention.infrastructure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

import org.apache.http.client.methods.HttpRequestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.Profile;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.model.BucketVersioningConfiguration;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.COSObjectInputStream;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.model.PutObjectResult;

import com.aifriend.retention.application.DeletionTombstoneExportReceipt;
import com.aifriend.retention.application.DisasterRecoveryProperties;
import com.aifriend.retention.application.EncryptedDeletionTombstoneEnvelope;
import com.aifriend.retention.application.TencentCosDisasterRecoveryProperties;
import com.aifriend.shared.error.UpstreamFailureException;
import com.aifriend.shared.security.DigestService;

class TencentCosDeletionTombstoneExportAdapterTest {

    private static final UUID TOMBSTONE_ID = UUID.fromString(
            "11111111-2222-4333-8444-555555555555");
    private static final String OBJECT_KEY =
            "deletion-tombstones/v1/11111111-2222-4333-8444-555555555555.envelope";

    private DigestService digestService;
    private COSClient cosClient;

    @BeforeEach
    void setUp() {
        digestService = new DigestService();
        cosClient = mock(COSClient.class);
        when(cosClient.getBucketVersioningConfiguration(bucket()))
                .thenReturn(new BucketVersioningConfiguration(
                        BucketVersioningConfiguration.OFF));
    }

    @Test
    void shouldOnlyExcludeAutomatedTestProfile() {
        Profile profile = TencentCosDeletionTombstoneExportAdapter.class
                .getAnnotation(Profile.class);

        assertArrayEquals(new String[] {"!test"}, profile.value());
    }

    @Test
    void shouldUploadWithImmutableEncryptedRequestAndVerifyFullReadback() throws Exception {
        byte[] content = content((byte) 7);
        when(cosClient.getObject(bucket(), OBJECT_KEY))
                .thenThrow(noSuchKey())
                .thenReturn(storedObject(content, content.length));
        when(cosClient.putObject(any(PutObjectRequest.class)))
                .thenReturn(new PutObjectResult());
        TencentCosDeletionTombstoneExportAdapter adapter = adapter();

        DeletionTombstoneExportReceipt receipt = adapter.export(
                TOMBSTONE_ID, envelope(content));

        assertArrayEquals(digestService.sha256(content), receipt.storedEnvelopeHash());
        assertEquals(32, receipt.receiptProof().length);
        ArgumentCaptor<PutObjectRequest> requestCaptor =
                ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(cosClient).putObject(requestCaptor.capture());
        PutObjectRequest request = requestCaptor.getValue();
        assertEquals(bucket(), request.getBucketName());
        assertEquals(OBJECT_KEY, request.getKey());
        assertEquals("true", request.getCustomRequestHeaders()
                .get("x-cos-forbid-overwrite"));
        assertEquals("AES256", request.getMetadata().getServerSideEncryption());
        assertEquals("application/octet-stream", request.getMetadata().getContentType());
        assertEquals("no-store", request.getMetadata().getCacheControl());
        assertEquals(content.length, request.getMetadata().getContentLength());
        assertArrayEquals(content, request.getInputStream().readAllBytes());
    }

    @Test
    void shouldReuseExistingIdenticalObjectWithoutUploading() {
        byte[] content = content((byte) 3);
        when(cosClient.getObject(bucket(), OBJECT_KEY))
                .thenReturn(storedObject(content, content.length));
        TencentCosDeletionTombstoneExportAdapter adapter = adapter();

        DeletionTombstoneExportReceipt receipt = adapter.export(
                TOMBSTONE_ID, envelope(content));

        assertArrayEquals(digestService.sha256(content), receipt.storedEnvelopeHash());
        verify(cosClient, never()).putObject(any(PutObjectRequest.class));
    }

    @Test
    void shouldReadConcurrentWinnerAfterFileAlreadyExists() {
        byte[] content = content((byte) 9);
        when(cosClient.getObject(bucket(), OBJECT_KEY))
                .thenThrow(noSuchKey())
                .thenReturn(storedObject(content, content.length));
        when(cosClient.putObject(any(PutObjectRequest.class)))
                .thenThrow(alreadyExists());
        TencentCosDeletionTombstoneExportAdapter adapter = adapter();

        DeletionTombstoneExportReceipt receipt = adapter.export(
                TOMBSTONE_ID, envelope(content));

        assertArrayEquals(digestService.sha256(content), receipt.storedEnvelopeHash());
        verify(cosClient, times(2)).getObject(bucket(), OBJECT_KEY);
    }

    @Test
    void shouldFailClosedWhenExistingObjectConflicts() {
        byte[] expected = content((byte) 1);
        byte[] conflicting = content((byte) 2);
        when(cosClient.getObject(bucket(), OBJECT_KEY))
                .thenReturn(storedObject(conflicting, conflicting.length));
        TencentCosDeletionTombstoneExportAdapter adapter = adapter();

        assertThrows(
                IllegalStateException.class,
                () -> adapter.export(TOMBSTONE_ID, envelope(expected)));
        verify(cosClient, never()).putObject(any(PutObjectRequest.class));
    }

    @Test
    void shouldFailClosedForTruncatedReadbackOrMissingEncryption() {
        byte[] content = content((byte) 5);
        when(cosClient.getObject(bucket(), OBJECT_KEY))
                .thenReturn(storedObject(content, content.length + 1));
        TencentCosDeletionTombstoneExportAdapter truncatedAdapter = adapter();

        assertThrows(
                UpstreamFailureException.class,
                () -> truncatedAdapter.export(TOMBSTONE_ID, envelope(content)));

        COSClient unencryptedClient = mock(COSClient.class);
        when(unencryptedClient.getBucketVersioningConfiguration(bucket()))
                .thenReturn(new BucketVersioningConfiguration(
                        BucketVersioningConfiguration.OFF));
        when(unencryptedClient.getObject(bucket(), OBJECT_KEY))
                .thenReturn(storedObject(content, content.length, ""));
        TencentCosDeletionTombstoneExportAdapter unencryptedAdapter =
                adapter(unencryptedClient);
        assertThrows(
                UpstreamFailureException.class,
                () -> unencryptedAdapter.export(TOMBSTONE_ID, envelope(content)));
    }

    @Test
    void shouldRejectEnabledOrPreviouslyEnabledBucketVersioning() {
        when(cosClient.getBucketVersioningConfiguration(bucket()))
                .thenReturn(new BucketVersioningConfiguration(
                        BucketVersioningConfiguration.ENABLED));

        assertThrows(IllegalStateException.class, this::adapter);
        verify(cosClient).shutdown();

        COSClient suspendedClient = mock(COSClient.class);
        when(suspendedClient.getBucketVersioningConfiguration(bucket()))
                .thenReturn(new BucketVersioningConfiguration(
                        BucketVersioningConfiguration.SUSPENDED));
        assertThrows(
                IllegalStateException.class,
                () -> adapter(suspendedClient));
        verify(suspendedClient).shutdown();
    }

    @Test
    void shouldFailClosedWhenVersioningCannotBeVerified() {
        when(cosClient.getBucketVersioningConfiguration(bucket()))
                .thenThrow(new CosServiceException("denied"));

        assertThrows(IllegalStateException.class, this::adapter);
        verify(cosClient).shutdown();
    }

    @Test
    void shouldRejectMismatchedEnvelopeHashBeforeStorageCall() {
        TencentCosDeletionTombstoneExportAdapter adapter = adapter();
        byte[] content = content((byte) 4);
        EncryptedDeletionTombstoneEnvelope invalid =
                new EncryptedDeletionTombstoneEnvelope(
                        "dr-key-v1", content, new byte[32]);

        assertThrows(
                IllegalArgumentException.class,
                () -> adapter.export(TOMBSTONE_ID, invalid));
        verify(cosClient, never()).getObject(bucket(), OBJECT_KEY);
    }

    @Test
    void shouldRequireCoreExportSwitchAndCloseExplicitly() {
        DisasterRecoveryProperties disabled = new DisasterRecoveryProperties(
                false, "", false, "", "");
        assertThrows(
                IllegalStateException.class,
                () -> new TencentCosDeletionTombstoneExportAdapter(
                        cosProperties(), disabled, digestService, cosClient));

        TencentCosDeletionTombstoneExportAdapter adapter = adapter();
        adapter.close();
        verify(cosClient).shutdown();
    }

    private TencentCosDeletionTombstoneExportAdapter adapter() {
        return adapter(cosClient);
    }

    private TencentCosDeletionTombstoneExportAdapter adapter(COSClient client) {
        return new TencentCosDeletionTombstoneExportAdapter(
                cosProperties(), coreProperties(), digestService, client);
    }

    private EncryptedDeletionTombstoneEnvelope envelope(byte[] content) {
        return new EncryptedDeletionTombstoneEnvelope(
                "dr-key-v1", content, digestService.sha256(content));
    }

    private static TencentCosDisasterRecoveryProperties cosProperties() {
        return new TencentCosDisasterRecoveryProperties(
                true,
                "ap-shanghai",
                bucket(),
                "secret-id-value",
                "secret-key-value",
                "",
                Duration.ofSeconds(2),
                Duration.ofSeconds(5));
    }

    private static DisasterRecoveryProperties coreProperties() {
        return new DisasterRecoveryProperties(
                false,
                "",
                true,
                "dr-key-v1",
                Base64.getEncoder().encodeToString(new byte[32]));
    }

    private static COSObject storedObject(byte[] content, long declaredLength) {
        return storedObject(content, declaredLength, "AES256");
    }

    private static COSObject storedObject(
            byte[] content,
            long declaredLength,
            String encryption) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(declaredLength);
        metadata.setServerSideEncryption(encryption);
        metadata.setETag("0123456789abcdef0123456789abcdef");
        COSObject object = new COSObject();
        object.setObjectMetadata(metadata);
        object.setObjectContent(new COSObjectInputStream(
                new ByteArrayInputStream(content), mock(HttpRequestBase.class)));
        return object;
    }

    private static byte[] content(byte value) {
        byte[] content = new byte[96];
        java.util.Arrays.fill(content, value);
        return content;
    }

    private static CosServiceException noSuchKey() {
        CosServiceException exception = new CosServiceException("missing");
        exception.setErrorCode("NoSuchKey");
        exception.setStatusCode(404);
        return exception;
    }

    private static CosServiceException alreadyExists() {
        CosServiceException exception = new CosServiceException("exists");
        exception.setErrorCode("FileAlreadyExists");
        exception.setStatusCode(409);
        return exception;
    }

    private static String bucket() {
        return "ai-friend-tombstones-1250000000";
    }
}
