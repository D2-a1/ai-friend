package com.aifriend.voicecollection.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.aifriend.consent.application.ConsentGrantQueryPort;
import com.aifriend.consent.domain.ConsentType;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;
import com.aifriend.shared.security.SensitiveDataProtector;
import com.aifriend.voice.application.AudioObjectConsumer;
import com.aifriend.voice.application.AudioObjectConsumptionService;
import com.aifriend.voice.application.ValidatedAudioObject;
import com.aifriend.voice.domain.AudioPurpose;
import com.aifriend.voicecollection.domain.VoiceCollectionCategory;
import com.aifriend.voicecollection.domain.VoiceCollectionEnvironment;
import com.aifriend.voicecollection.domain.VoiceCollectionReviewStatus;

/** 人工复核文字加密保存边界测试。 */
class VoiceCollectionServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-28T05:00:00Z");

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void shouldEncryptReviewedTranscriptBeforePersistingSample() {
        UUID ownerUserId = UUID.randomUUID();
        UUID audioObjectId = UUID.randomUUID();
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AudioObjectConsumptionService consumptionService =
                mock(AudioObjectConsumptionService.class);
        ConsentGrantQueryPort consentPort = mock(ConsentGrantQueryPort.class);
        SensitiveDataProtector protector = mock(SensitiveDataProtector.class);
        byte[] transcriptCipher = new byte[]{11, 12, 13, 14};
        when(consentPort.isGrantedForPolicy(
                ownerUserId, ConsentType.TEST_VOICE_COLLECTION,
                "test-voice-collection-v1")).thenReturn(true);
        when(protector.encrypt("小友")).thenReturn(transcriptCipher);
        when(jdbcTemplate.query(
                startsWith("SELECT BIN_TO_UUID(id),category"),
                any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        when(jdbcTemplate.query(
                startsWith("SELECT 1 FROM app_user"),
                any(RowMapper.class), any(Object[].class))).thenReturn(List.of(1));
        when(jdbcTemplate.queryForObject(
                startsWith("SELECT retention_until FROM audio_object"),
                any(RowMapper.class), any(Object[].class)))
                .thenReturn(NOW.plus(Duration.ofDays(1)));
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        ValidatedAudioObject validatedAudio = new ValidatedAudioObject(
                audioObjectId, ownerUserId, AudioPurpose.TEST_VOICE_COLLECTION,
                "audio/wav", new byte[]{1, 2}, 1_200, "oss-v1", 0L);
        doAnswer(invocation -> {
            AudioObjectConsumer<VoiceCollectionSampleView> consumer =
                    invocation.getArgument(3);
            return consumer.consume(validatedAudio);
        }).when(consumptionService).consume(
                eq(ownerUserId),
                eq("au_0123456789abcdef0123456789abcdef"),
                eq(AudioPurpose.TEST_VOICE_COLLECTION),
                any());
        VoiceCollectionService service = new VoiceCollectionService(
                jdbcTemplate,
                consumptionService,
                consentPort,
                mock(VoiceTrainingDatasetCleanupPort.class),
                new VoiceCollectionProperties(
                        "test-voice-collection-v1", "voice-model-training-v1",
                        "voice-sample-review-v1", Duration.ofDays(30)),
                new DigestService(),
                protector,
                Clock.fixed(NOW, ZoneOffset.UTC));

        VoiceCollectionSampleView view = service.create(
                ownerUserId,
                "01JVOICECOLLECTIONREVIEW0000",
                new CreateVoiceCollectionSampleCommand(
                        "au_0123456789abcdef0123456789abcdef",
                        VoiceCollectionCategory.WAKE_WORD,
                        "wake_xiaoyou_01",
                        VoiceCollectionEnvironment.QUIET,
                        "zh-Hans-CN-x-wugang",
                        "test-voice-collection-v1",
                        "  小友  ",
                        true,
                        "voice-sample-review-v1"));

        verify(protector).encrypt("小友");
        assertEquals(VoiceCollectionReviewStatus.CONFIRMED, view.reviewStatus());
        assertEquals(NOW, view.reviewedAt());
        assertFalse(view.trainingEligible());
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(
                startsWith("INSERT INTO voice_collection_sample"), arguments.capture());
        assertTrue(Arrays.stream(arguments.getValue())
                .noneMatch(value -> "小友".equals(value)));
        assertTrue(Arrays.stream(arguments.getValue())
                .anyMatch(value -> value == transcriptCipher));
        assertTrue(Arrays.stream(arguments.getValue())
                .anyMatch(value -> value instanceof Timestamp));
    }

    @Test
    void shouldRejectInvisibleFormatCharactersInReviewedTranscript() {
        VoiceCollectionService service = new VoiceCollectionService(
                mock(JdbcTemplate.class),
                mock(AudioObjectConsumptionService.class),
                mock(ConsentGrantQueryPort.class),
                mock(VoiceTrainingDatasetCleanupPort.class),
                new VoiceCollectionProperties(
                        "test-voice-collection-v1", "voice-model-training-v1",
                        "voice-sample-review-v1", Duration.ofDays(30)),
                new DigestService(),
                mock(SensitiveDataProtector.class),
                Clock.fixed(NOW, ZoneOffset.UTC));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.create(
                        UUID.randomUUID(),
                        "01JVOICECOLLECTIONCONTROL000",
                        new CreateVoiceCollectionSampleCommand(
                                "au_0123456789abcdef0123456789abcdef",
                                VoiceCollectionCategory.WAKE_WORD,
                                "wake_xiaoyou_01",
                                VoiceCollectionEnvironment.QUIET,
                                "zh-Hans-CN-x-wugang",
                                "test-voice-collection-v1",
                                "小\u200B友",
                                true,
                                "voice-sample-review-v1")));

        assertEquals(ErrorCode.VALIDATION_FAILED, exception.errorCode());
    }
}
