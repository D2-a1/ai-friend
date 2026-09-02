package com.aifriend.voice.infrastructure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Constructor;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.BucketVersioningConfiguration;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.ObjectMetadata;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.error.UpstreamFailureException;
import com.aifriend.shared.security.DigestService;
import com.aifriend.voice.application.AliyunOssStorageProperties;
import com.aifriend.voice.application.AudioUploadTarget;
import com.aifriend.voice.application.StoredAudioObject;
import com.aifriend.voice.domain.AudioObject;
import com.aifriend.voice.domain.AudioObjectStatus;
import com.aifriend.voice.domain.AudioPurpose;

/**
 * 阿里云 OSS 生产音频存储适配器测试。
 *
 * @author Codex
 * @since 1.0.0
 */
class AliyunOssAudioStorageAdapterTest {

    private static final String OBJECT_KEY =
            "temporary/00112233445566778899aabbccddeeff";
    private static final byte[] AUDIO_CONTENT =
            new byte[] {1, 2, 3, 4};

    @Test
    void shouldExposeProductionConstructorAsOnlySpringInjectionCandidate() {
        AutowiredAnnotationBeanPostProcessor processor =
                new AutowiredAnnotationBeanPostProcessor();

        Constructor<?>[] candidates = processor.determineCandidateConstructors(
                AliyunOssAudioStorageAdapter.class,
                "aliyunOssAudioStorageAdapter");

        assertEquals(1, candidates.length);
        assertArrayEquals(
                new Class<?>[] {
                    AliyunOssStorageProperties.class,
                    DigestService.class
                },
                candidates[0].getParameterTypes());
    }

    @Test
    void shouldCreateRestrictedPutTargetUsingOriginalExpiry() throws Exception {
        OSS serviceClient = readyServiceClient();
        OSS signingClient = mock(OSS.class);
        URL signedUrl = new URL(
                "https://ai-friend-audio.oss-cn-shanghai.aliyuncs.com/"
                        + OBJECT_KEY + "?signature=redacted");
        when(signingClient.generatePresignedUrl(any())).thenReturn(signedUrl);
        AliyunOssAudioStorageAdapter adapter =
                adapter(serviceClient, signingClient);
        AudioObject audioObject = audioObject();

        AudioUploadTarget target =
                adapter.createTarget(audioObject, "upload-secret");

        ArgumentCaptor<GeneratePresignedUrlRequest> requestCaptor =
                ArgumentCaptor.forClass(GeneratePresignedUrlRequest.class);
        verify(signingClient).generatePresignedUrl(requestCaptor.capture());
        GeneratePresignedUrlRequest request = requestCaptor.getValue();
        assertEquals(HttpMethod.PUT, request.getMethod());
        assertEquals(Date.from(audioObject.uploadExpiresAt()),
                request.getExpiration());
        assertEquals(audioObject.mediaType(), request.getContentType());
        assertEquals("true",
                request.getHeaders().get("x-oss-forbid-overwrite"));
        assertEquals("private",
                request.getHeaders().get("x-oss-object-acl"));
        assertEquals("AES256",
                request.getHeaders().get("x-oss-server-side-encryption"));
        assertFalse(request.getHeaders().containsKey(
                "x-oss-server-side-encryption-key-id"));
        assertEquals("PUT", target.method());
        assertEquals(audioObject.mediaType(),
                target.requiredHeaders().get("Content-Type"));
        assertEquals("no-store",
                target.requiredHeaders().get("Cache-Control"));
        assertFalse(target.requiredHeaders().containsValue("upload-secret"));
    }

    @Test
    void shouldGenerateVersionFourSignedUrlLocallyWithoutNetwork() {
        OSS serviceClient = readyServiceClient();
        OSS signingClient = AliyunOssAudioStorageAdapter.createClient(
                properties(), properties().uploadEndpoint());
        AliyunOssAudioStorageAdapter adapter =
                adapter(serviceClient, signingClient);

        AudioUploadTarget target = adapter.createTarget(
                audioObject(), "upload-secret");

        assertEquals("https", target.uploadUrl().getScheme());
        assertEquals(
                "ai-friend-audio.oss-cn-shanghai.aliyuncs.com",
                target.uploadUrl().getHost());
        assertFalse(target.uploadUrl().toString().contains("upload-secret"));
        adapter.close();
    }

    @Test
    void shouldReadBoundedContentAndUseSha256StrongVersion() {
        OSS serviceClient = readyServiceClient();
        when(serviceClient.getObject("ai-friend-audio", OBJECT_KEY))
                .thenAnswer(invocation -> storedObject(AUDIO_CONTENT));
        AliyunOssAudioStorageAdapter adapter =
                adapter(serviceClient, mock(OSS.class));

        StoredAudioObject current =
                adapter.readCurrent(OBJECT_KEY, AUDIO_CONTENT.length);
        StoredAudioObject exact = adapter.readExact(
                OBJECT_KEY, current.storageVersion(), AUDIO_CONTENT.length);

        assertArrayEquals(AUDIO_CONTENT, current.audioContent());
        assertArrayEquals(AUDIO_CONTENT, exact.audioContent());
        assertEquals(
                "sha256:9f64a747e1b97f131fabb6b447296c9b"
                        + "6f0201e79fb3c5356e6c77e89b6a806a",
                current.storageVersion());
    }

    @Test
    void shouldRejectChangedStrongVersionAndMissingObject() {
        OSS serviceClient = readyServiceClient();
        when(serviceClient.getObject("ai-friend-audio", OBJECT_KEY))
                .thenAnswer(invocation -> storedObject(AUDIO_CONTENT))
                .thenThrow(missingObject());
        AliyunOssAudioStorageAdapter adapter =
                adapter(serviceClient, mock(OSS.class));

        BusinessException changed = assertThrows(
                BusinessException.class,
                () -> adapter.readExact(
                        OBJECT_KEY, "sha256:changed", AUDIO_CONTENT.length));
        BusinessException missing = assertThrows(
                BusinessException.class,
                () -> adapter.readCurrent(
                        OBJECT_KEY, AUDIO_CONTENT.length));

        assertEquals(ErrorCode.AUDIO_INVALID, changed.errorCode());
        assertEquals(ErrorCode.AUDIO_INVALID, missing.errorCode());
    }

    @Test
    void shouldDeleteOnlyValidatedTemporaryObjectKey() {
        OSS serviceClient = readyServiceClient();
        AliyunOssAudioStorageAdapter adapter =
                adapter(serviceClient, mock(OSS.class));

        adapter.delete(OBJECT_KEY);

        verify(serviceClient).deleteObject("ai-friend-audio", OBJECT_KEY);
        verify(serviceClient).doesObjectExist("ai-friend-audio", OBJECT_KEY);
        BusinessException invalid = assertThrows(
                BusinessException.class,
                () -> adapter.delete("../outside"));
        assertEquals(ErrorCode.INTERNAL_ERROR, invalid.errorCode());
    }

    @Test
    void shouldTreatAlreadyMissingObjectAsIdempotentDeletion() {
        OSS serviceClient = readyServiceClient();
        doThrow(missingObject()).when(serviceClient)
                .deleteObject("ai-friend-audio", OBJECT_KEY);
        AliyunOssAudioStorageAdapter adapter =
                adapter(serviceClient, mock(OSS.class));

        adapter.delete(OBJECT_KEY);

        verify(serviceClient, never()).doesObjectExist(
                "ai-friend-audio", OBJECT_KEY);
    }

    @Test
    void shouldFailDeletionWhenObjectStillExistsAfterDeleteRequest() {
        OSS serviceClient = readyServiceClient();
        when(serviceClient.doesObjectExist("ai-friend-audio", OBJECT_KEY))
                .thenReturn(true);
        AliyunOssAudioStorageAdapter adapter =
                adapter(serviceClient, mock(OSS.class));

        assertThrows(UpstreamFailureException.class,
                () -> adapter.delete(OBJECT_KEY));
    }

    @Test
    void shouldNotTreatMissingBucketAsIdempotentObjectDeletion() {
        OSS serviceClient = readyServiceClient();
        doThrow(new OSSException(
                "missing bucket", "NoSuchBucket", null, null,
                null, null, null))
                .when(serviceClient)
                .deleteObject("ai-friend-audio", OBJECT_KEY);
        AliyunOssAudioStorageAdapter adapter =
                adapter(serviceClient, mock(OSS.class));

        assertThrows(UpstreamFailureException.class,
                () -> adapter.delete(OBJECT_KEY));
    }

    @Test
    void shouldAcceptBucketThatNeverEnabledVersioning() {
        OSS serviceClient = mock(OSS.class);
        OSS signingClient = mock(OSS.class);
        when(serviceClient.getBucketVersioning("ai-friend-audio"))
                .thenReturn(new BucketVersioningConfiguration());

        adapter(serviceClient, signingClient);

        verify(serviceClient).getBucketVersioning("ai-friend-audio");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        BucketVersioningConfiguration.ENABLED,
        BucketVersioningConfiguration.SUSPENDED,
        "Unexpected"
    })
    void shouldFailClosedWhenBucketVersioningIsNotOff(String status) {
        OSS serviceClient = mock(OSS.class);
        OSS signingClient = mock(OSS.class);
        when(serviceClient.getBucketVersioning("ai-friend-audio"))
                .thenReturn(new BucketVersioningConfiguration(status));

        assertThrows(IllegalStateException.class,
                () -> adapter(serviceClient, signingClient));

        verify(serviceClient).shutdown();
        verify(signingClient).shutdown();
    }

    @Test
    void shouldFailClosedWhenBucketVersioningCannotBeVerified() {
        OSS serviceClient = mock(OSS.class);
        OSS signingClient = mock(OSS.class);
        when(serviceClient.getBucketVersioning("ai-friend-audio"))
                .thenThrow(new OSSException("unavailable"));

        assertThrows(IllegalStateException.class,
                () -> adapter(serviceClient, signingClient));

        verify(serviceClient).shutdown();
        verify(signingClient).shutdown();
    }

    private AliyunOssAudioStorageAdapter adapter(
            OSS serviceClient,
            OSS signingClient) {
        return new AliyunOssAudioStorageAdapter(
                properties(),
                new DigestService(),
                serviceClient,
                signingClient);
    }

    private OSS readyServiceClient() {
        OSS serviceClient = mock(OSS.class);
        when(serviceClient.getBucketVersioning("ai-friend-audio"))
                .thenReturn(new BucketVersioningConfiguration());
        return serviceClient;
    }

    private AliyunOssStorageProperties properties() {
        return new AliyunOssStorageProperties(
                true,
                "cn-shanghai",
                URI.create("https://oss-cn-shanghai.aliyuncs.com"),
                URI.create("https://oss-cn-shanghai.aliyuncs.com"),
                "ai-friend-audio",
                "access-key",
                "access-secret",
                "",
                Duration.ofSeconds(2),
                Duration.ofSeconds(5));
    }

    private AudioObject audioObject() {
        Instant now = Instant.parse("2026-08-25T10:00:00Z");
        return new AudioObject(
                UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"),
                UUID.fromString("11112233-4455-6677-8899-aabbccddeeff"),
                AudioPurpose.TASK,
                "audio/wav",
                AUDIO_CONTENT.length,
                1_000,
                new byte[32],
                OBJECT_KEY,
                new byte[32],
                new byte[32],
                new byte[32],
                AudioObjectStatus.ISSUED,
                now.plus(Duration.ofMinutes(10)),
                now.plus(Duration.ofHours(24)),
                null,
                null,
                null,
                null,
                0L,
                now,
                now);
    }

    private OSSObject storedObject(byte[] content) {
        OSSObject object = new OSSObject();
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(content.length);
        object.setObjectMetadata(metadata);
        object.setObjectContent(new ByteArrayInputStream(content));
        return object;
    }

    private OSSException missingObject() {
        return new OSSException(
                "missing", "NoSuchKey", null, null,
                null, null, null);
    }
}
