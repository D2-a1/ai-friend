package com.aifriend.voicecollection.application;

import java.time.Instant;

import com.aifriend.voicecollection.domain.VoiceCollectionStatus;

/**
 * 语音采集样本删除受理结果。
 *
 * @param id vs_ 前缀样本编号
 * @param status 删除状态
 * @param requestedAt 删除受理时间
 * @author Codex
 * @since 1.0.0
 */
public record VoiceCollectionDeletionView(
        String id,
        VoiceCollectionStatus status,
        Instant requestedAt) {
}
