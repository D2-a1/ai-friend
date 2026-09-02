package com.aifriend.invitation.infrastructure;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import com.aifriend.invitation.application.InvitationOAuthRateLimitProperties;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;

class RedisInvitationOAuthAttemptLimiterTest {

    @Test
    void shouldAllowAttemptAtFrozenBoundary() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(
                any(RedisScript.class), anyList(), eq("300000"))).thenReturn(5L);
        RedisInvitationOAuthAttemptLimiter limiter = limiter(redisTemplate);

        assertDoesNotThrow(() -> limiter.acquire(new DigestService().sha256("session-token")));
    }

    @Test
    void shouldRejectAttemptAboveFrozenBoundary() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(
                any(RedisScript.class), anyList(), eq("300000"))).thenReturn(6L);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> limiter(redisTemplate).acquire(
                        new DigestService().sha256("session-token")));

        assertEquals(ErrorCode.RATE_LIMITED, exception.errorCode());
    }

    @Test
    void shouldFailClosedWhenRedisIsUnavailableOrReturnsNoCounter() {
        StringRedisTemplate unavailableRedis = mock(StringRedisTemplate.class);
        when(unavailableRedis.execute(
                any(RedisScript.class), anyList(), eq("300000")))
                .thenThrow(new IllegalStateException("redis unavailable"));
        StringRedisTemplate emptyRedis = mock(StringRedisTemplate.class);
        when(emptyRedis.execute(
                any(RedisScript.class), anyList(), eq("300000"))).thenReturn(null);

        assertEquals(ErrorCode.RATE_LIMITED,
                assertThrows(BusinessException.class,
                        () -> limiter(unavailableRedis).acquire(new byte[32])).errorCode());
        assertEquals(ErrorCode.RATE_LIMITED,
                assertThrows(BusinessException.class,
                        () -> limiter(emptyRedis).acquire(new byte[32])).errorCode());
    }

    private RedisInvitationOAuthAttemptLimiter limiter(StringRedisTemplate redisTemplate) {
        return new RedisInvitationOAuthAttemptLimiter(
                redisTemplate,
                new InvitationOAuthRateLimitProperties(5, Duration.ofMinutes(5)));
    }
}
