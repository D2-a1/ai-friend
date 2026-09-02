package com.aifriend.template.application;

import java.time.Instant;
import java.util.UUID;

import com.aifriend.task.domain.TaskIntent;

/**
 * 待写入 owner 命名空间的加密日常指令模板。
 *
 * @param id 服务端生成的模板 UUID
 * @param intent 有限通信意图
 * @param dialectCode 方言代码
 * @param dialectPackageVersion 方言包版本
 * @param templateModelVersion 模板模型版本
 * @param thresholdVersion 阈值版本
 * @param templateCipher AES-GCM 模板密文
 * @param templateDigest 模板明文 SHA-256
 * @param confirmedAt 来源任务最终确认时间
 * @author Codex
 * @since 1.0.0
 */
public record RoutineCommandTemplateWrite(
        UUID id,
        TaskIntent intent,
        String dialectCode,
        String dialectPackageVersion,
        String templateModelVersion,
        String thresholdVersion,
        byte[] templateCipher,
        byte[] templateDigest,
        Instant confirmedAt) {

    /** 固化密文和摘要副本。 */
    public RoutineCommandTemplateWrite {
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
