package com.aifriend.voice.infrastructure;

import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.stereotype.Component;

import com.aifriend.voice.application.AudioUploadTokenPort;

/**
 * 使用系统安全随机源签发一次性音频上传秘密。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class SecureAudioUploadTokenAdapter implements AudioUploadTokenPort {

    private static final int TOKEN_BYTES = 32;

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 创建安全随机音频上传秘密适配器。
     */
    public SecureAudioUploadTokenAdapter() {
    }

    /** {@inheritDoc} */
    @Override
    public String issue() {
        byte[] token = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(token);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }
}
