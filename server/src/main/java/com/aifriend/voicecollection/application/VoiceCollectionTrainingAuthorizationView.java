package com.aifriend.voicecollection.application;

import java.time.Instant;

/**
 * 单条测试语音样本训练授权结果投影。
 *
 * @param sampleId vs_ 前缀样本编号
 * @param trainingEligible 当前是否具备训练资格
 * @param version 样本乐观锁版本
 * @param decidedAt 服务端记录决定的时间
 * @author codex
 * @since 1.0.0
 */
public record VoiceCollectionTrainingAuthorizationView(
        String sampleId,
        boolean trainingEligible,
        long version,
        Instant decidedAt) {
}
