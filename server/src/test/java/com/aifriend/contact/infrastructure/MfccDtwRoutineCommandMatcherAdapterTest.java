package com.aifriend.contact.infrastructure;

import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifriend.dialect.application.DialectAcousticCalibration;
import com.aifriend.dialect.application.DialectPackageManifest;
import com.aifriend.dialect.application.DialectPackageRegistry;
import com.aifriend.dialect.application.DialectPackageState;
import com.aifriend.dialect.application.VerifiedDialectPackage;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.task.application.TaskAudioRange;
import com.aifriend.task.application.TaskClientContext;
import com.aifriend.task.domain.TaskIntent;
import com.aifriend.template.application.RoutineCommandRuntimeMatch;
import com.aifriend.template.application.RoutineCommandRuntimeMatchBand;
import com.aifriend.template.application.RoutineCommandRuntimeTemplate;
import com.aifriend.voice.application.ValidatedAudioObject;
import com.aifriend.voice.domain.AudioPurpose;

class MfccDtwRoutineCommandMatcherAdapterTest {

    private static final int SAMPLE_RATE_HZ = 16_000;

    @Test
    void identicalSingleIntentTemplateMustBeUnique() {
        MfccDtwRoutineCommandMatcherAdapter adapter = adapter(activeRegistry());
        byte[] wav = sineWav(1_000, 440.0D);
        byte[] material = actionTemplate(wav);
        try (ValidatedAudioObject audio = audio(wav);
                RoutineCommandRuntimeTemplate template =
                        new RoutineCommandRuntimeTemplate(
                                UUID.randomUUID(), TaskIntent.VOICE_CALL, material)) {
            RoutineCommandRuntimeMatch result = adapter.classify(
                    audio, new TaskAudioRange(100, 600),
                    List.of(template), context());

            assertEquals(RoutineCommandRuntimeMatchBand.UNIQUE, result.band());
            assertEquals(TaskIntent.VOICE_CALL, result.intent());
        }
    }

    @Test
    void identicalTemplatesAcrossIntentsMustRemainAmbiguous() {
        MfccDtwRoutineCommandMatcherAdapter adapter = adapter(activeRegistry());
        byte[] wav = sineWav(1_000, 440.0D);
        byte[] material = actionTemplate(wav);
        try (ValidatedAudioObject audio = audio(wav);
                RoutineCommandRuntimeTemplate voiceTemplate =
                        new RoutineCommandRuntimeTemplate(
                                UUID.randomUUID(), TaskIntent.VOICE_CALL, material);
                RoutineCommandRuntimeTemplate videoTemplate =
                        new RoutineCommandRuntimeTemplate(
                                UUID.randomUUID(), TaskIntent.VIDEO_CALL, material)) {
            RoutineCommandRuntimeMatch result = adapter.classify(
                    audio, new TaskAudioRange(100, 600),
                    List.of(voiceTemplate, videoTemplate), context());

            assertEquals(RoutineCommandRuntimeMatchBand.AMBIGUOUS, result.band());
        }
    }

    @Test
    void missingVerifiedPackageMustFailBeforeAudioDecoding() {
        DialectPackageRegistry registry = mock(DialectPackageRegistry.class);
        when(registry.findActive()).thenReturn(Optional.empty());
        MfccDtwRoutineCommandMatcherAdapter adapter = adapter(registry);
        try (ValidatedAudioObject audio = audio(new byte[] {1, 2, 3});
                RoutineCommandRuntimeTemplate template =
                        new RoutineCommandRuntimeTemplate(
                                UUID.randomUUID(), TaskIntent.VOICE_CALL,
                                new byte[] {1, 2, 3})) {
            BusinessException exception = assertThrows(BusinessException.class,
                    () -> adapter.classify(audio, new TaskAudioRange(100, 600),
                            List.of(template), context()));

            assertEquals(ErrorCode.TEMPLATE_INCOMPATIBLE, exception.errorCode());
        }
    }

    private MfccDtwRoutineCommandMatcherAdapter adapter(
            DialectPackageRegistry registry) {
        return new MfccDtwRoutineCommandMatcherAdapter(registry);
    }

    private DialectPackageRegistry activeRegistry() {
        return new DialectPackageRegistry() {
            @Override
            public DialectPackageState state() {
                return DialectPackageState.ACTIVE;
            }

            @Override
            public Optional<VerifiedDialectPackage> findActive() {
                return Optional.of(new VerifiedDialectPackage(
                        manifest(), calibration()));
            }
        };
    }

    private ValidatedAudioObject audio(byte[] wav) {
        return new ValidatedAudioObject(
                UUID.randomUUID(), UUID.randomUUID(), AudioPurpose.TASK,
                "audio/wav", wav, 1_000, "v1", 0L);
    }

    private TaskClientContext context() {
        return new TaskClientContext(
                "1.0.0", "wechat-v1", "rule-v1",
                "zh-Hans-CN-x-wugang", "dialect-v1", "assist-v1",
                "fusion-v1", "mfcc-v1", "threshold-v1");
    }

    private byte[] actionTemplate(byte[] wav) {
        try (ValidatedAudioObject audio = audio(wav)) {
            PcmAudio fullAudio = new TaskWavPcmNormalizer()
                    .normalize(audio, calibration());
            try {
                double[] samples = fullAudio.samples();
                double[] segment = java.util.Arrays.copyOfRange(
                        samples, SAMPLE_RATE_HZ / 10, SAMPLE_RATE_HZ * 6 / 10);
                java.util.Arrays.fill(samples, 0.0D);
                PcmAudio action = new PcmAudio(segment, SAMPLE_RATE_HZ, 0.0D);
                try {
                    float[][] features = new MfccFeatureExtractor()
                            .extract(action, calibration());
                    try {
                        return new RoutineCommandTemplateCodec().encode(features);
                    } finally {
                        for (float[] frame : features) {
                            java.util.Arrays.fill(frame, 0.0F);
                        }
                    }
                } finally {
                    action.clear();
                }
            } finally {
                fullAudio.clear();
            }
        }
    }

    private static byte[] sineWav(int durationMs, double frequencyHz) {
        int sampleCount = SAMPLE_RATE_HZ * durationMs / 1_000;
        int dataBytes = sampleCount * Short.BYTES;
        ByteBuffer buffer = ByteBuffer.allocate(44 + dataBytes).order(LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        buffer.putInt(36 + dataBytes);
        buffer.put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        buffer.putInt(16).putShort((short) 1).putShort((short) 1);
        buffer.putInt(SAMPLE_RATE_HZ).putInt(SAMPLE_RATE_HZ * Short.BYTES);
        buffer.putShort((short) Short.BYTES).putShort((short) 16);
        buffer.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        buffer.putInt(dataBytes);
        for (int index = 0; index < sampleCount; index++) {
            short sample = (short) Math.round(12_000.0D * Math.sin(
                    2.0D * Math.PI * frequencyHz * index / SAMPLE_RATE_HZ));
            buffer.putShort(sample);
        }
        return buffer.array();
    }

    private DialectPackageManifest manifest() {
        return new DialectPackageManifest(
                "zh-Hans-CN-x-wugang", "dialect-v1", "MFCC_DTW_V1",
                "mfcc-v1", "threshold-v1", "primary-v1", "1".repeat(64),
                "assist-v1", "2".repeat(64), "fusion-v1", "alignment-v1",
                "1.0.0", "1.0.0", "calibration.json", "3".repeat(64),
                "key-v1", "2026-08-24T03:00:00Z");
    }

    private DialectAcousticCalibration calibration() {
        return new DialectAcousticCalibration(
                "MFCC_DTW_V1", SAMPLE_RATE_HZ, 25, 10, 26, 13,
                300, 5_000, -60.0D, 35.0D, 0.20D, 0.01D,
                0.20D, 1.0D, 0.2D, 1.5D,
                0.1D, 1.0D, 0.1D);
    }
}
