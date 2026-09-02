package com.aifriend.shared.security;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.springframework.stereotype.Component;

/**
 * 非密码查询用途的 SHA-256 摘要服务。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class DigestService {

    /**
     * 创建 SHA-256 摘要服务。
     */
    public DigestService() {
    }

    /**
     * 计算字符串 SHA-256 摘要。
     *
     * @param value 输入字符串
     * @return 32 字节摘要
     * @throws IllegalStateException 当运行环境不支持 SHA-256 时抛出
     */
    public byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境缺少 SHA-256", exception);
        }
    }

    /**
     * 计算字节数组 SHA-256 摘要。
     *
     * @param value 输入字节，不得记录原始内容
     * @return 32 字节摘要
     * @throws IllegalStateException 当运行环境不支持 SHA-256 时抛出
     */
    public byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境缺少 SHA-256", exception);
        }
    }

    /**
     * 使用常量时间算法比较两个摘要，避免普通数组比较泄露首个差异位置。
     *
     * @param expected 预期摘要
     * @param actual 实际摘要
     * @return 两个摘要内容完全一致时返回 true
     */
    public boolean constantTimeEquals(byte[] expected, byte[] actual) {
        return MessageDigest.isEqual(expected, actual);
    }
}
