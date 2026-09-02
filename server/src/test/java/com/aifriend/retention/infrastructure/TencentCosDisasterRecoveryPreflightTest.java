package com.aifriend.retention.infrastructure;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.model.BucketVersioningConfiguration;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.ListObjectsRequest;
import com.qcloud.cos.model.ObjectListing;
import com.qcloud.cos.model.PutObjectRequest;

class TencentCosDisasterRecoveryPreflightTest {

    @Test
    void shouldVerifyThreeIdentitiesWithoutWritingOrDeletingObjects() {
        COSClient publisherClient = clientWithVersioningOff();
        COSClient exportClient = clientWithVersioningOff();
        COSClient restoreClient = clientWithVersioningOff();
        when(publisherClient.listObjects(any(ListObjectsRequest.class)))
                .thenReturn(new ObjectListing());
        when(exportClient.getObject(eq(options().bucket()), any(String.class)))
                .thenThrow(noSuchKey());
        when(restoreClient.getObject(eq(options().bucket()), any(String.class)))
                .thenThrow(noSuchKey());

        try (TencentCosDisasterRecoveryPreflight preflight =
                new TencentCosDisasterRecoveryPreflight(
                        options(), publisherClient, exportClient, restoreClient)) {
            assertThatCode(preflight::verify).doesNotThrowAnyException();
        }

        verify(publisherClient).listObjects(any(ListObjectsRequest.class));
        verify(exportClient).getObject(eq(options().bucket()), any(String.class));
        verify(restoreClient).getObject(eq(options().bucket()), any(String.class));
        verify(exportClient, never())
                .getObjectMetadata(eq(options().bucket()), any(String.class));
        verify(restoreClient, never())
                .getObjectMetadata(eq(options().bucket()), any(String.class));
        verify(publisherClient, never()).putObject(any(PutObjectRequest.class));
        verify(exportClient, never()).putObject(any(PutObjectRequest.class));
        verify(restoreClient, never()).putObject(any(PutObjectRequest.class));
        verify(publisherClient, never()).deleteObject(any(String.class), any(String.class));
        verify(exportClient, never()).deleteObject(any(String.class), any(String.class));
        verify(restoreClient, never()).deleteObject(any(String.class), any(String.class));
    }

    @Test
    void shouldFailClosedWhenAnyIdentityCannotQueryBucket() {
        COSClient publisherClient = clientWithVersioningOff();
        COSClient exportClient = mock(COSClient.class);
        COSClient restoreClient = clientWithVersioningOff();
        when(exportClient.getBucketVersioningConfiguration(options().bucket()))
                .thenThrow(new CosServiceException("AccessDenied"));

        try (TencentCosDisasterRecoveryPreflight preflight =
                new TencentCosDisasterRecoveryPreflight(
                        options(), publisherClient, exportClient, restoreClient)) {
            assertThatThrownBy(preflight::verify)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("预检失败")
                    .hasMessageNotContaining("secret");
        }
    }

    @Test
    void shouldRejectEnabledBucketVersioningBeforePermissionProbes() {
        COSClient publisherClient = mock(COSClient.class);
        COSClient exportClient = clientWithVersioningOff();
        COSClient restoreClient = clientWithVersioningOff();
        BucketVersioningConfiguration enabled = new BucketVersioningConfiguration();
        enabled.setStatus(BucketVersioningConfiguration.ENABLED);
        when(publisherClient.getBucketVersioningConfiguration(options().bucket()))
                .thenReturn(enabled);

        try (TencentCosDisasterRecoveryPreflight preflight =
                new TencentCosDisasterRecoveryPreflight(
                        options(), publisherClient, exportClient, restoreClient)) {
            assertThatThrownBy(preflight::verify)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("版本控制");
        }

        verify(publisherClient, never()).listObjects(any(ListObjectsRequest.class));
    }

    @Test
    void shouldFailClosedWhenReadProbeObjectUnexpectedlyExists() throws Exception {
        COSClient publisherClient = clientWithVersioningOff();
        COSClient exportClient = clientWithVersioningOff();
        COSClient restoreClient = clientWithVersioningOff();
        COSObject unexpectedObject = mock(COSObject.class);
        when(publisherClient.listObjects(any(ListObjectsRequest.class)))
                .thenReturn(new ObjectListing());
        when(exportClient.getObject(eq(options().bucket()), any(String.class)))
                .thenReturn(unexpectedObject);

        try (TencentCosDisasterRecoveryPreflight preflight =
                new TencentCosDisasterRecoveryPreflight(
                        options(), publisherClient, exportClient, restoreClient)) {
            assertThatThrownBy(preflight::verify)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("对象意外存在");
        }

        verify(unexpectedObject).close();
        verify(restoreClient, never())
                .getObject(eq(options().bucket()), any(String.class));
    }

    private static COSClient clientWithVersioningOff() {
        COSClient client = mock(COSClient.class);
        BucketVersioningConfiguration off = new BucketVersioningConfiguration();
        off.setStatus(BucketVersioningConfiguration.OFF);
        when(client.getBucketVersioningConfiguration(options().bucket())).thenReturn(off);
        return client;
    }

    private static CosServiceException noSuchKey() {
        CosServiceException exception = new CosServiceException("NoSuchKey");
        exception.setErrorCode("NoSuchKey");
        exception.setStatusCode(404);
        return exception;
    }

    private static TencentCosDisasterRecoveryPreflightOptions options() {
        return new TencentCosDisasterRecoveryPreflightOptions(
                "ap-shanghai",
                "ai-friend-tombstones-1250000000",
                "publisher-secret-id",
                "publisher-secret-key",
                "",
                "export-secret-id",
                "export-secret-key",
                "",
                "restore-secret-id",
                "restore-secret-key",
                "",
                Duration.ofSeconds(2),
                Duration.ofSeconds(5));
    }
}
