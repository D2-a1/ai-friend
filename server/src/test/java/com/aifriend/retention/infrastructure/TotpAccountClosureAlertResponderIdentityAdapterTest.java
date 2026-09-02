package com.aifriend.retention.infrastructure;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifriend.retention.application.AccountClosureAlertAudience;
import com.aifriend.retention.application.AccountClosureAlertResponderIdentity;
import com.aifriend.retention.application.OperationsTotpProperties;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;
import com.aifriend.shared.security.SensitiveDataProtector;

class TotpAccountClosureAlertResponderIdentityAdapterTest {

    private static final Instant NOW = Instant.ofEpochSecond(59L);
    private static final UUID DELIVERY_ID = UUID.fromString(
            "12345678-1234-1234-1234-123456789abc");
    private static final String SUBJECT_ID = "on-call-operator-01";
    private static final String SECRET =
            "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Test
    void shouldReturnFixedOnCallIdentityAndClearCredential() {
        RedisOperationsTotpGuard guard = mock(RedisOperationsTotpGuard.class);
        SensitiveDataProtector protector = mock(SensitiveDataProtector.class);
        when(protector.subjectHmac(
                "operations-totp-subject-v1:" + SUBJECT_ID))
                .thenReturn(new byte[32]);
        TotpAccountClosureAlertResponderIdentityAdapter adapter =
                adapter(guard, protector);
        byte[] credential = "287082".getBytes(US_ASCII);

        AccountClosureAlertResponderIdentity identity =
                adapter.verify(DELIVERY_ID, credential);

        assertThat(identity.audience()).isEqualTo(AccountClosureAlertAudience.ON_CALL);
        assertThat(identity.verifiedAt()).isEqualTo(NOW);
        assertThat(identity.expiresAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(identity.authenticationContextHash()).hasSize(32);
        assertThat(credential).containsOnly((byte) 0);
        verify(guard).acquireAttempt(DELIVERY_ID);
        verify(guard).consumeCounter(SUBJECT_ID, 1L);
    }

    @Test
    void shouldRejectInvalidCodeAndStillClearCredential() {
        RedisOperationsTotpGuard guard = mock(RedisOperationsTotpGuard.class);
        SensitiveDataProtector protector = mock(SensitiveDataProtector.class);
        TotpAccountClosureAlertResponderIdentityAdapter adapter =
                adapter(guard, protector);
        byte[] credential = "000000".getBytes(US_ASCII);

        assertThatThrownBy(() -> adapter.verify(DELIVERY_ID, credential))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.OPERATIONS_CREDENTIAL_INVALID));
        assertThat(credential).containsOnly((byte) 0);
        verify(guard).acquireAttempt(DELIVERY_ID);
    }

    private TotpAccountClosureAlertResponderIdentityAdapter adapter(
            RedisOperationsTotpGuard guard,
            SensitiveDataProtector protector) {
        OperationsTotpProperties properties = new OperationsTotpProperties(
                true,
                SUBJECT_ID,
                SECRET,
                URI.create(
                        "https://api.ai-friend.asia/api/v1/operations/"
                                + "account-closure-alert-deliveries/"));
        return new TotpAccountClosureAlertResponderIdentityAdapter(
                properties,
                guard,
                protector,
                new DigestService(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
