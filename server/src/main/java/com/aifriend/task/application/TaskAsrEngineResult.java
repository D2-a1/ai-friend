package com.aifriend.task.application;

import java.util.List;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;

/**
 * 单个本地识别引擎的有界 N-best 结果。
 *
 * @param candidates 按置信度稳定排序的候选，最多三个
 * @param modelVersion 实际模型版本
 * @author Codex
 * @since 1.0.0
 */
public record TaskAsrEngineResult(
        List<TaskTranscriptCandidate> candidates,
        String modelVersion) {

    /** 固化候选集合。 */
    public TaskAsrEngineResult {
        candidates = List.copyOf(candidates);
    }

    /**
     * 获取当前引擎的首选候选。
     *
     * @return 非空首选候选
     * @throws BusinessException 当识别器没有产生可靠候选时抛出
     */
    public TaskTranscriptCandidate topCandidate() {
        if (candidates.isEmpty()) {
            throw new BusinessException(ErrorCode.AUDIO_SEGMENT_UNCERTAIN);
        }
        return candidates.get(0);
    }
}
