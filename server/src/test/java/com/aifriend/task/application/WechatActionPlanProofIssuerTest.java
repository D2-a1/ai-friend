package com.aifriend.task.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;

class WechatActionPlanProofIssuerTest {

    private static final Instant ISSUED_AT = Instant.parse("2026-08-19T08:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-08-19T08:00:30Z");

    @Test
    void shouldDeclareProductionConstructorForSpringInjection() throws NoSuchMethodException {
        assertTrue(WechatActionPlanProofIssuer.class
                .getConstructor(WechatActionPlanProofSignerPort.class, DigestService.class)
                .isAnnotationPresent(Autowired.class));
    }

    @Test
    void issueMustUseFreshSaltAndReturnOnlySaltedDigest() {
        byte[] signature = new byte[64];
        Arrays.fill(signature, (byte) 7);
        WechatActionPlanProofSignerPort signer = signer(true, signature);
        SecureRandom deterministicRandom = new SecureRandom() {
            @Override
            public void nextBytes(byte[] bytes) {
                for (int index = 0; index < bytes.length; index++) {
                    bytes[index] = (byte) index;
                }
            }
        };
        WechatActionPlanProofIssuer issuer = new WechatActionPlanProofIssuer(
                signer, new DigestService(), deterministicRandom);

        WechatTargetLocatorProofView proof = issuer.issue(claims(), "private-locator");

        assertEquals("000102030405060708090a0b0c0d0e0f", proof.salt());
        assertEquals("d0a3be9e54693a671d3123d15a5868a7ca9a883afb6b5733cc3be45c12019015",
                proof.targetLocatorSha256());
        assertFalse(proof.targetLocatorSha256().contains("private-locator"));
        assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(signature),
                proof.signature());
        assertFalse(proof.toString().contains(proof.salt()));
        assertFalse(proof.toString().contains(proof.signature()));
    }

    @Test
    void unavailableSignerMustFailClosedBeforeReturningProof() {
        WechatActionPlanProofIssuer issuer = new WechatActionPlanProofIssuer(
                signer(false, new byte[64]), new DigestService());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> issuer.issue(claims(), "private-locator"));

        assertEquals(ErrorCode.ACTION_UNSUPPORTED, exception.errorCode());
    }

    @Test
    void canonicalBytesMustMatchAndroidGoldenVector() {
        WechatActionPlanProofClaims goldenClaims = new WechatActionPlanProofClaims(
                "wp_test", "SEND_AUDIO_AND_TEXT", "ct_test", "ao_test",
                "a".repeat(64), "rule-v1", 7, "8.0.56", "locator-v1",
                ISSUED_AT, EXPIRES_AT);
        byte[] canonical = WechatActionPlanProofCanonicalizer.canonicalBytes(
                goldenClaims, WechatActionPlanProofIssuer.PROOF_VERSION,
                "test-rfc8032", "000102030405060708090a0b0c0d0e0f",
                "d0a3be9e54693a671d3123d15a5868a7ca9a883afb6b5733cc3be45c12019015");

        assertEquals("021f15b7bcbfb5df08695454b4d680faa27136f31a1d9383f62512a1b643bb30",
                HexFormat.of().formatHex(new DigestService().sha256(canonical)));
    }

    private WechatActionPlanProofClaims claims() {
        return new WechatActionPlanProofClaims(
                "wp_test", "SEND_AUDIO_AND_TEXT", "ct_test", "ao_test",
                "a".repeat(64), "rule-v1", 7, "8.0.56", "locator-v1",
                ISSUED_AT, EXPIRES_AT);
    }

    private WechatActionPlanProofSignerPort signer(boolean available, byte[] signature) {
        return new WechatActionPlanProofSignerPort() {
            @Override
            public boolean available() {
                return available;
            }

            @Override
            public String keyId() {
                return "test-key";
            }

            @Override
            public byte[] sign(byte[] canonicalBytes) {
                return signature.clone();
            }
        };
    }
}
