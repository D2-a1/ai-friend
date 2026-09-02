package com.aifriend.task.application;

import java.util.List;

/**
 * 不持久化的本地 ASR N-best 候选。
 *
 * @param transcript 临时转写正文，不得记录日志
 * @param words 词级时间戳
 * @param confidence 根据词置信度计算的保守分数，范围 0—1
 * @param source 主方言或普通话辅助来源
 * @param modelVersion 实际识别模型版本
 * @author Codex
 * @since 1.0.0
 */
public record TaskTranscriptCandidate(
        String transcript,
        List<TaskRecognizedWord> words,
        double confidence,
        TaskAsrSource source,
        String modelVersion) {

    /** 固化词级时间戳，避免识别 SDK 返回集合在调用后变化。 */
    public TaskTranscriptCandidate {
        words = List.copyOf(words);
    }
}
