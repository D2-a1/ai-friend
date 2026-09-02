package com.aifriend.voicecollection.application;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.security.DigestService;
import com.aifriend.shared.security.SensitiveDataProtector;
import com.aifriend.voice.application.AudioObjectContentValidationService;
import com.aifriend.voice.application.AudioObjectRepositoryPort;
import com.aifriend.voice.application.AudioObjectStoragePort;
import com.aifriend.voice.application.StoredAudioObject;
import com.aifriend.voice.application.ValidatedAudioObject;
import com.aifriend.voice.domain.AudioObject;
import com.aifriend.voice.domain.AudioObjectStatus;
import com.aifriend.voice.domain.AudioPurpose;
import com.aifriend.voicecollection.domain.VoiceCollectionCategory;
import com.aifriend.voicecollection.domain.VoiceCollectionEnvironment;

class VoiceTrainingInputExportServiceTest {

    private static final UUID OWNER_ID =
            UUID.fromString("ca1c7c56-9d79-4db4-83bb-d44f78bb9076");
    private static final UUID DATASET_ID =
            UUID.fromString("2ae9ed26-dc79-4ed8-bcbc-31f3a91953cc");
    private static final UUID SAMPLE_ID =
            UUID.fromString("f37b1a9f-fbb4-4ad6-8121-a7963321b4f2");
    private static final UUID AUDIO_ID =
            UUID.fromString("c6939d25-1bef-48a8-a285-1a3a825796f9");
    private static final String DATASET_VERSION = "wugang-personal-20260828-v2";
    private static final Instant NOW = Instant.parse("2026-08-28T08:00:00Z");

    @Test
    void shouldExportVerifiedWavAndClearAllSensitiveBuffers() {
        Fixture fixture = fixture(true);
        byte[] audio = "RIFF0000WAVEfmt ".getBytes(UTF_8);
        byte[] cipher = new byte[32];
        Arrays.fill(cipher, (byte) 7);
        byte[] transcript = "打电话给女儿".getBytes(UTF_8);
        VoiceTrainingInputExportMember member = member(
                cipher, fixture.digestService.sha256(cipher),
                fixture.digestService.sha256(audio));
        when(fixture.datasetStorePort.findExportMembers(
                OWNER_ID, DATASET_ID, VoiceTrainingDatasetService.MAXIMUM_SAMPLE_COUNT + 1))
                .thenReturn(List.of(member));
        when(fixture.sensitiveDataProtector.decryptBytes(cipher)).thenReturn(transcript);
        AudioObject audioObject = audioObject(audio, fixture.digestService);
        when(fixture.audioObjectRepositoryPort.findByIdAndOwner(AUDIO_ID, OWNER_ID))
                .thenReturn(Optional.of(audioObject));
        when(fixture.audioObjectStoragePort.readExact(
                audioObject.objectKey(), audioObject.storageVersion(), audio.length))
                .thenReturn(new StoredAudioObject(audioObject.storageVersion(), audio));
        when(fixture.contentValidationService.validate(any(), any()))
                .thenReturn(new ValidatedAudioObject(
                        AUDIO_ID, OWNER_ID, AudioPurpose.TEST_VOICE_COLLECTION,
                        "audio/wav", audio, 1_000, "storage-v1", 3L));

        VoiceTrainingInputExportReceipt receipt = fixture.service.export(
                OWNER_ID, DATASET_VERSION);

        assertEquals(1, receipt.sampleCount());
        assertArrayEquals(audio, fixture.sink.copiedAudio);
        assertArrayEquals("打电话给女儿".getBytes(UTF_8), fixture.sink.copiedTranscript);
        assertEquals(1_000, fixture.sink.copiedItem.durationMs());
        assertEquals(VoiceTrainingInputSplit.TRAIN, fixture.sink.copiedItem.split());
        assertTrue(fixture.sink.committed);
        assertTrue(fixture.sink.closed);
        assertArrayEquals(new byte[32], cipher);
        assertArrayEquals(new byte[transcript.length], transcript);
    }

    @Test
    void shouldRejectDisabledExportBeforeDatasetOrStorageAccess() {
        Fixture fixture = fixture(false);

        assertThrows(
                BusinessException.class,
                () -> fixture.service.export(OWNER_ID, DATASET_VERSION));

        verify(fixture.datasetService, never()).resolveForTraining(any(), any());
        verify(fixture.audioObjectStoragePort, never()).readExact(any(), any(), any(Long.class));
    }

    @Test
    void shouldDiscardStagingWhenFinalDatasetRevalidationChanges() {
        Fixture fixture = fixture(true);
        byte[] audio = "RIFF0000WAVEfmt ".getBytes(UTF_8);
        byte[] cipher = new byte[32];
        Arrays.fill(cipher, (byte) 9);
        byte[] transcript = "发送消息".getBytes(UTF_8);
        VoiceTrainingInputExportMember member = member(
                cipher, fixture.digestService.sha256(cipher),
                fixture.digestService.sha256(audio));
        when(fixture.datasetStorePort.findExportMembers(any(), any(), any(Integer.class)))
                .thenReturn(List.of(member));
        when(fixture.sensitiveDataProtector.decryptBytes(cipher)).thenReturn(transcript);
        AudioObject audioObject = audioObject(audio, fixture.digestService);
        when(fixture.audioObjectRepositoryPort.findByIdAndOwner(AUDIO_ID, OWNER_ID))
                .thenReturn(Optional.of(audioObject));
        when(fixture.audioObjectStoragePort.readExact(any(), any(), any(Long.class)))
                .thenReturn(new StoredAudioObject("storage-v1", audio));
        when(fixture.contentValidationService.validate(any(), any()))
                .thenReturn(new ValidatedAudioObject(
                        AUDIO_ID, OWNER_ID, AudioPurpose.TEST_VOICE_COLLECTION,
                        "audio/wav", audio, 1_000, "storage-v1", 3L));
        VoiceTrainingDatasetSelection changed = new VoiceTrainingDatasetSelection(
                DATASET_ID,
                DATASET_VERSION,
                List.of(SAMPLE_ID),
                fixture.digestService.sha256("changed"),
                NOW);
        when(fixture.datasetService.resolveForTraining(OWNER_ID, DATASET_VERSION))
                .thenReturn(fixture.selection, changed);

        assertThrows(
                BusinessException.class,
                () -> fixture.service.export(OWNER_ID, DATASET_VERSION));

        assertTrue(fixture.sink.closed);
        assertTrue(!fixture.sink.committed);
    }

    @Test
    void shouldRejectCipherDigestMismatchBeforeDecryptOrStorageRead() {
        Fixture fixture = fixture(true);
        byte[] cipher = new byte[32];
        Arrays.fill(cipher, (byte) 4);
        VoiceTrainingInputExportMember member = member(
                cipher, fixture.digestService.sha256("different"), new byte[32]);
        when(fixture.datasetStorePort.findExportMembers(any(), any(), any(Integer.class)))
                .thenReturn(List.of(member));

        assertThrows(
                BusinessException.class,
                () -> fixture.service.export(OWNER_ID, DATASET_VERSION));

        verify(fixture.sensitiveDataProtector, never()).decryptBytes(any());
        verify(fixture.audioObjectStoragePort, never()).readExact(any(), any(), any(Long.class));
        assertArrayEquals(new byte[32], cipher);
        assertTrue(fixture.sink.closed);
    }

    @Test
    void shouldClearCipherWhenMemberListDoesNotMatchBeforeSinkOpens() {
        Fixture fixture = fixture(true);
        byte[] cipher = new byte[32];
        Arrays.fill(cipher, (byte) 6);
        VoiceTrainingInputExportMember unexpected = new VoiceTrainingInputExportMember(
                UUID.randomUUID(),
                AUDIO_ID,
                0,
                3L,
                3L,
                new byte[32],
                fixture.digestService.sha256(cipher),
                cipher,
                VoiceCollectionCategory.FULL_TASK,
                "full_task_contact_message",
                VoiceCollectionEnvironment.QUIET,
                "zh-Hans-CN-x-wugang");
        when(fixture.datasetStorePort.findExportMembers(any(), any(), any(Integer.class)))
                .thenReturn(List.of(unexpected));

        assertThrows(
                BusinessException.class,
                () -> fixture.service.export(OWNER_ID, DATASET_VERSION));

        assertArrayEquals(new byte[32], cipher);
        verify(fixture.sinkPort, never()).open(any(), any(), any());
    }

    private Fixture fixture(boolean enabled) {
        VoiceTrainingDatasetService datasetService = mock(VoiceTrainingDatasetService.class);
        VoiceTrainingDatasetStorePort datasetStorePort = mock(VoiceTrainingDatasetStorePort.class);
        VoiceTrainingDatasetSplitPlanner splitPlanner =
                mock(VoiceTrainingDatasetSplitPlanner.class);
        AudioObjectRepositoryPort repositoryPort = mock(AudioObjectRepositoryPort.class);
        AudioObjectStoragePort storagePort = mock(AudioObjectStoragePort.class);
        AudioObjectContentValidationService validationService =
                mock(AudioObjectContentValidationService.class);
        SensitiveDataProtector protector = mock(SensitiveDataProtector.class);
        DigestService digestService = new DigestService();
        VoiceTrainingDatasetSelection selection = new VoiceTrainingDatasetSelection(
                DATASET_ID,
                DATASET_VERSION,
                List.of(SAMPLE_ID),
                digestService.sha256("manifest"),
                NOW);
        when(datasetService.resolveForTraining(OWNER_ID, DATASET_VERSION))
                .thenReturn(selection);
        when(splitPlanner.plan(any())).thenReturn(List.of(VoiceTrainingInputSplit.TRAIN));
        FakeSink sink = new FakeSink(selection.manifestSha256());
        VoiceTrainingInputSinkPort sinkPort = mock(VoiceTrainingInputSinkPort.class);
        when(sinkPort.open(any(), any(), any())).thenReturn(sink);
        VoiceTrainingInputExportService service = new VoiceTrainingInputExportService(
                datasetService,
                datasetStorePort,
                splitPlanner,
                repositoryPort,
                storagePort,
                validationService,
                protector,
                digestService,
                sinkPort,
                new VoiceTrainingInputExportProperties(
                        enabled, enabled ? "E:\\training-export" : "unused", 1_024L),
                Clock.fixed(NOW, ZoneOffset.UTC));
        return new Fixture(
                service, datasetService, datasetStorePort, repositoryPort,
                storagePort, validationService, protector, digestService,
                selection, sinkPort, sink);
    }

    private VoiceTrainingInputExportMember member(
            byte[] cipher,
            byte[] cipherSha256,
            byte[] audioSha256) {
        return new VoiceTrainingInputExportMember(
                SAMPLE_ID,
                AUDIO_ID,
                0,
                3L,
                3L,
                audioSha256,
                cipherSha256,
                cipher,
                VoiceCollectionCategory.FULL_TASK,
                "full_task_contact_message",
                VoiceCollectionEnvironment.QUIET,
                "zh-Hans-CN-x-wugang");
    }

    private AudioObject audioObject(byte[] audio, DigestService digestService) {
        return new AudioObject(
                AUDIO_ID,
                OWNER_ID,
                AudioPurpose.TEST_VOICE_COLLECTION,
                "audio/wav",
                audio.length,
                1_000,
                digestService.sha256(audio),
                "temporary/audio-object",
                new byte[32],
                new byte[32],
                new byte[32],
                AudioObjectStatus.CONSUMED,
                NOW.minusSeconds(3_600),
                NOW.plusSeconds(3_600),
                "storage-v1",
                NOW.minusSeconds(3_000),
                NOW.minusSeconds(2_000),
                null,
                3L,
                NOW.minusSeconds(4_000),
                NOW.minusSeconds(2_000));
    }

    private record Fixture(
            VoiceTrainingInputExportService service,
            VoiceTrainingDatasetService datasetService,
            VoiceTrainingDatasetStorePort datasetStorePort,
            AudioObjectRepositoryPort audioObjectRepositoryPort,
            AudioObjectStoragePort audioObjectStoragePort,
            AudioObjectContentValidationService contentValidationService,
            SensitiveDataProtector sensitiveDataProtector,
            DigestService digestService,
            VoiceTrainingDatasetSelection selection,
            VoiceTrainingInputSinkPort sinkPort,
            FakeSink sink) {
    }

    private static final class FakeSink implements VoiceTrainingInputSink {

        private final byte[] manifestSha256;

        private byte[] copiedAudio;
        private byte[] copiedTranscript;
        private VoiceTrainingInputItem copiedItem;
        private boolean committed;
        private boolean closed;

        private FakeSink(byte[] manifestSha256) {
            this.manifestSha256 = manifestSha256;
        }

        @Override
        public void write(
                VoiceTrainingInputItem item,
                byte[] audioContent,
                byte[] transcriptUtf8) {
            copiedItem = item;
            copiedAudio = audioContent.clone();
            copiedTranscript = transcriptUtf8.clone();
        }

        @Override
        public VoiceTrainingInputExportReceipt commit(int expectedSampleCount) {
            committed = true;
            return new VoiceTrainingInputExportReceipt(
                    Path.of("E:\\training-export\\result"),
                    expectedSampleCount,
                    copiedAudio.length,
                    manifestSha256);
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
