package com.aifriend.shared.security;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;

import org.springframework.stereotype.Component;

/**
 * 微信主体等敏感值的 AES-GCM 加密与 HMAC 查询键生成器。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class SensitiveDataProtector {

    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;

    private final SecurityKeyMaterial keyMaterial;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 创建敏感数据保护器。
     *
     * @param keyMaterial 运行时密钥
     */
    public SensitiveDataProtector(SecurityKeyMaterial keyMaterial) {
        this.keyMaterial = keyMaterial;
    }

    /**
     * 使用随机 IV 加密敏感字符串。
     *
     * @param plainText 明文，仅在当前调用内存中短暂存在
     * @return IV 与密文拼接结果
     * @throws IllegalStateException 当 AES-GCM 初始化或加密失败时抛出
     */
    public byte[] encrypt(String plainText) {
        return encryptBytes(plainText.getBytes(UTF_8));
    }

    /**
     * 使用随机 IV 加密不得记录日志的二进制敏感数据。
     *
     * @param plainBytes 敏感二进制明文，仅在当前调用内存中存在
     * @return IV 与密文拼接结果
     * @throws IllegalStateException 当 AES-GCM 初始化或加密失败时抛出
     */
    public byte[] encryptBytes(byte[] plainBytes) {
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keyMaterial.dataEncryptionKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plainBytes);
            return ByteBuffer.allocate(iv.length + cipherText.length).put(iv).put(cipherText).array();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("敏感数据加密失败", exception);
        }
    }

    /**
     * 解密由本服务生成的 AES-GCM 敏感字符串密文。
     *
     * @param encryptedValue IV 与密文拼接结果，不得记录日志
     * @return 解密后的短期明文
     * @throws IllegalStateException 当密文损坏、密钥不匹配或解密失败时抛出
     */
    public String decrypt(byte[] encryptedValue) {
        return new String(decryptBytes(encryptedValue), UTF_8);
    }

    /**
     * 解密由本服务生成的 AES-GCM 二进制密文。
     *
     * @param encryptedValue IV 与密文拼接结果，不得记录日志
     * @return 二进制敏感明文副本
     * @throws IllegalStateException 当密文损坏、密钥不匹配或解密失败时抛出
     */
    public byte[] decryptBytes(byte[] encryptedValue) {
        if (encryptedValue == null || encryptedValue.length <= GCM_IV_BYTES) {
            throw new IllegalStateException("敏感数据密文格式错误");
        }
        try {
            byte[] iv = Arrays.copyOfRange(encryptedValue, 0, GCM_IV_BYTES);
            byte[] cipherText = Arrays.copyOfRange(
                    encryptedValue, GCM_IV_BYTES, encryptedValue.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keyMaterial.dataEncryptionKey(),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            return cipher.doFinal(cipherText);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("敏感数据解密失败", exception);
        }
    }

    /**
     * 为敏感主体生成稳定 HMAC 查询键。
     *
     * @param value 敏感主体明文
     * @return 32 字节 HMAC-SHA-256
     * @throws IllegalStateException 当 HMAC 初始化或计算失败时抛出
     */
    public byte[] subjectHmac(String value) {
        return hmac(keyMaterial.subjectHmacKey().getAlgorithm(), keyMaterial.subjectHmacKey(), value.getBytes(UTF_8));
    }

    /**
     * 按邀请 UUID 派生固定 256 位秘密，支持创建接口幂等重放且不持久化 proof。
     *
     * <p>使用独立域前缀与微信主体摘要隔离；返回值只能进入一次性分享 URL fragment。
     *
     * @param invitationId 邀请 UUID
     * @return 无填充 Base64URL proof
     * @throws IllegalStateException 当 HMAC 计算失败时抛出
     */
    public String invitationProof(UUID invitationId) {
        byte[] proof = hmac(
                keyMaterial.subjectHmacKey().getAlgorithm(),
                keyMaterial.subjectHmacKey(),
                ("invitation-proof-v1:" + invitationId).getBytes(UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(proof);
    }

    /**
     * 使用指定算法和密钥计算消息认证码。
     *
     * @param algorithm HMAC 算法
     * @param key HMAC 密钥
     * @param value 待摘要字节
     * @return 消息认证码
     * @throws IllegalStateException 当算法或密钥不可用时抛出
     */
    private byte[] hmac(String algorithm, javax.crypto.SecretKey key, byte[] value) {
        try {
            Mac mac = Mac.getInstance(algorithm);
            mac.init(key);
            return mac.doFinal(value);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("敏感主体摘要生成失败", exception);
        }
    }
}
