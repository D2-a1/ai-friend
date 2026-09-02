package com.aifriend.task.application;

import java.util.List;

/**
 * 主方言与普通话辅助的保守融合结果。
 *
 * @param selectedCandidate 最终仍由主方言提供的首选候选
 * @param evidenceCandidates 主辅全部有界候选，仅作临时证据
 * @param confidence 保守融合置信度
 * @author Codex
 * @since 1.0.0
 */
public record TaskAsrFusionResult(
        TaskTranscriptCandidate selectedCandidate,
        List<TaskTranscriptCandidate> evidenceCandidates,
        double confidence) {

    /** 固化临时候选集合。 */
    public TaskAsrFusionResult {
        evidenceCandidates = List.copyOf(evidenceCandidates);
    }
}
