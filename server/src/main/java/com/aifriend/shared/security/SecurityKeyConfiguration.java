package com.aifriend.shared.security;

import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.util.StringUtils;

/**
 * 从受控配置构建安全密钥；生产环境缺失密钥时拒绝启动。
 *
 * @author Codex
 * @since 1.0.0
 */
@Configuration
public class SecurityKeyConfiguration {

    /** 安全密钥配置日志组件，不输出密钥内容。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(SecurityKeyConfiguration.class);
    /** JWT、数据加密和主体摘要密钥的最小字节数。 */
    private static final int KEY_BYTES = 32;

    /**
     * 创建安全密钥配置。
     */
    public SecurityKeyConfiguration() {
    }

    /**
     * 构建运行时密钥材料。
     *
     * @param properties 身份安全配置
     * @param environment Spring 环境
     * @return 运行时密钥集合
     * @throws IllegalStateException 当生产密钥缺失、Base64 非法或密钥不足 256 位时抛出
     */
    @Bean
    public SecurityKeyMaterial securityKeyMaterial(
            IdentitySecurityProperties properties,
            Environment environment) {
        boolean isProduction = environment.acceptsProfiles(Profiles.of("prod"));
        SecretKey jwtKey = loadKey(
                properties.jwtSigningKeyBase64(), "HmacSHA256", "JWT", isProduction);
        SecretKey dataKey = loadKey(
                properties.dataEncryptionKeyBase64(), "AES", "DATA_ENCRYPTION", isProduction);
        SecretKey subjectKey = loadKey(
                properties.subjectHmacKeyBase64(), "HmacSHA256", "SUBJECT_HMAC", isProduction);
        return new SecurityKeyMaterial(jwtKey, dataKey, subjectKey);
    }

    /**
     * 加载并校验指定用途的密钥；非生产环境缺省时仅在当前进程生成临时随机密钥。
     *
     * @param encodedKey Base64 编码密钥
     * @param algorithm 密钥算法
     * @param purpose 不含密钥内容的用途标识
     * @param isProduction 是否生产环境
     * @return 已校验密钥
     * @throws IllegalStateException 当生产密钥缺失、Base64 非法或密钥不足 256 位时抛出
     */
    private SecretKey loadKey(String encodedKey, String algorithm, String purpose, boolean isProduction) {
        if (!StringUtils.hasText(encodedKey)) {
            if (isProduction) {
                throw new IllegalStateException("生产环境缺少必要安全密钥: " + purpose);
            }
            byte[] randomKey = new byte[KEY_BYTES];
            new SecureRandom().nextBytes(randomKey);
            LOGGER.warn("非生产环境未配置 {} 密钥，本进程使用临时随机密钥", purpose);
            return new SecretKeySpec(randomKey, algorithm);
        }
        byte[] decodedKey;
        try {
            decodedKey = Base64.getDecoder().decode(encodedKey);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("安全密钥不是有效 Base64: " + purpose, exception);
        }
        if (decodedKey.length < KEY_BYTES) {
            throw new IllegalStateException("安全密钥长度不足 256 位: " + purpose);
        }
        return new SecretKeySpec(decodedKey, algorithm);
    }
}
