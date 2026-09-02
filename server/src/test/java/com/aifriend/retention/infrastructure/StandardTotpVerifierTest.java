package com.aifriend.retention.infrastructure;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class StandardTotpVerifierTest {

    private static final String RFC_SECRET =
            "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Test
    void shouldMatchRfcVectorUsingSixDigitCode() {
        StandardTotpVerifier verifier = new StandardTotpVerifier(RFC_SECRET);

        long counter = verifier.findMatchingCounter(
                "287082".getBytes(US_ASCII),
                Instant.ofEpochSecond(59L));

        assertThat(counter).isEqualTo(1L);
    }

    @Test
    void shouldRejectMalformedOrUnmatchedCredential() {
        StandardTotpVerifier verifier = new StandardTotpVerifier(RFC_SECRET);

        assertThat(verifier.findMatchingCounter(
                "12345".getBytes(US_ASCII),
                Instant.ofEpochSecond(59L))).isNegative();
        assertThat(verifier.findMatchingCounter(
                "000000".getBytes(US_ASCII),
                Instant.ofEpochSecond(59L))).isNegative();
    }
}
