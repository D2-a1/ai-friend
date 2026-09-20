package com.aifriend.personalization.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.aifriend.consent.application.ConsentGrantQueryPort;
import com.aifriend.consent.domain.ConsentType;
import com.aifriend.identity.application.AuditEventPort;
import com.aifriend.personalization.domain.AmbiguousCallPreference;
import com.aifriend.personalization.domain.DialogueStylePreference;
import com.aifriend.personalization.domain.PersonalMemoryPreferences;
import com.aifriend.personalization.domain.PersonalMemoryRecord;
import com.aifriend.personalization.domain.PersonalMemoryStatus;
import com.aifriend.personalization.domain.SpeechRatePreference;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;
import com.aifriend.shared.security.SecurityKeyMaterial;
import com.aifriend.shared.security.SensitiveDataProtector;

class PersonalMemoryServiceTest {

    private static final UUID OWNER = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-09-04T12:00:00Z");
    private static final String POLICY = "personal-memory-v1";
    private static final String UPDATE_KEY = "01JPERSONALMEMORYUPDATE000001";
    private static final String DELETE_KEY = "01JPERSONALMEMORYDELETE000001";
    private final DigestService digestService = new DigestService();
    private final PersonalMemoryCodec codec = new PersonalMemoryCodec(
            new SensitiveDataProtector(keyMaterial()), digestService);

    @Test
    void shouldFailClosedWhenFeatureIsDisabledOrConsentIsMissing() {
        Fixture disabled = fixture(false, false);

        assertCode(ErrorCode.ACTION_UNSUPPORTED, () -> disabled.service.update(
                OWNER, UPDATE_KEY, preferences(), 0));
        verify(disabled.repository, never()).findByOwnerForUpdate(any());

        Fixture noConsent = fixture(true, false);
        assertCode(ErrorCode.CONSENT_REQUIRED, () -> noConsent.service.update(
                OWNER, UPDATE_KEY, preferences(), 0));
        verify(noConsent.repository, never()).findByOwnerForUpdate(any());
    }

    @Test
    void shouldCreateEncryptedRecordAndBindRequestHashToIdempotencyKey() {
        Fixture fixture = fixture(true, true);
        when(fixture.repository.findByOwnerForUpdate(OWNER)).thenReturn(Optional.empty());
        when(fixture.repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PersonalMemorySnapshot result = fixture.service.update(
                OWNER, UPDATE_KEY, preferences(), 0);

        ArgumentCaptor<PersonalMemoryRecord> captor =
                ArgumentCaptor.forClass(PersonalMemoryRecord.class);
        verify(fixture.repository).save(captor.capture());
        PersonalMemoryRecord saved = captor.getValue();
        assertThat(saved.status()).isEqualTo(PersonalMemoryStatus.ACTIVE);
        assertThat(saved.version()).isEqualTo(1);
        assertThat(saved.preferencesCipher()).isNotNull();
        assertThat(saved.preferencesDigest())
                .isEqualTo(digestService.sha256(saved.preferencesCipher()));
        assertThat(saved.updateRequestHash()).isEqualTo(digestService.sha256(
                UPDATE_KEY + "|" + codec.canonical(preferences()) + "|0"));
        assertThat(result.preferences()).isEqualTo(preferences());
        assertThat(result.consentGranted()).isTrue();
        verify(fixture.audit).append(
                OWNER, "PERSONAL_MEMORY_UPDATED", "SUCCESS", "PERSONAL_MEMORY", NOW);
    }

    @Test
    void shouldRejectStaleVersionAndConflictingIdempotencyReplay() {
        Fixture fixture = fixture(true, true);
        PersonalMemoryRecord current = activeRecord(3, preferences(), null, null);
        when(fixture.repository.findByOwnerForUpdate(OWNER))
                .thenReturn(Optional.of(current));

        assertCode(ErrorCode.SESSION_CONFLICT, () -> fixture.service.update(
                OWNER, UPDATE_KEY, preferences(), 2));

        String originalKey = "01JPERSONALMEMORYORIGINAL0001";
        PersonalMemoryPreferences original = preferences();
        PersonalMemoryRecord replayRecord = activeRecord(
                3,
                original,
                digestService.sha256(originalKey),
                digestService.sha256(originalKey + "|" + codec.canonical(original) + "|2"));
        when(fixture.repository.findByOwnerForUpdate(OWNER))
                .thenReturn(Optional.of(replayRecord));
        PersonalMemoryPreferences different = new PersonalMemoryPreferences(
                SpeechRatePreference.FAST,
                DialogueStylePreference.STANDARD,
                AmbiguousCallPreference.ASK_EVERY_TIME);

        assertCode(ErrorCode.SESSION_CONFLICT, () -> fixture.service.update(
                OWNER, originalKey, different, 2));
        verify(fixture.repository, never()).save(any());
    }

    @Test
    void shouldAllowViewAndDeletionAfterFeatureAndConsentAreDisabled() {
        Fixture fixture = fixture(false, false);
        PersonalMemoryRecord current = activeRecord(1, preferences(), null, null);
        when(fixture.repository.findByOwner(OWNER)).thenReturn(Optional.of(current));
        when(fixture.repository.findByOwnerForUpdate(OWNER))
                .thenReturn(Optional.of(current));
        when(fixture.repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PersonalMemorySnapshot before = fixture.service.get(OWNER);
        PersonalMemorySnapshot deleted = fixture.service.delete(
                OWNER, DELETE_KEY, true, 1);

        assertThat(before.featureEnabled()).isFalse();
        assertThat(before.preferences()).isEqualTo(preferences());
        assertThat(deleted.preferences()).isNull();
        assertThat(deleted.version()).isEqualTo(2);
        ArgumentCaptor<PersonalMemoryRecord> captor =
                ArgumentCaptor.forClass(PersonalMemoryRecord.class);
        verify(fixture.repository).save(captor.capture());
        assertThat(captor.getValue().preferencesCipher()).isNull();
        assertThat(captor.getValue().preferencesDigest()).isNull();
        assertThat(captor.getValue().updateIdempotencyKeyHash()).isNull();
        assertThat(captor.getValue().deleteIdempotencyKeyHash()).isNotNull();
        verify(fixture.audit).append(
                OWNER, "PERSONAL_MEMORY_DELETED", "SUCCESS", "PERSONAL_MEMORY", NOW);
    }

    @Test
    void shouldReturnDeletionTombstoneForExactReplayWithoutSecondWrite() {
        Fixture fixture = fixture(false, false);
        byte[] keyHash = digestService.sha256(DELETE_KEY);
        byte[] requestHash = digestService.sha256(
                DELETE_KEY + "|personal-memory-delete-v1|1|true");
        PersonalMemoryRecord tombstone = new PersonalMemoryRecord(
                OWNER, null, null, POLICY, PersonalMemoryStatus.DELETED,
                null, null, keyHash, requestHash, 2,
                NOW.minusSeconds(60), NOW, NOW);
        when(fixture.repository.findByOwnerForUpdate(OWNER))
                .thenReturn(Optional.of(tombstone));

        PersonalMemorySnapshot result = fixture.service.delete(
                OWNER, DELETE_KEY, true, 1);

        assertThat(result.preferences()).isNull();
        assertThat(result.version()).isEqualTo(2);
        verify(fixture.repository, never()).save(any());
        verify(fixture.audit, never()).append(any(), any(), any(), any(), any());
    }

    @Test
    void shouldRejectMalformedIdempotencyKeyBeforeHashing() {
        Fixture fixture = fixture(true, true);

        assertCode(ErrorCode.VALIDATION_FAILED, () -> fixture.service.update(
                OWNER, "short", preferences(), 0));
        assertCode(ErrorCode.VALIDATION_FAILED, () -> fixture.service.delete(
                OWNER, null, true, 1));
        verify(fixture.repository, never()).findByOwnerForUpdate(any());
    }

    private Fixture fixture(boolean enabled, boolean consentGranted) {
        PersonalMemoryRepositoryPort repository = mock(PersonalMemoryRepositoryPort.class);
        ConsentGrantQueryPort consent = mock(ConsentGrantQueryPort.class);
        AuditEventPort audit = mock(AuditEventPort.class);
        when(consent.isGrantedForPolicy(OWNER, ConsentType.PERSONAL_MEMORY, POLICY))
                .thenReturn(consentGranted);
        PersonalMemoryService service = new PersonalMemoryService(
                repository,
                consent,
                new PersonalMemoryProperties(enabled, POLICY),
                codec,
                digestService,
                audit,
                Clock.fixed(NOW, ZoneOffset.UTC));
        return new Fixture(service, repository, audit);
    }

    private PersonalMemoryRecord activeRecord(
            long version,
            PersonalMemoryPreferences preferences,
            byte[] updateKeyHash,
            byte[] updateRequestHash) {
        PersonalMemoryCodec.EncodedPersonalMemory encoded = codec.encode(preferences);
        return new PersonalMemoryRecord(
                OWNER, encoded.cipher(), encoded.digest(), POLICY,
                PersonalMemoryStatus.ACTIVE, updateKeyHash, updateRequestHash,
                null, null, version, NOW.minusSeconds(120), NOW, null);
    }

    private PersonalMemoryPreferences preferences() {
        return new PersonalMemoryPreferences(
                SpeechRatePreference.SLOW,
                DialogueStylePreference.BRIEF,
                AmbiguousCallPreference.VIDEO);
    }

    private SecurityKeyMaterial keyMaterial() {
        byte[] keyBytes = new byte[32];
        return new SecurityKeyMaterial(
                new SecretKeySpec(keyBytes, "HmacSHA256"),
                new SecretKeySpec(keyBytes, "AES"),
                new SecretKeySpec(keyBytes, "HmacSHA256"));
    }

    private void assertCode(ErrorCode expected, ThrowingCall call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(expected));
    }

    private record Fixture(
            PersonalMemoryService service,
            PersonalMemoryRepositoryPort repository,
            AuditEventPort audit) {
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run();
    }
}
