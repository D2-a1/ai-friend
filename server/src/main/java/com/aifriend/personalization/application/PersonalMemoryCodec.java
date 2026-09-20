package com.aifriend.personalization.application;

import org.springframework.stereotype.Component;

import com.aifriend.personalization.domain.AmbiguousCallPreference;
import com.aifriend.personalization.domain.DialogueStylePreference;
import com.aifriend.personalization.domain.PersonalMemoryPreferences;
import com.aifriend.personalization.domain.SpeechRatePreference;
import com.aifriend.shared.security.DigestService;
import com.aifriend.shared.security.SensitiveDataProtector;

/**
 * 有限个人偏好的规范编码、加密和完整性校验器。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class PersonalMemoryCodec {

    private static final String FORMAT = "personal-memory-v1";
    private final SensitiveDataProtector protector;
    private final DigestService digestService;

    /**
     * 创建偏好保护器。
     *
     * @param protector AES-GCM 敏感字段保护器
     * @param digestService SHA-256 摘要器
     */
    public PersonalMemoryCodec(
            SensitiveDataProtector protector,
            DigestService digestService) {
        this.protector = protector;
        this.digestService = digestService;
    }

    /**
     * 规范编码并加密三个有限枚举。
     *
     * @param preferences 当前偏好
     * @return 密文与完整性摘要
     */
    public EncodedPersonalMemory encode(PersonalMemoryPreferences preferences) {
        String canonical = canonical(preferences);
        byte[] cipher = protector.encrypt(canonical);
        return new EncodedPersonalMemory(cipher, digestService.sha256(cipher));
    }

    /**
     * 解密并验证偏好，损坏或未知枚举立即失败关闭。
     *
     * @param cipher AES-GCM 密文
     * @param expectedDigest 密文摘要
     * @return 已验证偏好
     */
    public PersonalMemoryPreferences decode(byte[] cipher, byte[] expectedDigest) {
        if (cipher == null || expectedDigest == null
                || !digestService.constantTimeEquals(
                        digestService.sha256(cipher), expectedDigest)) {
            throw new IllegalStateException("长期个人偏好完整性校验失败");
        }
        String canonical = protector.decrypt(cipher);
        if (canonical.isEmpty()) {
            throw new IllegalStateException("长期个人偏好完整性校验失败");
        }
        String[] parts = canonical.split("\\|", -1);
        if (parts.length != 4 || !FORMAT.equals(parts[0])) {
            throw new IllegalStateException("长期个人偏好格式无效");
        }
        try {
            return new PersonalMemoryPreferences(
                    SpeechRatePreference.valueOf(parts[1]),
                    DialogueStylePreference.valueOf(parts[2]),
                    AmbiguousCallPreference.valueOf(parts[3]));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("长期个人偏好枚举无效", exception);
        }
    }

    /**
     * 生成稳定请求语义编码。
     *
     * @param preferences 当前偏好
     * @return 不含用户身份的规范文本
     */
    public String canonical(PersonalMemoryPreferences preferences) {
        return FORMAT + "|" + preferences.speechRate().name()
                + "|" + preferences.dialogueStyle().name()
                + "|" + preferences.ambiguousCall().name();
    }

    /**
     * 密文与摘要组合。
     *
     * @param cipher 偏好密文
     * @param digest 密文摘要，避免低熵偏好被离线穷举
     */
    public record EncodedPersonalMemory(byte[] cipher, byte[] digest) {
        /** 防御性复制。 */
        public EncodedPersonalMemory {
            cipher = cipher.clone();
            digest = digest.clone();
        }

        /**
         * 获取密文副本。
         *
         * @return 密文防御性副本
         */
        @Override
        public byte[] cipher() { return cipher.clone(); }

        /**
         * 获取密文摘要副本。
         *
         * @return 摘要防御性副本
         */
        @Override
        public byte[] digest() { return digest.clone(); }
    }
}
