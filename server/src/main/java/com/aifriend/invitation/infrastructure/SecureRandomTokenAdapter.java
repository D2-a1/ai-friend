package com.aifriend.invitation.infrastructure;

import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.stereotype.Component;

import com.aifriend.invitation.application.SecretTokenPort;

/**
 * 基于操作系统安全随机源的 256 位 URL 安全令牌生成器。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class SecureRandomTokenAdapter implements SecretTokenPort {

    private static final int TOKEN_BYTES = 32;

    /** 密码学安全随机源。 */
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 创建安全随机令牌生成器。
     */
    public SecureRandomTokenAdapter() {
    }

    /**
     * 生成 256 位无填充 Base64URL 令牌。
     *
     * @return 长度为 43 的随机令牌
     */
    @Override
    public String issue() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
