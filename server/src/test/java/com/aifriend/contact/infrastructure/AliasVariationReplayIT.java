package com.aifriend.contact.infrastructure;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.sound.sampled.AudioSystem;
import org.junit.jupiter.api.Test;
import com.aifriend.contact.application.AcousticEnrollmentCandidate;
import com.aifriend.contact.application.AcousticEnrollmentSample;
import com.aifriend.contact.application.ExistingAcousticTemplate;
import com.aifriend.contact.application.AcousticUniqueness;
import com.aifriend.dialect.infrastructure.BasicExperienceDialectPackageRegistry;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;

/** Explicit diagnostic sweep of generated WAVs, not a quality/release gate. */
class AliasVariationReplayIT {
    @Test
    void shouldNotApplyRuntimeQueryThresholdToValidEnrollmentPair() throws Exception {
        String configured = System.getProperty("aifriend.aliasReplayRoot", "");
        assertTrue(!configured.isBlank());
        Path root = Path.of(configured).toAbsolutePath().normalize();
        var registry = new BasicExperienceDialectPackageRegistry();
        var adapter = new MfccDtwAcousticTemplateAdapter(registry);
        var oldAlias = adapter.enroll(sample(root, "male", "second", 1), sample(root, "male", "second", 9));
        var newAlias = adapter.enroll(sample(root, "male", "third", 1), sample(root, "male", "third", 2));
        var decoded = new AcousticTemplateCodec().decode(oldAlias.template());
        var calibration = registry.findActive().orElseThrow().acousticCalibration();
        double baseline = new DynamicTimeWarping().distance(decoded.first(), decoded.second(), calibration.dtwWindowRatio());
        assertTrue(baseline > calibration.taskAliasUniqueMaxDistance());
        assertTrue(baseline <= calibration.enrollmentConsistencyMaxDistance());
        assertTrue(adapter.isSafetyCommandDistinct(newAlias, List.of(existing(oldAlias))));
        assertEquals(AcousticUniqueness.DISTINCT, adapter.classify(newAlias, List.of(existing(oldAlias))));
        assertEquals(AcousticUniqueness.DISTINCT, adapter.classify(oldAlias, List.of(existing(newAlias))));
        var newDecoded = new AcousticTemplateCodec().decode(newAlias.template());
        for (int take : List.of(3, 4)) {
            assertHeldOutUnique(sample(root, "male", "second", take), decoded, newDecoded, calibration);
            assertHeldOutUnique(sample(root, "male", "third", take), newDecoded, decoded, calibration);
        }
        var sameWord = adapter.enroll(sample(root, "male", "second", 3), sample(root, "male", "second", 4));
        assertNotEquals(AcousticUniqueness.DISTINCT, adapter.classify(sameWord, List.of(existing(oldAlias))));
    }

    @Test
    void shouldRejectHomophonesAndKeepDistinctNeighborWords() throws Exception {
        String configured = System.getProperty("aifriend.aliasReplayRoot", "");
        assertTrue(!configured.isBlank());
        Path root = Path.of(configured).toAbsolutePath().normalize();
        var registry = new BasicExperienceDialectPackageRegistry();
        var adapter = new MfccDtwAcousticTemplateAdapter(registry);
        for (String voice : List.of("female", "male")) {
            var third = adapter.enroll(sample(root, voice, "third", 1), sample(root, voice, "third", 2));
            var homophone = adapter.enroll(sample(root, voice, "third_homophone", 3), sample(root, voice, "third_homophone", 4));
            var fourth = adapter.enroll(sample(root, voice, "fourth", 1), sample(root, voice, "fourth", 2));
            assertNotEquals(AcousticUniqueness.DISTINCT, adapter.classify(homophone, List.of(existing(third))));
            assertNotEquals(AcousticUniqueness.DISTINCT, adapter.classify(third, List.of(existing(homophone))));
            assertEquals(AcousticUniqueness.DISTINCT, adapter.classify(fourth, List.of(existing(third))));
            assertEquals(AcousticUniqueness.DISTINCT, adapter.classify(third, List.of(existing(fourth))));
            System.out.printf("ALIAS_NEIGHBOR voice=%s homophone=REJECTED thirdFourth=DISTINCT%n", voice);
        }
    }

    private void assertHeldOutUnique(AcousticEnrollmentSample sample,
            AcousticTemplateCodec.DecodedAcousticTemplate own,
            AcousticTemplateCodec.DecodedAcousticTemplate other,
            com.aifriend.dialect.application.DialectAcousticCalibration calibration) {
        var features = new MfccFeatureExtractor().extract(new WavPcmNormalizer().normalize(sample, calibration), calibration);
        var dtw = new DynamicTimeWarping();
        double ownDistance = Math.min(dtw.distance(features, own.first(), calibration.dtwWindowRatio()),
                dtw.distance(features, own.second(), calibration.dtwWindowRatio()));
        double otherDistance = Math.min(dtw.distance(features, other.first(), calibration.dtwWindowRatio()),
                dtw.distance(features, other.second(), calibration.dtwWindowRatio()));
        assertTrue(ownDistance <= calibration.taskAliasUniqueMaxDistance());
        assertTrue(otherDistance - ownDistance >= calibration.taskAliasMinimumMargin());
    }

    @Test
    void characterizeVariationsWithoutRelaxingProductionPolicy() throws Exception {
        String configured = System.getProperty("aifriend.aliasReplayRoot", "");
        assertTrue(!configured.isBlank(), "Explicit synthetic WAV directory required");
        Path root = Path.of(configured).toAbsolutePath().normalize();
        var registry = new BasicExperienceDialectPackageRegistry();
        var adapter = new MfccDtwAcousticTemplateAdapter(registry);
        var calibration = registry.findActive().orElseThrow().acousticCalibration();
        var codec = new AcousticTemplateCodec();
        var dtw = new DynamicTimeWarping();
        int completed = 0;
        for (String voice : List.of("female", "male")) {
            for (String word : List.of("second", "third", "eldest", "mother")) {
                var clean = adapter.enroll(sample(root, voice, word, 3), sample(root, voice, word, 4));
                String otherWord = word.equals("third") ? "second" : "third";
                var other = adapter.enroll(sample(root, voice, otherWord, 1), sample(root, voice, otherWord, 3));
                for (int take = 2; take <= 11; take++) {
                    if (take == 3 || take == 4) continue;
                    try {
                        var varied = adapter.enroll(sample(root, voice, word, 1), sample(root, voice, word, take));
                        var decoded = codec.decode(varied.template());
                        double baseline = dtw.distance(decoded.first(), decoded.second(), calibration.dtwWindowRatio());
                        var same = adapter.classify(varied, List.of(existing(clean)));
                        assertNotEquals(AcousticUniqueness.DISTINCT, same, "Disjoint repeated word must not become a new identity");
                        var different = adapter.classify(other, List.of(existing(varied)));
                        // Hypothesis only: evaluate the relative-margin rule separately, no production mutation.
                        boolean relative = adapter.isSafetyCommandDistinct(other, List.of(existing(varied)));
                        System.out.printf(Locale.ROOT,
                                "ALIAS_VARIATION voice=%s word=%s take=%d baseline=%.6f same=%s different=%s relativeOnly=%s%n",
                                voice, word, take, baseline, same, different, relative);
                    } catch (BusinessException rejected) {
                        assertTrue(List.of(ErrorCode.AUDIO_INVALID, ErrorCode.ENROLLMENT_INCONSISTENT)
                                .contains(rejected.errorCode()), "Unexpected infrastructure/version error is not an audio rejection");
                        System.out.printf("ALIAS_VARIATION voice=%s word=%s take=%d rejected=%s%n",
                                voice, word, take, rejected.errorCode());
                    }
                    completed++;
                }
            }
        }
        assertTrue(completed == 64, "All diagnostic cases must execute; not an accuracy assertion");
    }

    private ExistingAcousticTemplate existing(AcousticEnrollmentCandidate value) {
        return new ExistingAcousticTemplate(UUID.randomUUID(), value.template(), value.dialectCode(),
                value.dialectPackageVersion(), value.modelVersion(), value.thresholdVersion());
    }

    private AcousticEnrollmentSample sample(Path root, String voice, String word, int take) throws Exception {
        Path file = root.resolve(voice + "-" + word + "-" + take + ".wav");
        assertTrue(!Files.isSymbolicLink(file) && Files.size(file) <= 200_000);
        try (var audio = AudioSystem.getAudioInputStream(file.toFile())) {
            int duration = (int) Math.round(audio.getFrameLength() * 1000.0 / audio.getFormat().getFrameRate());
            return new AcousticEnrollmentSample("audio/wav", Files.readAllBytes(file), duration);
        }
    }
}
