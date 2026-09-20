package com.aifriend.contact.infrastructure;

import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void shouldUseEnrollmentConsistencyForLegacyTemplatePair() {
        // Valid encoded feature fixtures, not actual user recordings or accuracy evidence.
        var registry = new com.aifriend.dialect.infrastructure.BasicExperienceDialectPackageRegistry();
        var calibration = registry.findActive().orElseThrow().acousticCalibration();
        var adapter = new MfccDtwAcousticTemplateAdapter(registry);
        var legacy = basicFeatures(0.0F, 0.8F);
        var candidate = basicFeatures(1.9F, 2.0F);
        var codec = new AcousticTemplateCodec();
        var oldFeatures = codec.decode(legacy.template());
        var newFeatures = codec.decode(candidate.template());
        var dtw = new DynamicTimeWarping();
        double window = calibration.dtwWindowRatio();
        double baseline = dtw.distance(oldFeatures.first(), oldFeatures.second(), window);
        assertTrue(baseline <= calibration.enrollmentConsistencyMaxDistance());
        assertTrue(baseline > calibration.taskAliasUniqueMaxDistance());
        // For these ordered fixtures this is the minimum of all four cross-take distances.
        double cross = dtw.distance(oldFeatures.second(), newFeatures.first(), window);
        assertTrue(cross < calibration.uniquenessDistinctMinDistance());
        assertTrue(cross - baseline >= calibration.taskAliasMinimumMargin());
        assertSyntheticProbeUnique(0.1F, oldFeatures, newFeatures, calibration);
        assertSyntheticProbeUnique(1.95F, newFeatures, oldFeatures, calibration);
        assertEquals(AcousticUniqueness.DISTINCT,
                adapter.classify(candidate, List.of(existing(legacy))));
    }

    private void assertSyntheticProbeUnique(float value,
            AcousticTemplateCodec.DecodedAcousticTemplate expected,
            AcousticTemplateCodec.DecodedAcousticTemplate other,
            DialectAcousticCalibration calibration) {
        float[][] probe = {{value}, {value}, {value}};
        var dtw = new DynamicTimeWarping();
        double window = calibration.dtwWindowRatio();
        double ownDistance = Math.min(dtw.distance(probe, expected.first(), window),
                dtw.distance(probe, expected.second(), window));
        double otherDistance = Math.min(dtw.distance(probe, other.first(), window),
                dtw.distance(probe, other.second(), window));
        assertTrue(ownDistance <= calibration.taskAliasUniqueMaxDistance());
        assertTrue(otherDistance - ownDistance >= calibration.taskAliasMinimumMargin());
    }

    @Test
    void shouldReportFixedComparisonReasonsWithoutAcousticOrContactData() {
        var registry = new com.aifriend.dialect.infrastructure.BasicExperienceDialectPackageRegistry();
        var adapter = new MfccDtwAcousticTemplateAdapter(registry);
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                MfccDtwAcousticTemplateAdapter.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var reference = basicFeatures(0.0F, 0.1F);
            adapter.classify(reference, List.of(existing(basicFeatures(1.45F, 2.5F))));
            adapter.classify(basicFeatures(1.45F, 2.5F), List.of(existing(reference)));
            adapter.classify(basicFeatures(0.0F, 1.01F), List.of(existing(basicFeatures(2.31F, 3.32F))));
            adapter.classify(reference, List.of(existing(basicFeatures(0.35F, 0.45F))));
            adapter.classify(reference, List.of(existing(reference)));
            adapter.classify(reference, List.of(existing(basicFeatures(0.8F, 0.9F))));
            new MfccDtwAcousticTemplateAdapter(registry(registry.findActive()))
                    .classify(reference, List.of(existing(basicFeatures(0.8F, 0.9F))));
            String prefix = "Alias enrollment comparison outcome=";
            assertEquals(List.of(
                    prefix + "BORDERLINE mode=BASIC_EXPERIENCE templates=1 reasons=[EXISTING_VARIATION]",
                    prefix + "BORDERLINE mode=BASIC_EXPERIENCE templates=1 reasons=[CANDIDATE_VARIATION]",
                    prefix + "BORDERLINE mode=BASIC_EXPERIENCE templates=1 reasons=[CANDIDATE_VARIATION, EXISTING_VARIATION]",
                    prefix + "BORDERLINE mode=BASIC_EXPERIENCE templates=1 reasons=[INSUFFICIENT_MARGIN]",
                    prefix + "CONFLICT mode=BASIC_EXPERIENCE templates=1 reasons=[ABSOLUTE_CONFLICT]",
                    prefix + "DISTINCT mode=BASIC_EXPERIENCE templates=1 reasons=[]",
                    prefix + "BORDERLINE mode=ACTIVE templates=1 reasons=[SIGNED_ABSOLUTE_BAND]"),
                    appender.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void shouldAcceptStableSeparatedBasicSyntheticContents() {
        var registry = new com.aifriend.dialect.infrastructure.BasicExperienceDialectPackageRegistry();
        var adapter = new MfccDtwAcousticTemplateAdapter(registry);
        var first = adapter.enroll(
                phrase(0.0D, 300, 450, 620, 430),
                phrase(1.0D, 300, 450, 620, 430));
        var other = adapter.enroll(
                phrase(0.0D, 760, 520, 340, 720),
                phrase(1.0D, 760, 520, 340, 720));
        AcousticUniqueness result = adapter.classify(first, List.of(existing(other)));
        // Characterization only: synthetic tones are not speech accuracy evidence.
        System.out.printf(java.util.Locale.ROOT,
                "ALIAS_SYNTHETIC_BASIC decision=%s%n", result);
        assertEquals(AcousticUniqueness.CONFLICT,
                adapter.classify(first, List.of(existing(first))));
        assertEquals(AcousticUniqueness.DISTINCT, result);
    }

    @Test
    void shouldKeepBasicPersonalSeparationFailClosed() {
        var registry = new com.aifriend.dialect.infrastructure.BasicExperienceDialectPackageRegistry();
        var adapter = new MfccDtwAcousticTemplateAdapter(registry);
        var reference = basicFeatures(0.0F, 0.1F);
        assertEquals(AcousticUniqueness.DISTINCT,
                adapter.classify(reference, List.of(existing(basicFeatures(0.8F, 0.9F)))));
        assertEquals(AcousticUniqueness.CONFLICT,
                adapter.classify(reference, List.of(existing(basicFeatures(0.29F, 0.39F)))));
        assertEquals(AcousticUniqueness.BORDERLINE,
                adapter.classify(reference, List.of(existing(basicFeatures(0.35F, 0.45F)))));
        assertEquals(AcousticUniqueness.BORDERLINE,
                adapter.classify(reference, List.of(existing(basicFeatures(0.9F, 1.6F)))));
        assertEquals(AcousticUniqueness.BORDERLINE,
                adapter.classify(basicFeatures(0.9F, 1.6F), List.of(existing(reference))));
        assertEquals(AcousticUniqueness.BORDERLINE, adapter.classify(reference, List.of(
                existing(basicFeatures(0.8F, 0.9F)), existing(basicFeatures(0.35F, 0.45F)))));
        assertEquals(AcousticUniqueness.CONFLICT, adapter.classify(reference, List.of(
                existing(basicFeatures(0.8F, 0.9F)), existing(basicFeatures(0.29F, 0.39F)))));
        var signedMode = new MfccDtwAcousticTemplateAdapter(registry(registry.findActive()));
        assertEquals(AcousticUniqueness.BORDERLINE,
                signedMode.classify(reference, List.of(existing(basicFeatures(0.8F, 0.9F)))));
    }

    private AcousticEnrollmentCandidate basicFeatures(float first, float second) {
        var manifest = new com.aifriend.dialect.infrastructure.BasicExperienceDialectPackageRegistry()
                .findActive().orElseThrow().manifest();
        byte[] encoded = new AcousticTemplateCodec().encode(List.of(
                new float[][] {{first}, {first}, {first}},
                new float[][] {{second}, {second}, {second}}));
        return new AcousticEnrollmentCandidate(encoded, manifest.dialectCode(), manifest.packageVersion(),
                manifest.acousticModelVersion(), manifest.thresholdVersion());
    }

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
