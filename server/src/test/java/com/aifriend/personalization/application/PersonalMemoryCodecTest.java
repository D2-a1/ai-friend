package com.aifriend.personalization.application;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

import com.aifriend.personalization.domain.AmbiguousCallPreference;
import com.aifriend.personalization.domain.DialogueStylePreference;
import com.aifriend.personalization.domain.PersonalMemoryPreferences;
import com.aifriend.personalization.domain.SpeechRatePreference;
import com.aifriend.shared.security.DigestService;
import com.aifriend.shared.security.SecurityKeyMaterial;
import com.aifriend.shared.security.SensitiveDataProtector;

class PersonalMemoryCodecTest {

    private final DigestService digestService = new DigestService();
    private final PersonalMemoryCodec codec = new PersonalMemoryCodec(
            new SensitiveDataProtector(keyMaterial()), digestService);

    @Test
    void shouldEncryptAndRoundTripConstrainedPreferences() {
        PersonalMemoryPreferences preferences = preferences();

        PersonalMemoryCodec.EncodedPersonalMemory encoded = codec.encode(preferences);

        assertThat(new String(encoded.cipher(), UTF_8))
                .doesNotContain("personal-memory-v1")
                .doesNotContain("SLOW")
                .doesNotContain("BRIEF")
                .doesNotContain("VIDEO");
        assertThat(encoded.digest()).isEqualTo(digestService.sha256(encoded.cipher()));
        assertThat(encoded.digest()).isNotEqualTo(
                digestService.sha256(codec.canonical(preferences)));
        assertThat(codec.decode(encoded.cipher(), encoded.digest())).isEqualTo(preferences);
    }

    @Test
    void shouldRejectCipherOrDigestTamperingBeforeReturningPreferences() {
        PersonalMemoryCodec.EncodedPersonalMemory encoded = codec.encode(preferences());
        byte[] changedCipher = encoded.cipher();
        changedCipher[changedCipher.length - 1] ^= 1;
        byte[] changedDigest = encoded.digest();
        changedDigest[0] ^= 1;

        assertThatThrownBy(() -> codec.decode(changedCipher, encoded.digest()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("长期个人偏好完整性校验失败");
        assertThatThrownBy(() -> codec.decode(encoded.cipher(), changedDigest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("长期个人偏好完整性校验失败");
        assertThatThrownBy(() -> codec.decode(null, encoded.digest()))
                .isInstanceOf(IllegalStateException.class);
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
}
