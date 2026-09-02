package com.aifriend.template.application;

import java.time.Instant;
import java.util.UUID;

import com.aifriend.task.domain.TaskIntent;

/**
 * owner 范围日常指令模板持久化快照。
 *
 * @param id 模板 UUID
 * @param intent 有限通信意图
 * @param dialectCode 方言代码
 * @param dialectPackageVersion 方言包版本
 * @param templateModelVersion 模板模型版本
 * @param thresholdVersion 阈值版本
 * @param templateCipher AES-GCM 模板密文
 * @param templateDigest 模板明文 SHA-256
 * @param usageCount 合并确认次数
 * @param lastConfirmedAt 最近确认时间
 * @param version 并发版本
 * @param updatedAt 最近更新时间
 * @author Codex
 * @since 1.0.0
 */
public record RoutineCommandTemplateRecord(
        UUID id,
        TaskIntent intent,
        String dialectCode,
        String dialectPackageVersion,
        String templateModelVersion,
        String thresholdVersion,
        byte[] templateCipher,
        byte[] templateDigest,
        int usageCount,
        Instant lastConfirmedAt,
        long version,
        Instant updatedAt) {

    /** 创建带防御性密文字段副本的快照。 */
    public RoutineCommandTemplateRecord {
        templateCipher = templateCipher.clone();
        templateDigest = templateDigest.clone();
    }

    /**
     * 获取模板密文。
     *
     * @return 模板密文防御性副本
     */
    @Override
    public byte[] templateCipher() {
        return templateCipher.clone();
    }

    /**
     * 获取模板摘要。
     *
     * @return 模板摘要防御性副本
     */
    @Override
    public byte[] templateDigest() {
        return templateDigest.clone();
    }
}
