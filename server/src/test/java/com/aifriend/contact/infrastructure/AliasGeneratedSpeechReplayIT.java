package com.aifriend.contact.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.sound.sampled.AudioSystem;

import org.junit.jupiter.api.Test;
import com.aifriend.contact.application.AcousticEnrollmentSample;
import com.aifriend.contact.application.AcousticEnrollmentCandidate;
import com.aifriend.contact.application.AcousticUniqueness;
import com.aifriend.contact.application.ExistingAcousticTemplate;
import com.aifriend.dialect.infrastructure.BasicExperienceDialectPackageRegistry;

/** Explicit synthetic-speech smoke only, never a real-dialect release gate. */
class AliasGeneratedSpeechReplayIT {
    private static final List<String> WORDS = List.of("second", "third", "eldest", "mother");

    @Test
    void characterizeEnrollmentAndHeldOutSpeech() throws Exception {
        String configured = System.getProperty("aifriend.aliasReplayRoot", "");
        assertTrue(!configured.isBlank(), "Explicit synthetic WAV directory is required");
        Path root = Path.of(configured).toAbsolutePath().normalize();
        var registry = new BasicExperienceDialectPackageRegistry();
        var adapter = new MfccDtwAcousticTemplateAdapter(registry);
        var calibration = registry.findActive().orElseThrow().acousticCalibration();
        var codec = new AcousticTemplateCodec();
        var dtw = new DynamicTimeWarping();
        int distinctPairs = 0;
        for (String speaker : List.of("female", "male")) {
            var candidates = new java.util.ArrayList<AcousticEnrollmentCandidate>();
            var probes = new java.util.ArrayList<float[][]>();
            for (String word : WORDS) {
                candidates.add(adapter.enroll(sample(root, speaker, word, 1), sample(root, speaker, word, 2)));
                probes.add(new MfccFeatureExtractor().extract(new WavPcmNormalizer().normalize(
                        sample(root, speaker, word, 3), calibration), calibration));
            }
            for (int i = 0; i < WORDS.size(); i++) {
                var left = codec.decode(candidates.get(i).template());
                var repeated = adapter.enroll(sample(root, speaker, WORDS.get(i), 3),
                        sample(root, speaker, WORDS.get(i), 4));
                var repeatedDecision = adapter.classify(repeated, List.of(existing(candidates.get(i))));
                System.out.printf("ALIAS_REPEAT speaker=%s word=%s result=%s%n",
                        speaker, WORDS.get(i), repeatedDecision);
                assertNotEquals(AcousticUniqueness.DISTINCT, repeatedDecision,
                        "Disjoint takes of the same synthetic word must not register as a different contact");
                double withinLeft = dtw.distance(left.first(), left.second(), calibration.dtwWindowRatio());
                assertEquals(AcousticUniqueness.CONFLICT, adapter.classify(candidates.get(i),
                        List.of(existing(candidates.get(i)))));
                for (int j = i + 1; j < WORDS.size(); j++) {
                    var right = codec.decode(candidates.get(j).template());
                    double withinRight = dtw.distance(right.first(), right.second(), calibration.dtwWindowRatio());
                    double cross = Math.min(Math.min(dtw.distance(left.first(), right.first(), 0.2),
                            dtw.distance(left.first(), right.second(), 0.2)),
                            Math.min(dtw.distance(left.second(), right.first(), 0.2),
                                    dtw.distance(left.second(), right.second(), 0.2)));
                    double leftMargin = nearest(probes.get(i), right, dtw) - nearest(probes.get(i), left, dtw);
                    double rightMargin = nearest(probes.get(j), left, dtw) - nearest(probes.get(j), right, dtw);
                    assertTrue(nearest(probes.get(i), left, dtw) <= calibration.taskAliasUniqueMaxDistance());
                    assertTrue(nearest(probes.get(j), right, dtw) <= calibration.taskAliasUniqueMaxDistance());
                    assertTrue(leftMargin >= calibration.taskAliasMinimumMargin());
                    assertTrue(rightMargin >= calibration.taskAliasMinimumMargin());
                    if (adapter.classify(candidates.get(i), List.of(existing(candidates.get(j))))
                            == AcousticUniqueness.DISTINCT) {
                        distinctPairs++;
                    }
                    System.out.printf(Locale.ROOT,
                            "ALIAS_SPEECH speaker=%s pair=%s/%s within=%.3f/%.3f cross=%.3f baselineMargin=%.3f heldOutMargins=%.3f/%.3f result=%s%n",
                            speaker, WORDS.get(i), WORDS.get(j), withinLeft, withinRight, cross,
                            cross - Math.max(withinLeft, withinRight), leftMargin, rightMargin,
                            adapter.classify(candidates.get(i), List.of(existing(candidates.get(j)))));
                }
            }
        }
        assertEquals(12, distinctPairs, "Stable, held-out-separated synthetic words must enroll distinctly");
    }

    private double nearest(float[][] probe, AcousticTemplateCodec.DecodedAcousticTemplate template, DynamicTimeWarping dtw) {
        return Math.min(dtw.distance(probe, template.first(), 0.2), dtw.distance(probe, template.second(), 0.2));
    }

    private ExistingAcousticTemplate existing(AcousticEnrollmentCandidate value) {
        return new ExistingAcousticTemplate(UUID.randomUUID(), value.template(), value.dialectCode(),
                value.dialectPackageVersion(), value.modelVersion(), value.thresholdVersion());
    }

    private AcousticEnrollmentSample sample(Path root, String speaker, String word, int take) throws Exception {
        Path path = root.resolve(speaker + "-" + word + "-" + take + ".wav");
        assertTrue(!Files.isSymbolicLink(path) && Files.size(path) <= 200_000);
        try (var audio = AudioSystem.getAudioInputStream(path.toFile())) {
            int duration = (int) Math.round(audio.getFrameLength() * 1000.0 / audio.getFormat().getFrameRate());
            return new AcousticEnrollmentSample("audio/wav", Files.readAllBytes(path), duration);
        }
    }
}
