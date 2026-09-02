package com.aifriend.contact.infrastructure;

import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifriend.contact.application.AcousticEnrollmentCandidate;
import com.aifriend.contact.application.AcousticEnrollmentSample;
import com.aifriend.contact.application.AcousticUniqueness;
import com.aifriend.contact.application.ExistingAcousticTemplate;
import com.aifriend.dialect.application.DialectAcousticCalibration;
import com.aifriend.dialect.application.DialectPackageManifest;
import com.aifriend.dialect.application.DialectPackageRegistry;
import com.aifriend.dialect.application.DialectPackageState;
import com.aifriend.dialect.application.VerifiedDialectPackage;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;

class MfccDtwAcousticTemplateAdapterTest {

    private static final int SAMPLE_RATE_HZ = 16_000;
    private static final int DURATION_MS = 1_200;

    @Test
    void shouldEnrollConsistentWavPairWithVersionedTemplate() {
        MfccDtwAcousticTemplateAdapter adapter = adapter(calibration(
                0.10D, 0.01D, 0.50D));
        AcousticEnrollmentSample phrase = phrase(0.0D, 300, 450, 620, 430);

        AcousticEnrollmentCandidate candidate = adapter.enroll(phrase, phrase);

        assertEquals("zh-Hans-CN-x-wugang", candidate.dialectCode());
        assertEquals("wugang-package-test-v1", candidate.dialectPackageVersion());
        assertEquals("mfcc-dtw-test-v1", candidate.modelVersion());
        assertEquals("threshold-test-v1", candidate.thresholdVersion());
    }

    @Test
    void shouldRejectTwoDifferentPronunciationContents() {
        MfccDtwAcousticTemplateAdapter adapter = adapter(calibration(
                0.01D, 0.01D, 0.50D));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> adapter.enroll(
                        phrase(0.0D, 300, 450, 620, 430),
                        phrase(0.0D, 760, 520, 340, 720)));

        assertEquals(ErrorCode.ENROLLMENT_INCONSISTENT, exception.errorCode());
    }

    @Test
    void shouldClassifyIdenticalTemplateAsConflict() {
        MfccDtwAcousticTemplateAdapter adapter = adapter(calibration(
                0.10D, 0.01D, 0.50D));
        AcousticEnrollmentCandidate candidate = adapter.enroll(
                phrase(0.0D, 300, 450, 620, 430),
                phrase(0.0D, 300, 450, 620, 430));

        AcousticUniqueness uniqueness = adapter.classify(candidate,
                List.of(existing(candidate)));

        assertEquals(AcousticUniqueness.CONFLICT, uniqueness);
    }

    @Test
    void shouldClassifyDifferentTemplateAsDistinct() {
        MfccDtwAcousticTemplateAdapter adapter = adapter(calibration(
                0.20D, 0.0001D, 0.0002D));
        AcousticEnrollmentCandidate candidate = adapter.enroll(
                phrase(0.0D, 300, 450, 620, 430),
                phrase(1.0D, 300, 450, 620, 430));
        AcousticEnrollmentCandidate different = adapter.enroll(
                phrase(0.0D, 760, 520, 340, 720),
                phrase(1.0D, 760, 520, 340, 720));

        AcousticUniqueness uniqueness = adapter.classify(candidate,
                List.of(existing(different)));

        assertEquals(AcousticUniqueness.DISTINCT, uniqueness);
    }

    @Test
    void shouldAcceptDifferentSafetyPhraseRelativeToEachDoubleTakeBaseline() {
        MfccDtwAcousticTemplateAdapter adapter = adapter(calibration(
                0.20D, 0.0001D, 1.50D));
        AcousticEnrollmentCandidate sendMessage = adapter.enroll(
                phrase(0.0D, 300, 450, 620, 430),
                phrase(1.0D, 300, 450, 620, 430));
        AcousticEnrollmentCandidate callNow = adapter.enroll(
                phrase(0.0D, 760, 520, 340, 720),
                phrase(1.0D, 760, 520, 340, 720));

        assertEquals(true, adapter.isSafetyCommandDistinct(
                callNow, List.of(existing(sendMessage))));
    }

    @Test
    void shouldRejectSameSafetyPhraseEvenWhenRecordedAsAnotherCommand() {
        MfccDtwAcousticTemplateAdapter adapter = adapter(calibration(
                0.20D, 0.0001D, 1.50D));
        AcousticEnrollmentCandidate first = adapter.enroll(
                phrase(0.0D, 300, 450, 620, 430),
                phrase(1.0D, 300, 450, 620, 430));
        AcousticEnrollmentCandidate repeated = adapter.enroll(
                phrase(0.5D, 300, 450, 620, 430),
                phrase(1.5D, 300, 450, 620, 430));

        assertEquals(false, adapter.isSafetyCommandDistinct(
                repeated, List.of(existing(first))));
    }

    @Test
    void shouldClassifyDistanceInsideCalibratedBandAsBorderline() {
        DialectAcousticCalibration permissive = calibration(
                10.0D, 0.01D, 10.0D);
        MfccDtwAcousticTemplateAdapter enrollmentAdapter = adapter(permissive);
        AcousticEnrollmentCandidate candidate = enrollmentAdapter.enroll(
                phrase(0.0D, 300, 450, 620, 430),
                phrase(1.0D, 300, 450, 620, 430));
        AcousticEnrollmentCandidate different = enrollmentAdapter.enroll(
                phrase(0.0D, 760, 520, 340, 720),
                phrase(1.0D, 760, 520, 340, 720));
        double distance = minimumDistance(candidate, different,
                permissive.dtwWindowRatio());
        MfccDtwAcousticTemplateAdapter classificationAdapter = adapter(calibration(
                10.0D, Math.max(0.0D, distance - 0.01D), distance + 0.01D));

        AcousticUniqueness uniqueness = classificationAdapter.classify(
                candidate, List.of(existing(different)));

        assertEquals(AcousticUniqueness.BORDERLINE, uniqueness);
    }

    @Test
    void shouldFailClosedWhenPackageIsUnavailable() {
        MfccDtwAcousticTemplateAdapter adapter = new MfccDtwAcousticTemplateAdapter(
                registry(Optional.empty()));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> adapter.enroll(
                        phrase(0.0D, 300, 450, 620, 430),
                        phrase(0.0D, 300, 450, 620, 430)));

        assertEquals(ErrorCode.TEMPLATE_INCOMPATIBLE, exception.errorCode());
    }

    @Test
    void shouldRejectSilentAudioBeforeCreatingTemplate() {
        MfccDtwAcousticTemplateAdapter adapter = adapter(calibration(
                0.10D, 0.01D, 0.50D));
        AcousticEnrollmentSample silence = wav(new int[0], 0.0D);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> adapter.enroll(silence, silence));

        assertEquals(ErrorCode.AUDIO_INVALID, exception.errorCode());
    }

    @Test
    void shouldRejectExistingTemplateWithIncompatibleVersion() {
        MfccDtwAcousticTemplateAdapter adapter = adapter(calibration(
                0.10D, 0.01D, 0.50D));
        AcousticEnrollmentCandidate candidate = adapter.enroll(
                phrase(0.0D, 300, 450, 620, 430),
                phrase(0.0D, 300, 450, 620, 430));
        ExistingAcousticTemplate incompatible = new ExistingAcousticTemplate(
                UUID.randomUUID(), candidate.template(), candidate.dialectCode(),
                candidate.dialectPackageVersion(), "other-model",
                candidate.thresholdVersion());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> adapter.classify(candidate, List.of(incompatible)));

        assertEquals(ErrorCode.TEMPLATE_INCOMPATIBLE, exception.errorCode());
    }

    @Test
    void shouldBoundComparisonToOneHundredTemplates() {
        MfccDtwAcousticTemplateAdapter adapter = adapter(calibration(
                0.20D, 0.0001D, 0.0002D));
        AcousticEnrollmentCandidate candidate = adapter.enroll(
                phrase(0.0D, 300, 450, 620, 430),
                phrase(0.0D, 300, 450, 620, 430));
        AcousticEnrollmentCandidate different = adapter.enroll(
                phrase(0.0D, 760, 520, 340, 720),
                phrase(0.0D, 760, 520, 340, 720));
        List<ExistingAcousticTemplate> existingTemplates = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            existingTemplates.add(existing(different));
        }

        assertTimeout(Duration.ofMillis(500), () -> {
            AcousticUniqueness uniqueness = adapter.classify(
                    candidate, existingTemplates);
            assertEquals(AcousticUniqueness.DISTINCT, uniqueness);
        });
    }

    private MfccDtwAcousticTemplateAdapter adapter(
            DialectAcousticCalibration calibration) {
        return new MfccDtwAcousticTemplateAdapter(registry(Optional.of(
                new VerifiedDialectPackage(manifest(), calibration))));
    }

    private DialectPackageRegistry registry(
            Optional<VerifiedDialectPackage> dialectPackage) {
        return new DialectPackageRegistry() {
            @Override
            public DialectPackageState state() {
                return dialectPackage.isPresent()
                        ? DialectPackageState.ACTIVE
                        : DialectPackageState.DISABLED;
            }

            @Override
            public Optional<VerifiedDialectPackage> findActive() {
                return dialectPackage;
            }
        };
    }

    private DialectPackageManifest manifest() {
        return new DialectPackageManifest(
                "zh-Hans-CN-x-wugang",
                "wugang-package-test-v1",
                "MFCC_DTW_V1",
                "mfcc-dtw-test-v1",
                "threshold-test-v1",
                "RESERVED_DISABLED",
                "0".repeat(64),
                "RESERVED_DISABLED",
                "1".repeat(64),
                "RESERVED_DISABLED",
                "RESERVED_DISABLED",
                "1.0.0",
                "1.0.0",
                "acoustic-calibration.json",
                "0".repeat(64),
                "test-key",
                "2026-08-12T14:00:00Z");
    }

    private DialectAcousticCalibration calibration(
            double consistencyMaximum,
            double conflictMaximum,
            double distinctMinimum) {
        return new DialectAcousticCalibration(
                "MFCC_DTW_V1",
                SAMPLE_RATE_HZ,
                25,
                10,
                26,
                13,
                300,
                5_000,
                -60.0D,
                35.0D,
                0.20D,
                0.01D,
                0.20D,
                consistencyMaximum,
                conflictMaximum,
                distinctMinimum,
                0.5D,
                1.5D,
                0.2D);
    }

    private ExistingAcousticTemplate existing(
            AcousticEnrollmentCandidate candidate) {
        return new ExistingAcousticTemplate(
                UUID.randomUUID(),
                candidate.template(),
                candidate.dialectCode(),
                candidate.dialectPackageVersion(),
                candidate.modelVersion(),
                candidate.thresholdVersion());
    }

    private double minimumDistance(
            AcousticEnrollmentCandidate left,
            AcousticEnrollmentCandidate right,
            double windowRatio) {
        AcousticTemplateCodec codec = new AcousticTemplateCodec();
        AcousticTemplateCodec.DecodedAcousticTemplate leftTemplate = codec.decode(
                left.template());
        AcousticTemplateCodec.DecodedAcousticTemplate rightTemplate = codec.decode(
                right.template());
        DynamicTimeWarping dtw = new DynamicTimeWarping();
        return Math.min(
                dtw.distance(leftTemplate.first(), rightTemplate.first(), windowRatio),
                dtw.distance(leftTemplate.second(), rightTemplate.second(), windowRatio));
    }

    private AcousticEnrollmentSample phrase(double frequencyOffset, int... frequencies) {
        return wav(frequencies, frequencyOffset);
    }

    private AcousticEnrollmentSample wav(int[] frequencies, double frequencyOffset) {
        int sampleCount = SAMPLE_RATE_HZ * DURATION_MS / 1_000;
        ByteBuffer pcm = ByteBuffer.allocate(sampleCount * Short.BYTES)
                .order(LITTLE_ENDIAN);
        for (int sampleIndex = 0; sampleIndex < sampleCount; sampleIndex++) {
            double sample;
            if (frequencies.length == 0) {
                sample = 0.0D;
            } else {
                int segment = Math.min(frequencies.length - 1,
                        sampleIndex * frequencies.length / sampleCount);
                double frequency = frequencies[segment] + frequencyOffset;
                sample = 0.35D * Math.sin(
                        2.0D * Math.PI * frequency * sampleIndex / SAMPLE_RATE_HZ);
            }
            pcm.putShort((short) Math.round(sample * Short.MAX_VALUE));
        }
        byte[] pcmBytes = pcm.array();
        ByteBuffer wav = ByteBuffer.allocate(44 + pcmBytes.length).order(LITTLE_ENDIAN);
        wav.put(new byte[] {'R', 'I', 'F', 'F'});
        wav.putInt(36 + pcmBytes.length);
        wav.put(new byte[] {'W', 'A', 'V', 'E'});
        wav.put(new byte[] {'f', 'm', 't', ' '});
        wav.putInt(16);
        wav.putShort((short) 1);
        wav.putShort((short) 1);
        wav.putInt(SAMPLE_RATE_HZ);
        wav.putInt(SAMPLE_RATE_HZ * Short.BYTES);
        wav.putShort((short) Short.BYTES);
        wav.putShort((short) 16);
        wav.put(new byte[] {'d', 'a', 't', 'a'});
        wav.putInt(pcmBytes.length);
        wav.put(pcmBytes);
        return new AcousticEnrollmentSample(
                "audio/wav", wav.array(), DURATION_MS);
    }
}
