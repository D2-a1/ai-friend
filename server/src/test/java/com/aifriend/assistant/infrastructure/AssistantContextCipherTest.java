package com.aifriend.assistant.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import com.aifriend.assistant.domain.AssistantAnswer.Purpose;
import com.aifriend.assistant.domain.AssistantConversation;
import com.aifriend.assistant.domain.AssistantConversation.Turn;
import com.aifriend.assistant.infrastructure.AssistantContextCipher.Binding;
import com.aifriend.shared.security.SecurityKeyMaterial;
import com.aifriend.shared.security.SensitiveDataProtector;

/** 真实AES-GCM，全部内容/密钥为合成数据，无数据库或外部模型。 */
class AssistantContextCipherTest {
    private static UUID id(int value) { return new UUID(0, value); }
    private static Binding binding(Purpose purpose) { return new Binding(id(1), id(2), purpose, "policy-v1", 8); }
    private static SensitiveDataProtector protector(int seed) {
        byte[] key = new byte[32]; key[0] = (byte) seed;
        return new SensitiveDataProtector(new SecurityKeyMaterial(new SecretKeySpec(key, "HmacSHA256"),
                new SecretKeySpec(key, "AES"), new SecretKeySpec(key, "HmacSHA256")));
    }
    private static AssistantConversation conversation(Purpose purpose) {
        return new AssistantConversation(purpose, List.of(new Turn(id(3), 2, "怎样使用小友？", "从首页进入。")));
    }

    @Test void actualAesRoundTripForBothPurposesUsesRandomCiphertexts() {
        var codec = new AssistantContextCipher(protector(1));
        for (Purpose purpose : Purpose.values()) {
            var context = conversation(purpose); var scope = binding(purpose);
            byte[] first = codec.encrypt(scope, context); byte[] second = codec.encrypt(scope, context);
            assertThat(first).isNotEqualTo(second);
            assertThat(codec.decrypt(scope, first)).isEqualTo(context);
            assertThat(codec.decrypt(scope, second)).isEqualTo(context);
        }
    }

    @Test void swappingOwnerSessionPurposePolicyOrVersionCannotDecrypt() {
        var codec = new AssistantContextCipher(protector(1));
        byte[] encrypted = codec.encrypt(binding(Purpose.PUBLIC_KNOWLEDGE), conversation(Purpose.PUBLIC_KNOWLEDGE));
        for (Binding wrong : List.of(new Binding(id(9), id(2), Purpose.PUBLIC_KNOWLEDGE, "policy-v1", 8),
                new Binding(id(1), id(9), Purpose.PUBLIC_KNOWLEDGE, "policy-v1", 8),
                binding(Purpose.CONTACT_GRAPH), new Binding(id(1), id(2), Purpose.PUBLIC_KNOWLEDGE, "policy-v2", 8),
                new Binding(id(1), id(2), Purpose.PUBLIC_KNOWLEDGE, "policy-v1", 9))) {
            assertThatThrownBy(() -> codec.decrypt(wrong, encrypted)).hasMessage("DECRYPTION_FAILED").hasNoCause();
        }
    }

    @Test void ciphertextTamperingOrWrongKeyFailsWithoutLeakingReasons() {
        var codec = new AssistantContextCipher(protector(1));
        byte[] encrypted = codec.encrypt(binding(Purpose.PUBLIC_KNOWLEDGE), conversation(Purpose.PUBLIC_KNOWLEDGE));
        assertThatThrownBy(() -> new AssistantContextCipher(protector(2)).decrypt(binding(Purpose.PUBLIC_KNOWLEDGE), encrypted))
                .hasMessage("DECRYPTION_FAILED").hasNoCause();
        encrypted[encrypted.length - 1] ^= 1;
        assertThatThrownBy(() -> codec.decrypt(binding(Purpose.PUBLIC_KNOWLEDGE), encrypted)).hasMessage("DECRYPTION_FAILED");
    }

    @Test void fullFourTurnUnicodeBudgetRoundTripsWithoutTruncation() {
        var crypto = protector(1); var codec = new AssistantContextCipher(crypto);
        var context = new AssistantConversation(Purpose.PUBLIC_KNOWLEDGE, List.of(
                new Turn(id(10), 2, "😀".repeat(500), "😀".repeat(360)),
                new Turn(id(11), 4, "😀".repeat(500), "😀".repeat(360)),
                new Turn(id(12), 6, "😀".repeat(500), "😀".repeat(360)),
                new Turn(id(13), 8, "😀".repeat(500), "😀".repeat(360))));
        byte[] encrypted = codec.encrypt(binding(Purpose.PUBLIC_KNOWLEDGE), context);
        byte[] json = crypto.decryptBytes(encrypted);
        try {
            assertThat(json.length).isGreaterThan(32768).isLessThanOrEqualTo(49152);
            assertThat(codec.decrypt(binding(Purpose.PUBLIC_KNOWLEDGE), encrypted)).isEqualTo(context);
        } finally { java.util.Arrays.fill(json, (byte) 0); }
    }

    @Test void duplicateUnknownMissingCoercedAndTrailingJsonIsRejectedEvenWhenEncryptionIsValid() {
        var crypto = protector(1); var codec = new AssistantContextCipher(crypto);
        var scope = binding(Purpose.PUBLIC_KNOWLEDGE);
        String json = crypto.decrypt(codec.encrypt(scope, conversation(Purpose.PUBLIC_KNOWLEDGE)));
        for (String invalid : List.of(json.replace("\"schema\":1", "\"schema\":1,\"schema\":1"),
                json.replace("\"schema\":1", "\"schema\":1,\"unknown\":true"),
                json.replace("\"schema\":1,", ""), json.replace("\"schema\":1", "\"schema\":null"),
                json.replace("\"version\":8", "\"version\":\"8\""),
                json.replace("\"version\":8", "\"version\":8.0"),
                json.replace("\"schema\":1", "\"schema\":2"), json + " {}")) {
            byte[] cipher = crypto.encrypt(invalid);
            assertThatThrownBy(() -> codec.decrypt(scope, cipher)).hasMessage("DECRYPTION_FAILED").hasNoCause();
        }
    }

    @Test void malformedContextAndFutureResultReferencesCannotBeRestored() {
        var crypto = protector(1); var codec = new AssistantContextCipher(crypto);
        var scope = binding(Purpose.PUBLIC_KNOWLEDGE);
        String json = crypto.decrypt(codec.encrypt(scope, conversation(Purpose.PUBLIC_KNOWLEDGE)));
        for (String invalid : List.of(json.replace("\"resultVersion\":2", "\"resultVersion\":9"),
                json.replace("\"question\":\"怎样使用小友？\"", "\"question\":\"\""),
                json.replace("\"turns\":[", "\"turns\":[null,"))) {
            assertThatThrownBy(() -> codec.decrypt(scope, crypto.encrypt(invalid))).hasMessage("DECRYPTION_FAILED");
        }
    }

    @Test void encryptionRejectsCrossPurposeOrFutureContextBeforeCallingProtector() {
        var crypto = mock(SensitiveDataProtector.class); var codec = new AssistantContextCipher(crypto);
        assertThatThrownBy(() -> codec.encrypt(binding(Purpose.PUBLIC_KNOWLEDGE), conversation(Purpose.CONTACT_GRAPH)))
                .hasMessage("CONTEXT_BINDING_MISMATCH");
        assertThatThrownBy(() -> codec.encrypt(new Binding(id(1), id(2), Purpose.PUBLIC_KNOWLEDGE, "policy-v1", 1),
                conversation(Purpose.PUBLIC_KNOWLEDGE))).hasMessage("CONTEXT_BINDING_MISMATCH");
        verifyNoInteractions(crypto);
    }

    @Test void oversizedCipherIsRejectedBeforeCryptographicAllocation() {
        var crypto = mock(SensitiveDataProtector.class); var codec = new AssistantContextCipher(crypto);
        for (byte[] invalid : new byte[][]{null, new byte[28], new byte[49181]}) {
            assertThatThrownBy(() -> codec.decrypt(binding(Purpose.PUBLIC_KNOWLEDGE), invalid)).hasMessage("DECRYPTION_FAILED");
        }
        verifyNoInteractions(crypto);
    }

    @Test void ownedPlainBytesAreZeroedAfterEncryptionEvenOnFailure() {
        var crypto = mock(SensitiveDataProtector.class); var codec = new AssistantContextCipher(crypto);
        var capture = new AtomicReference<byte[]>();
        when(crypto.encryptBytes(any())).thenAnswer(call -> {
            capture.set(call.getArgument(0)); throw new IllegalStateException("PRIVATE_FAILURE");
        });
        assertThatThrownBy(() -> codec.encrypt(binding(Purpose.PUBLIC_KNOWLEDGE), conversation(Purpose.PUBLIC_KNOWLEDGE)))
                .hasMessage("STORAGE_UNAVAILABLE").hasNoCause();
        assertThat(capture.get()).containsOnly((byte) 0);
    }

    @Test void borrowedCipherRemainsIntactAndOwnedBuffersAreZeroedOnSuccessAndScopeFailure() {
        var real = protector(1); var crypto = mock(SensitiveDataProtector.class); var codec = new AssistantContextCipher(crypto);
        var realCodec = new AssistantContextCipher(real);
        var original = realCodec.encrypt(binding(Purpose.PUBLIC_KNOWLEDGE), conversation(Purpose.PUBLIC_KNOWLEDGE));
        byte[] borrowed = original.clone();
        var plain = new AtomicReference<byte[]>(); var copied = new AtomicReference<byte[]>();
        when(crypto.decryptBytes(any())).thenAnswer(call -> {
            byte[] input = call.getArgument(0); copied.set(input);
            byte[] output = real.decryptBytes(input); plain.set(output); return output;
        });
        assertThat(codec.decrypt(binding(Purpose.PUBLIC_KNOWLEDGE), borrowed)).isEqualTo(conversation(Purpose.PUBLIC_KNOWLEDGE));
        assertThat(borrowed).isEqualTo(original); assertThat(copied.get()).containsOnly((byte) 0); assertThat(plain.get()).containsOnly((byte) 0);
        assertThatThrownBy(() -> codec.decrypt(binding(Purpose.CONTACT_GRAPH), borrowed)).hasMessage("DECRYPTION_FAILED");
        assertThat(borrowed).isEqualTo(original); assertThat(copied.get()).containsOnly((byte) 0); assertThat(plain.get()).containsOnly((byte) 0);
    }
}
