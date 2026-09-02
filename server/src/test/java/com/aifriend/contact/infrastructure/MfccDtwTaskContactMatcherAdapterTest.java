package com.aifriend.contact.infrastructure;

import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aifriend.contact.application.ContactAliasRepositoryPort;
import com.aifriend.contact.application.ContactBindingPage;
import com.aifriend.contact.application.ContactBindingRepositoryPort;
import com.aifriend.contact.domain.ContactAlias;
import com.aifriend.contact.domain.ContactAliasStatus;
import com.aifriend.contact.domain.ContactBinding;
import com.aifriend.contact.domain.ContactStatus;
import com.aifriend.dialect.application.DialectAcousticCalibration;
import com.aifriend.dialect.application.DialectPackageManifest;
import com.aifriend.dialect.application.DialectPackageRegistry;
import com.aifriend.dialect.application.DialectPackageState;
import com.aifriend.dialect.application.VerifiedDialectPackage;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;
import com.aifriend.shared.security.SensitiveDataProtector;
import com.aifriend.task.application.TaskAsrSource;
import com.aifriend.task.application.TaskClientContext;
import com.aifriend.task.application.TaskContactCandidate;
import com.aifriend.task.application.TaskRecognizedWord;
import com.aifriend.task.application.TaskSpeechRecognition;
import com.aifriend.task.application.TaskTranscriptCandidate;
import com.aifriend.voice.application.ValidatedAudioObject;
import com.aifriend.voice.domain.AudioPurpose;

class MfccDtwTaskContactMatcherAdapterTest {

    private static final int SAMPLE_RATE_HZ = 16_000;
    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");

    private final ContactAliasRepositoryPort aliasRepository =
            mock(ContactAliasRepositoryPort.class);
    private final ContactBindingRepositoryPort bindingRepository =
            mock(ContactBindingRepositoryPort.class);
    private final SensitiveDataProtector protector = mock(SensitiveDataProtector.class);
    private final DigestService digestService = new DigestService();
    private final UUID ownerId = UUID.randomUUID();
    private final byte[] wav = sineWav(1_000, 440.0D);
    private final byte[] template = matchingTemplate();

    private MfccDtwTaskContactMatcherAdapter adapter;

    @BeforeEach
    void setUp() {
        DialectPackageRegistry registry = new DialectPackageRegistry() {
            @Override
            public DialectPackageState state() {
                return DialectPackageState.ACTIVE;
            }

            @Override
            public Optional<VerifiedDialectPackage> findActive() {
                return Optional.of(new VerifiedDialectPackage(manifest(), calibration()));
            }
        };
        adapter = new MfccDtwTaskContactMatcherAdapter(
                aliasRepository, bindingRepository, protector, digestService, registry);
        when(protector.decryptBytes(any())).thenAnswer(
                invocation -> template.clone());
        when(protector.decrypt(any())).thenAnswer(invocation -> {
            byte[] cipher = invocation.getArgument(0);
            return cipher[0] == 2 ? "二狗子" : "张三";
        });
    }

    @Test
    void shouldReturnUniqueActiveContactForAcousticTopOne() {
        UUID contactId = UUID.randomUUID();
        stub(List.of(binding(contactId)), List.of(alias(contactId, true)));

        List<TaskContactCandidate> result = adapter.match(
                ownerId, audio(), recognition(), context());

        assertEquals(1, result.size());
        assertEquals(contactId, result.get(0).contactId());
        assertEquals("UNIQUE", result.get(0).scoreBand());
        assertEquals("二狗子", result.get(0).alias());
        assertEquals("张三", result.get(0).displayName());
    }

    @Test
    void shouldReturnAmbiguousTopCandidatesWhenMarginIsInsufficient() {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        stub(List.of(binding(firstId), binding(secondId)),
                List.of(alias(firstId, true), alias(secondId, true)));

        List<TaskContactCandidate> result = adapter.match(
                ownerId, audio(), recognition(), context());

        assertEquals(2, result.size());
        assertEquals("AMBIGUOUS", result.get(0).scoreBand());
        assertEquals("AMBIGUOUS", result.get(1).scoreBand());
    }

    @Test
    void shouldExcludeAliasWhoseBindingIsNotActive() {
        UUID contactId = UUID.randomUUID();
        stub(List.of(), List.of(alias(contactId, true)));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> adapter.match(ownerId, audio(), recognition(), context()));

        assertEquals(ErrorCode.NO_CONTACT_MATCH, exception.errorCode());
    }

    @Test
    void shouldFailClosedWhenTemplateDigestIsCorrupt() {
        UUID contactId = UUID.randomUUID();
        stub(List.of(binding(contactId)), List.of(alias(contactId, false)));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> adapter.match(ownerId, audio(), recognition(), context()));

        assertEquals(ErrorCode.TEMPLATE_INCOMPATIBLE, exception.errorCode());
    }

    private void stub(List<ContactBinding> bindings, List<ContactAlias> aliases) {
        when(bindingRepository.findByOwner(ownerId, ContactStatus.ACTIVE, 0, 20))
                .thenReturn(new ContactBindingPage(bindings, 0, 20,
                        bindings.size(), bindings.isEmpty() ? 0 : 1));
        when(aliasRepository.findActiveByOwner(ownerId)).thenReturn(aliases);
    }

    private ContactBinding binding(UUID contactId) {
        return new ContactBinding(
                contactId, ownerId, new byte[] {1}, new byte[] {1},
                new byte[] {1}, new byte[] {1}, new byte[] {3},
                "rules-v1", NOW, null, ContactStatus.ACTIVE,
                ownerId, 0, NOW, NOW, null);
    }

    private ContactAlias alias(UUID contactId, boolean validDigest) {
        byte[] digest = validDigest
                ? digestService.sha256(template) : new byte[32];
        return new ContactAlias(
                UUID.randomUUID(), ownerId, contactId,
                new byte[] {2}, null, "zh-Hans-CN-x-wugang",
                "dialect-v1", "mfcc-v1", "threshold-v1",
                new byte[] {1}, digest, ContactAliasStatus.ACTIVE,
                new byte[] {1}, new byte[] {1}, null, null,
                0, NOW, NOW, null);
    }

    private ValidatedAudioObject audio() {
        return new ValidatedAudioObject(
                UUID.randomUUID(), ownerId, AudioPurpose.TASK,
                "audio/wav", wav, 1_000, "v1", 0);
    }

    private TaskSpeechRecognition recognition() {
        TaskTranscriptCandidate primary = new TaskTranscriptCandidate(
                "给二狗子打电话",
                List.of(new TaskRecognizedWord("二狗子", 100, 600, 0.9D)),
                0.9D, TaskAsrSource.PRIMARY, "primary-v1");
        return new TaskSpeechRecognition(
                primary.transcript(), List.of(primary), List.of(), 0.9D,
                "primary-v1", "assist-v1", "fusion-v1", "alignment-v1");
    }

    private TaskClientContext context() {
        return new TaskClientContext(
                "1.0.0", "wechat-v1", "rule-v1",
                "zh-Hans-CN-x-wugang", "dialect-v1", "assist-v1",
                "fusion-v1", "mfcc-v1", "threshold-v1");
    }

    private byte[] matchingTemplate() {
        short[] samples = sineSamples(1_000, 440.0D);
        double[] segment = new double[SAMPLE_RATE_HZ / 2];
        int from = SAMPLE_RATE_HZ / 10;
        for (int index = 0; index < segment.length; index++) {
            segment[index] = samples[from + index] / 32_768.0D;
        }
        float[][] features = new MfccFeatureExtractor().extract(
                new PcmAudio(segment, SAMPLE_RATE_HZ, 0.0D), calibration());
        return new AcousticTemplateCodec().encode(List.of(features, features));
    }

    private static byte[] sineWav(int durationMs, double frequencyHz) {
        short[] samples = sineSamples(durationMs, frequencyHz);
        int dataBytes = samples.length * Short.BYTES;
        ByteBuffer buffer = ByteBuffer.allocate(44 + dataBytes).order(LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        buffer.putInt(36 + dataBytes);
        buffer.put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        buffer.putInt(16).putShort((short) 1).putShort((short) 1);
        buffer.putInt(SAMPLE_RATE_HZ).putInt(SAMPLE_RATE_HZ * Short.BYTES);
        buffer.putShort((short) Short.BYTES).putShort((short) 16);
        buffer.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        buffer.putInt(dataBytes);
        for (short sample : samples) {
            buffer.putShort(sample);
        }
        return buffer.array();
    }

    private static short[] sineSamples(int durationMs, double frequencyHz) {
        int sampleCount = SAMPLE_RATE_HZ * durationMs / 1_000;
        short[] samples = new short[sampleCount];
        for (int index = 0; index < sampleCount; index++) {
            samples[index] = (short) Math.round(12_000.0D * Math.sin(
                    2.0D * Math.PI * frequencyHz * index / SAMPLE_RATE_HZ));
        }
        return samples;
    }

    private DialectPackageManifest manifest() {
        return new DialectPackageManifest(
                "zh-Hans-CN-x-wugang", "dialect-v1", "MFCC_DTW_V1",
                "mfcc-v1", "threshold-v1", "primary-v1", "1".repeat(64),
                "assist-v1", "2".repeat(64), "fusion-v1", "alignment-v1",
                "1.0.0", "1.0.0", "calibration.json", "3".repeat(64),
                "key-v1", "2026-08-20T12:00:00Z");
    }

    private DialectAcousticCalibration calibration() {
        return new DialectAcousticCalibration(
                "MFCC_DTW_V1", SAMPLE_RATE_HZ, 25, 10, 26, 13,
                300, 5_000, -60.0D, 35.0D, 0.20D, 0.01D,
                0.20D, 1.0D, 0.2D, 1.5D,
                0.1D, 1.0D, 0.1D);
    }
}
