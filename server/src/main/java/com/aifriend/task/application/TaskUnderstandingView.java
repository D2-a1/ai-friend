package com.aifriend.task.application;

import java.util.List;

import com.aifriend.task.domain.TaskIntent;

/**
 * 任务临时语音理解结果。
 *
 * @param intent 有限通信意图
 * @param contact 当前唯一联系人，可空
 * @param transcript 临时敏感转写
 * @param messageText 与有效原声严格对应的临时消息正文；非消息任务为空
 * @param effectiveAudioRanges 有效原声区间
 * @param corrections 已证明边界的一次明确纠正区间
 * @param confidence 识别置信度
 * @param processingVersions 实际处理版本
 * @author Codex
 * @since 1.0.0
 */
public record TaskUnderstandingView(
        TaskIntent intent,
        TaskMatchedContactView contact,
        String transcript,
        String messageText,
        List<TaskAudioRange> effectiveAudioRanges,
        List<TaskCorrectionView> corrections,
        double confidence,
        TaskProcessingVersionsView processingVersions) {

    /** 固化响应列表。 */
    public TaskUnderstandingView {
        effectiveAudioRanges = List.copyOf(effectiveAudioRanges);
        corrections = List.copyOf(corrections);
    }
}
