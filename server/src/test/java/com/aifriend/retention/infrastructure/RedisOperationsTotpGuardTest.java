package com.aifriend.retention.infrastructure;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.error.UpstreamFailureException;
import com.aifriend.shared.security.DigestService;

class RedisOperationsTotpGuardTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private RedisOperationsTotpGuard guard;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        guard = new RedisOperationsTotpGuard(redisTemplate, new DigestService());
    }

    @Test
    void shouldAllowFirstFiveAttemptsAndRejectTheSixth() {
        when(redisTemplate.execute(
                any(DefaultRedisScript.class),
                anyList(),
                any())).thenReturn(1L, 2L, 3L, 4L, 5L, 6L);
        UUID deliveryId = UUID.fromString(
                "12345678-1234-1234-1234-123456789abc");

        for (int attempt = 0; attempt < 5; attempt++) {
            guard.acquireAttempt(deliveryId);
        }

        assertThatThrownBy(() -> guard.acquireAttempt(deliveryId))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.RATE_LIMITED));
    }

    @Test
    void shouldRejectAlreadyConsumedTimeCounter() {
        when(valueOperations.setIfAbsent(any(), any(), any()))
                .thenReturn(false);

        assertThatThrownBy(() -> guard.consumeCounter(
                "on-call-operator-01",
                123L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.OPERATIONS_CREDENTIAL_INVALID));
    }

    @Test
    void shouldFailClosedWhenRedisIsUnavailable() {
        when(redisTemplate.execute(
                any(DefaultRedisScript.class),
                anyList(),
                any())).thenThrow(new IllegalStateException("redis unavailable"));

        assertThatThrownBy(() -> guard.acquireAttempt(UUID.randomUUID()))
                .isInstanceOf(UpstreamFailureException.class)
                .hasMessageNotContaining("redis unavailable");
    }
}
