package com.aifriend.invitation.infrastructure;

import java.util.Base64;
import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import com.aifriend.invitation.application.InvitationOAuthAttemptLimiterPort;
import com.aifriend.invitation.application.InvitationOAuthRateLimitProperties;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;

/**
 * 基于 Redis 固定时间窗的邀请 OAuth 回调限流适配器。
 *
 * <p>Lua 脚本在同一次原子操作内递增计数并为首次计数设置过期时间。Redis
 * 异常、空响应和超限均保守失败，不回退到进程内计数，也不记录限流键。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class RedisInvitationOAuthAttemptLimiter implements InvitationOAuthAttemptLimiterPort {

    /** Redis 键命名空间，不包含任何会话明文。 */
    private static final String KEY_PREFIX = "ai-friend:invitation:oauth-attempt:v1:";
    /** 原子递增并仅在首次写入时设置毫秒过期时间的 Lua 脚本。 */
    private static final DefaultRedisScript<Long> INCREMENT_SCRIPT = new DefaultRedisScript<>(
            "local count = redis.call('INCR', KEYS[1]); "
                    + "if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]); end; "
                    + "return count;",
            Long.class);

    /** Redis 字符串操作模板。 */
    private final StringRedisTemplate redisTemplate;
    /** 固定回调限流配置。 */
    private final InvitationOAuthRateLimitProperties properties;

    /**
     * 创建 Redis 邀请 OAuth 限流适配器。
     *
     * @param redisTemplate Redis 字符串操作模板
     * @param properties 固定限流配置
     */
    public RedisInvitationOAuthAttemptLimiter(
            StringRedisTemplate redisTemplate,
            InvitationOAuthRateLimitProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    /**
     * 原子消耗当前邀请会话的一次 OAuth 回调额度。
     *
     * @param sessionTokenDigest 邀请会话 token 的 SHA-256 摘要
     * @throws BusinessException 超限或 Redis 不可用时抛出 RATE_LIMITED
     */
    @Override
    public void acquire(byte[] sessionTokenDigest) {
        String key = KEY_PREFIX + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(sessionTokenDigest);
        Long attempts;
        try {
            attempts = redisTemplate.execute(
                    INCREMENT_SCRIPT,
                    List.of(key),
                    Long.toString(properties.window().toMillis()));
        } catch (RuntimeException exception) {
            throw rateLimited();
        }
        if (attempts == null || attempts > properties.maximumAttempts()) {
            throw rateLimited();
        }
    }

    private BusinessException rateLimited() {
        return new BusinessException(ErrorCode.RATE_LIMITED);
    }
}
