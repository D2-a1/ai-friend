package com.aifriend.voicecollection.application;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.aifriend.consent.application.ConsentGrantQueryPort;
import com.aifriend.consent.domain.ConsentType;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.security.DigestService;
import com.aifriend.voicecollection.domain.VoiceCollectionCategory;
import com.aifriend.voicecollection.domain.VoiceCollectionEnvironment;

class VoiceTrainingDatasetServiceTest {

    private static final UUID OWNER_ID =
            UUID.fromString("ca1c7c56-9d79-4db4-83bb-d44f78bb9076");
    private static final Instant NOW = Instant.parse("2026-08-28T06:00:00Z");
    private static final String DATASET_VERSION = "wugang-personal-20260828-v1";

    @Test
    void shouldFreezeDeterministicAuthorizedDatasetWithoutCopyingTranscriptCipher() {
        VoiceTrainingDatasetStorePort storePort = mock(VoiceTrainingDatasetStorePort.class);
        ConsentGrantQueryPort consentPort = grantedConsents();
        VoiceTrainingDatasetCandidate second = candidate(
                "00000000-0000-0000-0000-000000000002", 3L, new byte[]{4, 5, 6});
        VoiceTrainingDatasetCandidate first = candidate(
                "00000000-0000-0000-0000-000000000001", 2L, new byte[]{1, 2, 3});
        when(storePort.findByVersion(OWNER_ID, DATASET_VERSION))
                .thenReturn(Optional.empty());
        when(storePort.findEligibleForUpdate(
                OWNER_ID,
                "voice-sample-review-v1",
                NOW,
                VoiceTrainingDatasetService.MAXIMUM_SAMPLE_COUNT + 1))
                .thenReturn(new java.util.ArrayList<>(List.of(second, first)));
        VoiceTrainingDatasetService service = service(storePort, consentPort);

        VoiceTrainingDatasetSelection selection = service.freeze(
                OWNER_ID, DATASET_VERSION);

        assertEquals(DATASET_VERSION, selection.datasetVersion());
        assertEquals(List.of(first.sampleId(), second.sampleId()), selection.sampleIds());
        assertEquals(32, selection.manifestSha256().length);
        ArgumentCaptor<VoiceTrainingDatasetRecord> datasetCaptor =
                ArgumentCaptor.forClass(VoiceTrainingDatasetRecord.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<VoiceTrainingDatasetMember>> membersCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(storePort).insert(datasetCaptor.capture(), membersCaptor.capture());
        assertEquals(2, datasetCaptor.getValue().sampleCount());
        assertEquals(2, membersCaptor.getValue().size());
        assertEquals(32, membersCaptor.getValue().get(0).reviewedTranscriptSha256().length);
        assertFalse(Arrays.equals(
                new byte[]{1, 2, 3},
                membersCaptor.getValue().get(0).reviewedTranscriptSha256()));
        assertArrayEquals(new byte[]{0, 0, 0}, first.reviewedTranscriptCipher());
        assertArrayEquals(new byte[]{0, 0, 0}, second.reviewedTranscriptCipher());
    }

    @Test
    void shouldResolveExistingDatasetOnlyWhenLiveManifestStillMatches() {
        VoiceTrainingDatasetStorePort storePort = mock(VoiceTrainingDatasetStorePort.class);
        ConsentGrantQueryPort consentPort = grantedConsents();
        VoiceTrainingDatasetCandidate initial = candidate(
                "00000000-0000-0000-0000-000000000003", 7L, new byte[]{7, 8, 9});
        when(storePort.findByVersion(OWNER_ID, DATASET_VERSION))
                .thenReturn(Optional.empty());
        when(storePort.findEligibleForUpdate(
                eq(OWNER_ID), eq("voice-sample-review-v1"), eq(NOW), any(Integer.class)))
                .thenReturn(new java.util.ArrayList<>(List.of(initial)));
        VoiceTrainingDatasetService service = service(storePort, consentPort);
        service.freeze(OWNER_ID, DATASET_VERSION);
        ArgumentCaptor<VoiceTrainingDatasetRecord> datasetCaptor =
                ArgumentCaptor.forClass(VoiceTrainingDatasetRecord.class);
        verify(storePort).insert(datasetCaptor.capture(), any());
        VoiceTrainingDatasetRecord stored = datasetCaptor.getValue();
        VoiceTrainingDatasetCandidate current = candidate(
                "00000000-0000-0000-0000-000000000003", 7L, new byte[]{7, 8, 9});
        when(storePort.findByVersion(OWNER_ID, DATASET_VERSION))
                .thenReturn(Optional.of(stored));
        when(storePort.findEligibleDatasetMembersForUpdate(
                OWNER_ID,
                stored.datasetId(),
                "voice-sample-review-v1",
                NOW,
                VoiceTrainingDatasetService.MAXIMUM_SAMPLE_COUNT + 1))
                .thenReturn(new java.util.ArrayList<>(List.of(current)));

        VoiceTrainingDatasetSelection resolved = service.resolveForTraining(
                OWNER_ID, DATASET_VERSION);

        assertEquals(List.of(current.sampleId()), resolved.sampleIds());
        assertArrayEquals(stored.manifestSha256(), resolved.manifestSha256());
        assertArrayEquals(new byte[]{0, 0, 0}, current.reviewedTranscriptCipher());
    }

    @Test
    void shouldRejectExistingDatasetAfterSampleVersionChanges() {
        VoiceTrainingDatasetStorePort storePort = mock(VoiceTrainingDatasetStorePort.class);
        ConsentGrantQueryPort consentPort = grantedConsents();
        VoiceTrainingDatasetCandidate initial = candidate(
                "00000000-0000-0000-0000-000000000004", 4L, new byte[]{10, 11});
        when(storePort.findByVersion(OWNER_ID, DATASET_VERSION))
                .thenReturn(Optional.empty());
        when(storePort.findEligibleForUpdate(
                eq(OWNER_ID), eq("voice-sample-review-v1"), eq(NOW), any(Integer.class)))
                .thenReturn(new java.util.ArrayList<>(List.of(initial)));
        VoiceTrainingDatasetService service = service(storePort, consentPort);
        service.freeze(OWNER_ID, DATASET_VERSION);
        ArgumentCaptor<VoiceTrainingDatasetRecord> datasetCaptor =
                ArgumentCaptor.forClass(VoiceTrainingDatasetRecord.class);
        verify(storePort).insert(datasetCaptor.capture(), any());
        VoiceTrainingDatasetRecord stored = datasetCaptor.getValue();
        VoiceTrainingDatasetCandidate changed = candidate(
                "00000000-0000-0000-0000-000000000004", 5L, new byte[]{10, 11});
        when(storePort.findByVersion(OWNER_ID, DATASET_VERSION))
                .thenReturn(Optional.of(stored));
        when(storePort.findEligibleDatasetMembersForUpdate(
                eq(OWNER_ID), eq(stored.datasetId()), eq("voice-sample-review-v1"),
                eq(NOW), any(Integer.class)))
                .thenReturn(new java.util.ArrayList<>(List.of(changed)));

        assertThrows(
                BusinessException.class,
                () -> service.resolveForTraining(OWNER_ID, DATASET_VERSION));

        assertArrayEquals(new byte[]{0, 0}, changed.reviewedTranscriptCipher());
    }

    @Test
    void shouldRejectFreezeWithoutBothCurrentConsents() {
        VoiceTrainingDatasetStorePort storePort = mock(VoiceTrainingDatasetStorePort.class);
        ConsentGrantQueryPort consentPort = mock(ConsentGrantQueryPort.class);
        when(consentPort.isGrantedForPolicy(
                OWNER_ID, ConsentType.TEST_VOICE_COLLECTION, "test-voice-collection-v1"))
                .thenReturn(true);
        when(consentPort.isGrantedForPolicy(
                OWNER_ID, ConsentType.VOICE_MODEL_TRAINING, "voice-model-training-v1"))
                .thenReturn(false);
        VoiceTrainingDatasetService service = service(storePort, consentPort);

        assertThrows(
                BusinessException.class,
                () -> service.freeze(OWNER_ID, DATASET_VERSION));

        verify(storePort, never()).findEligibleForUpdate(any(), any(), any(), any(Integer.class));
        verify(storePort, never()).insert(any(), any());
    }

    @Test
    void shouldRejectInvalidDatasetVersionBeforeDatabaseAccess() {
        VoiceTrainingDatasetStorePort storePort = mock(VoiceTrainingDatasetStorePort.class);
        VoiceTrainingDatasetService service = service(storePort, grantedConsents());

        assertThrows(
                BusinessException.class,
                () -> service.freeze(OWNER_ID, "包含中文"));

        verify(storePort, never()).lockOwner(any());
    }

    private VoiceTrainingDatasetService service(
            VoiceTrainingDatasetStorePort storePort,
            ConsentGrantQueryPort consentPort) {
        return new VoiceTrainingDatasetService(
                storePort,
                consentPort,
                new VoiceCollectionProperties(
                        "test-voice-collection-v1",
                        "voice-model-training-v1",
                        "voice-sample-review-v1",
                        Duration.ofDays(30)),
                new DigestService(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ConsentGrantQueryPort grantedConsents() {
        ConsentGrantQueryPort consentPort = mock(ConsentGrantQueryPort.class);
        when(consentPort.isGrantedForPolicy(
                OWNER_ID, ConsentType.TEST_VOICE_COLLECTION, "test-voice-collection-v1"))
                .thenReturn(true);
        when(consentPort.isGrantedForPolicy(
                OWNER_ID, ConsentType.VOICE_MODEL_TRAINING, "voice-model-training-v1"))
                .thenReturn(true);
        return consentPort;
    }

    private VoiceTrainingDatasetCandidate candidate(
            String sampleId,
            long version,
            byte[] reviewedTranscriptCipher) {
        UUID sampleUuid = UUID.fromString(sampleId);
        return new VoiceTrainingDatasetCandidate(
                sampleUuid,
                UUID.nameUUIDFromBytes(sampleId.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                version,
                VoiceCollectionCategory.FULL_TASK,
                "full_task_contact_message",
                VoiceCollectionEnvironment.QUIET,
                "zh-Hans-CN-x-wugang",
                new byte[32],
                reviewedTranscriptCipher,
                NOW.minusSeconds(60),
                NOW.plus(Duration.ofDays(10)));
    }
}
