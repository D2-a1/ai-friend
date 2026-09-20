package com.aifriend.task.application;

import com.aifriend.task.domain.TaskRevisionMode;

/**
 * 当前语音会话中的需求重说或纠错命令。
 *
 * @param audioObjectId 本轮 TASK 录音对象编号
 * @param expectedVersion 客户端已播报的会话版本
 * @param mode 完整重说或对已复述草稿的定向纠错
 * @param basicRecognition 可空的 Android 本机基础识别证据
 */
public record ReviseTaskCommand(
        String audioObjectId,
        long expectedVersion,
        TaskRevisionMode mode,
        TaskClientRecognitionEvidence basicRecognition) {
}
