package com.aifriend.task.application;

import java.util.List;

/**
 * 事务外语音识别证据与可选的有效原声对齐结果。
 *
 * @param transcript 临时转写，不得记录日志
 * @param nBest 主方言和普通话辅助的临时候选证据
 * @param effectiveAudioRanges 需要发送的有效原声区间
 * @param confidence 识别置信度，0—1
 * @param primaryAsrModelVersion 主识别模型版本
 * @param mandarinAssistModelVersion 实际普通话辅助模型版本
 * @param fusionRuleVersion 实际主辅融合规则版本
 * @param alignmentVersion 原声对齐版本
 * @author Codex
 * @since 1.0.0
 */
public record TaskSpeechRecognition(
        String transcript,
        List<TaskTranscriptCandidate> nBest,
        List<TaskAudioRange> effectiveAudioRanges,
        double confidence,
        String primaryAsrModelVersion,
        String mandarinAssistModelVersion,
        String fusionRuleVersion,
        String alignmentVersion) {

    /** 固化区间列表。 */
    public TaskSpeechRecognition {
        nBest = List.copyOf(nBest);
        effectiveAudioRanges = List.copyOf(effectiveAudioRanges);
    }
}
