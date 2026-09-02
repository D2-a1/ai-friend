package com.aifriend.voicecollection.api;

import java.time.Instant;

import com.aifriend.voicecollection.domain.VoiceCollectionStatus;

/**
 * 采集样本删除受理响应。
 *
 * @param sampleId vs_ 前缀样本编号
 * @param status 删除状态
 * @param requestedAt 删除受理时间
 * @author Codex
 * @since 1.0.0
 */
public record VoiceCollectionDeletionResp(
        String sampleId,
        VoiceCollectionStatus status,
        Instant requestedAt) {
}
