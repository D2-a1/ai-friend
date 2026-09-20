package com.aifriend.task.application;

import java.util.List;

import com.aifriend.task.domain.TaskIntent;

/**
 * 语义层针对当前结构化任务草稿返回的有限补丁。
 *
 * <p>该结果只能修改草稿，不能确认任务或生成微信动作计划。
 *
 * @param intent 修订后的动作意图
 * @param outcome 修订后的安全处理结果
 * @param recognition 当前修订语音的识别证据
 * @param messageText 修订后的消息正文，可空
 * @param corrections 当前轮次的纠正证据
 * @param replaceContact 是否重新执行 owner 范围联系人匹配
 * @param replaceSourceAudio 是否用当前录音替换消息原声音频
 * @param routineCommandLearningEvidence 可空的日常动作声学学习证据
 */
public record TaskDraftRevision(
        TaskIntent intent,
        TaskInterpretationOutcome outcome,
        TaskSpeechRecognition recognition,
        String messageText,
        List<TaskCorrectionView> corrections,
        boolean replaceContact,
        boolean replaceSourceAudio,
        RoutineCommandLearningEvidence routineCommandLearningEvidence) {

    /**
     * 兼容不产生日常动作学习证据的有限修订。
     *
     * @param intent 修订后的动作意图
     * @param outcome 修订后的安全处理结果
     * @param recognition 当前修订语音的识别证据
     * @param messageText 修订后的消息正文，可空
     * @param corrections 当前轮次的纠正证据
     * @param replaceContact 是否重新执行 owner 范围联系人匹配
     * @param replaceSourceAudio 是否用当前录音替换消息原声音频
     */
    public TaskDraftRevision(
            TaskIntent intent,
            TaskInterpretationOutcome outcome,
            TaskSpeechRecognition recognition,
            String messageText,
            List<TaskCorrectionView> corrections,
            boolean replaceContact,
            boolean replaceSourceAudio) {
        this(intent, outcome, recognition, messageText, corrections,
                replaceContact, replaceSourceAudio, null);
    }

    /** 固化纠正列表。 */
    public TaskDraftRevision {
        corrections = corrections == null ? List.of() : List.copyOf(corrections);
    }
}