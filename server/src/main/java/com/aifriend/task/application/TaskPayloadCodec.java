package com.aifriend.task.application;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

import com.aifriend.shared.security.SensitiveDataProtector;

/**
 * 任务敏感载荷 JSON 序列化和 AES-GCM 保护器。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class TaskPayloadCodec {

    private final ObjectMapper objectMapper;
    private final SensitiveDataProtector protector;

    /**
     * 创建任务敏感载荷保护器。
     *
     * @param objectMapper Spring JSON 映射器
     * @param protector AES-GCM 数据保护器
     */
    public TaskPayloadCodec(
            ObjectMapper objectMapper,
            SensitiveDataProtector protector) {
        this.objectMapper = objectMapper;
        this.protector = protector;
    }

    /**
     * 序列化并加密任务载荷。
     *
     * @param payload 内存中的敏感任务载荷
     * @return AES-GCM 密文
     * @throws IllegalStateException JSON 序列化失败时抛出
     */
    public byte[] encode(TaskPayload payload) {
        try {
            return protector.encryptBytes(objectMapper.writeValueAsBytes(payload));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("任务敏感载荷序列化失败", exception);
        }
    }

    /**
     * 解密并反序列化任务载荷。
     *
     * @param cipher AES-GCM 密文
     * @return 仅在当前请求内存存在的任务载荷
     * @throws IllegalStateException 密文或 JSON 损坏时抛出
     */
    public TaskPayload decode(byte[] cipher) {
        try {
            return objectMapper.readValue(
                    protector.decryptBytes(cipher), TaskPayload.class);
        } catch (IOException exception) {
            throw new IllegalStateException("任务敏感载荷反序列化失败", exception);
        }
    }
}
