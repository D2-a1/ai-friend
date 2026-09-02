package com.aifriend.retention.infrastructure;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.error.UpstreamFailureException;
import com.aifriend.shared.security.DigestService;

/**
 * Redis 运维 TOTP 尝试限制和单次消费门禁。
 *
 * <p>尝试计数与过期时间由 Lua 原子写入；通过验证的主体时间步再以 SET NX
 * 跨实例消费。Redis 不可用时失败关闭，不回退进程内计数。</p>
 *
 * @author Codex
 * @since 1.0.0
 */
final class RedisOperationsTotpGuard {

    /** Redis 键前缀，不包含投递 UUID 或运维主体明文。 */
    private static final String KEY_PREFIX = "ai-friend:operations:totp:v1:";
    /** 五分钟内最多允许五次验证码尝试。 */
    private static final int MAXIMUM_ATTEMPTS = 5;
    /** 验证码尝试限制窗口。 */
    private static final Duration ATTEMPT_WINDOW = Duration.ofMinutes(5);
    /** 已验证时间步的单次消费保留窗口。 */
    private static final Duration USED_COUNTER_WINDOW = Duration.ofMinutes(2);
    /** 原子递增计数并在首次写入时设置有效期。 */
    private static final DefaultRedisScript<Long> INCREMENT_SCRIPT =
            new DefaultRedisScript<>(
                    "local count = redis.call('INCR', KEYS[1]); "
                            + "if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]); end; "
                            + "return count;",
                    Long.class);

    /** Redis 字符串操作模板。 */
    private final StringRedisTemplate redisTemplate;
    /** SHA-256 摘要服务。 */
    private final DigestService digestService;

    RedisOperationsTotpGuard(
            StringRedisTemplate redisTemplate,
            DigestService digestService) {
        this.redisTemplate = redisTemplate;
        this.digestService = digestService;
    }

    void acquireAttempt(UUID deliveryId) {
        String key = KEY_PREFIX + "attempt:" + digestKey(
                "operations-totp-attempt-v1\n" + deliveryId);
        Long attempts;
        try {
            attempts = redisTemplate.execute(
                    INCREMENT_SCRIPT,
                    List.of(key),
                    Long.toString(ATTEMPT_WINDOW.toMillis()));
        } catch (RuntimeException exception) {
            throw new UpstreamFailureException("运维身份验证暂不可用");
        }
        if (attempts == null || attempts > MAXIMUM_ATTEMPTS) {
            throw new BusinessException(ErrorCode.RATE_LIMITED);
        }
    }

    void consumeCounter(String subjectId, long matchingCounter) {
        String key = KEY_PREFIX + "used:" + digestKey(
                "operations-totp-used-v1\n" + subjectId + "\n" + matchingCounter);
        Boolean consumed;
        try {
            consumed = redisTemplate.opsForValue().setIfAbsent(
                    key,
                    "1",
                    USED_COUNTER_WINDOW);
        } catch (RuntimeException exception) {
            throw new UpstreamFailureException("运维身份验证暂不可用");
        }
        if (!Boolean.TRUE.equals(consumed)) {
            throw new BusinessException(ErrorCode.OPERATIONS_CREDENTIAL_INVALID);
        }
    }

    private String digestKey(String canonicalValue) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(digestService.sha256(canonicalValue));
    }
}
