package com.aifriend.voicecollection.application;

import java.time.Instant;
import java.util.UUID;

import com.aifriend.consent.domain.ConsentDecision;

/**
 * 样本级训练授权最小幂等事实。
 *
 * @param id 授权事实 UUID
 * @param sampleId 样本 UUID
 * @param decision 授予或撤回决定
 * @param policyVersion 模型训练政策版本
 * @param idempotencyKeyHash 幂等键 SHA-256
 * @param requestHash 请求正文 SHA-256
 * @param trainingEligible 决定完成后的训练资格
 * @param sampleVersion 决定完成后的样本版本
 * @param decidedAt 服务端决定时间
 * @author codex
 * @since 1.0.0
 */
public record VoiceCollectionTrainingAuthorizationRecord(
        UUID id,
        UUID sampleId,
        ConsentDecision decision,
        String policyVersion,
        byte[] idempotencyKeyHash,
        byte[] requestHash,
        boolean trainingEligible,
        long sampleVersion,
        Instant decidedAt) {
}
