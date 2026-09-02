package com.aifriend.task.application;

import java.util.List;

import com.aifriend.task.domain.TaskIntent;

/**
 * 单次任务语音的保守结构化解释。
 *
 * @param intent 最终有限通信意图
 * @param outcome 是否允许继续匹配或必须重说的有限处理结果
 * @param recognition 只包含最终有效子句的识别证据和连续原声范围
 * @param messageText 与连续原声范围严格对应的消息正文；非消息或失败分支为空
 * @param corrections 已证明边界的一次纠正映射
 * @param routineCommandLearningEvidence 可空的明确动作声学范围
 * @author Codex
 * @since 1.0.0
 */
public record TaskUtteranceInterpretation(
        TaskIntent intent,
        TaskInterpretationOutcome outcome,
        TaskSpeechRecognition recognition,
        String messageText,
        List<TaskCorrectionView> corrections,
        RoutineCommandLearningEvidence routineCommandLearningEvidence) {

    /** 固化纠正映射，避免调用方修改。 */
    public TaskUtteranceInterpretation {
        corrections = List.copyOf(corrections);
    }
}
