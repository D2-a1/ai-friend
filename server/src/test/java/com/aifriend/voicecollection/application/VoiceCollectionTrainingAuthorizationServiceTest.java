package com.aifriend.voicecollection.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifriend.consent.application.ConsentGrantQueryPort;
import com.aifriend.consent.domain.ConsentDecision;
import com.aifriend.consent.domain.ConsentType;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;
import com.aifriend.shared.security.PublicIdCodec;
import com.aifriend.voicecollection.domain.VoiceCollectionReviewStatus;
import com.aifriend.voicecollection.domain.VoiceCollectionStatus;

class VoiceCollectionTrainingAuthorizationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-24T10:00:00Z");
    private static final String POLICY_VERSION = "voice-model-training-v1";

    @Test
    void shouldRequireIndependentTrainingConsentBeforeGranting() {
        UUID ownerUserId = UUID.randomUUID();
        UUID sampleId = UUID.randomUUID();
        InMemoryStore store = new InMemoryStore(ownerUserId, activeSample(sampleId));
        MutableConsentPort consentPort = new MutableConsentPort(false);
        VoiceCollectionTrainingAuthorizationService service = service(store, consentPort);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.update(
                        ownerUserId,
                        PublicIdCodec.voiceCollectionSampleId(sampleId),
                        "01JTRAININGCONSENTREQUIRED000",
                        command(ConsentDecision.GRANTED, 0L)));

        assertEquals(ErrorCode.CONSENT_REQUIRED, exception.errorCode());
        assertFalse(store.sample.trainingEligible());
        assertEquals(0, store.records.size());
    }

    @Test
    void shouldGrantReplayAndRevokeWithoutCreatingTrainingWork() {
        UUID ownerUserId = UUID.randomUUID();
        UUID sampleId = UUID.randomUUID();
        InMemoryStore store = new InMemoryStore(ownerUserId, activeSample(sampleId));
        MutableConsentPort consentPort = new MutableConsentPort(true);
        RecordingDatasetCleanupPort cleanupPort = new RecordingDatasetCleanupPort();
        VoiceCollectionTrainingAuthorizationService service =
                service(store, consentPort, cleanupPort);
        String publicSampleId = PublicIdCodec.voiceCollectionSampleId(sampleId);
        String grantKey = "01JTRAININGSAMPLEGRANTED00000";

        VoiceCollectionTrainingAuthorizationView granted = service.update(
                ownerUserId, publicSampleId, grantKey,
                command(ConsentDecision.GRANTED, 0L));
        VoiceCollectionTrainingAuthorizationView replay = service.update(
                ownerUserId, publicSampleId, grantKey,
                command(ConsentDecision.GRANTED, 0L));

        assertTrue(granted.trainingEligible());
        assertEquals(1L, granted.version());
        assertEquals(granted, replay);
        assertEquals(1, store.records.size());

        consentPort.granted = false;
        VoiceCollectionTrainingAuthorizationView revoked = service.update(
                ownerUserId,
                publicSampleId,
                "01JTRAININGSAMPLEREVOKED0000",
                command(ConsentDecision.REVOKED, 1L));

        assertFalse(revoked.trainingEligible());
        assertEquals(2L, revoked.version());
        assertEquals(2, store.records.size());
        assertEquals(List.of(sampleId), cleanupPort.deletedSamples);
    }

    @Test
    void shouldAllowRevocationAfterTrainingPolicyChanges() {
        UUID ownerUserId = UUID.randomUUID();
        UUID sampleId = UUID.randomUUID();
        VoiceCollectionTrainingSample eligibleSample = new VoiceCollectionTrainingSample(
                sampleId, VoiceCollectionStatus.ACTIVE, true,
                VoiceCollectionReviewStatus.CONFIRMED,
                NOW.plus(Duration.ofDays(1)), 7L);
        InMemoryStore store = new InMemoryStore(ownerUserId, eligibleSample);
        VoiceCollectionTrainingAuthorizationService service =
                service(store, new MutableConsentPort(false));

        VoiceCollectionTrainingAuthorizationView revoked = service.update(
                ownerUserId,
                PublicIdCodec.voiceCollectionSampleId(sampleId),
                "01JTRAININGOLDPOLICYREVOKE00",
                new VoiceCollectionTrainingAuthorizationCommand(
                        ConsentDecision.REVOKED, true,
                        "voice-model-training-v0", 7L));

        assertFalse(revoked.trainingEligible());
        assertEquals(8L, revoked.version());
        assertEquals(1, store.records.size());
    }

    @Test
    void shouldRejectIdempotencyBodyChangeAndOwnerMismatch() {
        UUID ownerUserId = UUID.randomUUID();
        UUID sampleId = UUID.randomUUID();
        InMemoryStore store = new InMemoryStore(ownerUserId, activeSample(sampleId));
        VoiceCollectionTrainingAuthorizationService service =
                service(store, new MutableConsentPort(true));
        String publicSampleId = PublicIdCodec.voiceCollectionSampleId(sampleId);
        String key = "01JTRAININGIDEMPOTENT000000";
        service.update(
                ownerUserId, publicSampleId, key,
                command(ConsentDecision.GRANTED, 0L));

        BusinessException conflict = assertThrows(
                BusinessException.class,
                () -> service.update(
                        ownerUserId, publicSampleId, key,
                        command(ConsentDecision.REVOKED, 1L)));
        BusinessException missing = assertThrows(
                BusinessException.class,
                () -> service.update(
                        UUID.randomUUID(), publicSampleId,
                        "01JTRAININGWRONGOWNER000000",
                        command(ConsentDecision.GRANTED, 1L)));

        assertEquals(ErrorCode.SESSION_CONFLICT, conflict.errorCode());
        assertEquals(ErrorCode.NOT_FOUND, missing.errorCode());
    }

    @Test
    void shouldRejectExpiredVersionAndUnconfirmedRequests() {
        UUID ownerUserId = UUID.randomUUID();
        UUID sampleId = UUID.randomUUID();
        VoiceCollectionTrainingSample expired = new VoiceCollectionTrainingSample(
                sampleId, VoiceCollectionStatus.ACTIVE, false,
                VoiceCollectionReviewStatus.CONFIRMED,
                NOW.minusMillis(1), 0L);
        InMemoryStore store = new InMemoryStore(ownerUserId, expired);
        VoiceCollectionTrainingAuthorizationService service =
                service(store, new MutableConsentPort(true));
        String publicSampleId = PublicIdCodec.voiceCollectionSampleId(sampleId);

        BusinessException expiredException = assertThrows(
                BusinessException.class,
                () -> service.update(
                        ownerUserId, publicSampleId,
                        "01JTRAININGEXPIREDSAMPLE000",
                        command(ConsentDecision.GRANTED, 0L)));
        BusinessException unconfirmedException = assertThrows(
                BusinessException.class,
                () -> service.update(
                        ownerUserId, publicSampleId,
                        "01JTRAININGUNCONFIRMED0000",
                        new VoiceCollectionTrainingAuthorizationCommand(
                                ConsentDecision.GRANTED, false, POLICY_VERSION, 0L)));

        assertEquals(ErrorCode.SESSION_CONFLICT, expiredException.errorCode());
        assertEquals(ErrorCode.VALIDATION_FAILED, unconfirmedException.errorCode());
    }

    @Test
    void shouldRejectTrainingGrantForSampleWithoutManualReview() {
        UUID ownerUserId = UUID.randomUUID();
        UUID sampleId = UUID.randomUUID();
        VoiceCollectionTrainingSample pendingReview = new VoiceCollectionTrainingSample(
                sampleId, VoiceCollectionStatus.ACTIVE, false,
                VoiceCollectionReviewStatus.PENDING,
                NOW.plus(Duration.ofDays(1)), 0L);
        InMemoryStore store = new InMemoryStore(ownerUserId, pendingReview);
        VoiceCollectionTrainingAuthorizationService service =
                service(store, new MutableConsentPort(true));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.update(
                        ownerUserId,
                        PublicIdCodec.voiceCollectionSampleId(sampleId),
                        "01JTRAININGREVIEWREQUIRED000",
                        command(ConsentDecision.GRANTED, 0L)));

        assertEquals(ErrorCode.SESSION_CONFLICT, exception.errorCode());
        assertFalse(store.sample.trainingEligible());
        assertEquals(0, store.records.size());
    }

    private VoiceCollectionTrainingAuthorizationService service(
            InMemoryStore store,
            MutableConsentPort consentPort) {
        return service(store, consentPort, new NoOpDatasetCleanupPort());
    }

    private VoiceCollectionTrainingAuthorizationService service(
            InMemoryStore store,
            MutableConsentPort consentPort,
            VoiceTrainingDatasetCleanupPort cleanupPort) {
        return new VoiceCollectionTrainingAuthorizationService(
                store,
                cleanupPort,
                consentPort,
                new VoiceCollectionProperties(
                        "test-voice-collection-v1", POLICY_VERSION,
                        "voice-sample-review-v1",
                        Duration.ofDays(30)),
                new DigestService(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private VoiceCollectionTrainingAuthorizationCommand command(
            ConsentDecision decision,
            long expectedVersion) {
        return new VoiceCollectionTrainingAuthorizationCommand(
                decision, true, POLICY_VERSION, expectedVersion);
    }

    private static VoiceCollectionTrainingSample activeSample(UUID sampleId) {
        return new VoiceCollectionTrainingSample(
                sampleId, VoiceCollectionStatus.ACTIVE, false,
                VoiceCollectionReviewStatus.CONFIRMED,
                NOW.plus(Duration.ofDays(1)), 0L);
    }

    private static final class MutableConsentPort implements ConsentGrantQueryPort {

        private boolean granted;

        private MutableConsentPort(boolean granted) {
            this.granted = granted;
        }

        @Override
        public boolean isGranted(UUID userId, ConsentType type) {
            return granted && type == ConsentType.VOICE_MODEL_TRAINING;
        }

        @Override
        public boolean isGrantedForPolicy(
                UUID userId,
                ConsentType type,
                String policyVersion) {
            return granted
                    && type == ConsentType.VOICE_MODEL_TRAINING
                    && POLICY_VERSION.equals(policyVersion);
        }
    }

    private static final class NoOpDatasetCleanupPort
            implements VoiceTrainingDatasetCleanupPort {

        @Override
        public void deleteAll(UUID ownerUserId) {
        }

        @Override
        public void deleteContaining(UUID ownerUserId, UUID sampleId) {
        }
    }

    private static final class RecordingDatasetCleanupPort
            implements VoiceTrainingDatasetCleanupPort {

        private final List<UUID> deletedSamples = new ArrayList<>();

        @Override
        public void deleteAll(UUID ownerUserId) {
        }

        @Override
        public void deleteContaining(UUID ownerUserId, UUID sampleId) {
            deletedSamples.add(sampleId);
        }
    }

    private static final class InMemoryStore
            implements VoiceCollectionTrainingAuthorizationStorePort {

        private final UUID ownerUserId;
        private final Map<String, VoiceCollectionTrainingAuthorizationRecord> byKey =
                new HashMap<>();
        private final List<VoiceCollectionTrainingAuthorizationRecord> records =
                new ArrayList<>();
        private VoiceCollectionTrainingSample sample;

        private InMemoryStore(
                UUID ownerUserId,
                VoiceCollectionTrainingSample sample) {
            this.ownerUserId = ownerUserId;
            this.sample = sample;
        }

        @Override
        public void lockOwner(UUID ownerUserId) {
        }

        @Override
        public Optional<VoiceCollectionTrainingAuthorizationRecord> findByIdempotencyKey(
                UUID ownerUserId,
                byte[] idempotencyKeyHash) {
            return Optional.ofNullable(byKey.get(Arrays.toString(idempotencyKeyHash)));
        }

        @Override
        public Optional<VoiceCollectionTrainingSample> findForUpdate(
                UUID ownerUserId,
                UUID sampleId) {
            if (!this.ownerUserId.equals(ownerUserId)
                    || !sample.sampleId().equals(sampleId)) {
                return Optional.empty();
            }
            return Optional.of(sample);
        }

        @Override
        public boolean updateEligibility(
                UUID ownerUserId,
                UUID sampleId,
                long expectedVersion,
                boolean trainingEligible,
                Instant updatedAt) {
            if (!this.ownerUserId.equals(ownerUserId)
                    || !sample.sampleId().equals(sampleId)
                    || sample.version() != expectedVersion
                    || sample.status() != VoiceCollectionStatus.ACTIVE) {
                return false;
            }
            sample = new VoiceCollectionTrainingSample(
                    sample.sampleId(), sample.status(), trainingEligible,
                    sample.reviewStatus(),
                    sample.retentionUntil(), sample.version() + 1L);
            return true;
        }

        @Override
        public void append(
                UUID ownerUserId,
                VoiceCollectionTrainingAuthorizationRecord record) {
            records.add(record);
            byKey.put(Arrays.toString(record.idempotencyKeyHash()), record);
        }
    }
}
