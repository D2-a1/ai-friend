package com.aifriend.task.application;

import com.aifriend.task.domain.TaskIntent;

/**
 * 加密任务载荷中的日常指令学习资格证据。
 *
 * <p>只保存有限动作和完整词边界对应的声学区间，不保存动作文字、联系人或消息正文。
 *
 * @param intent SEND_MESSAGE、VOICE_CALL 或 VIDEO_CALL
 * @param actionAudioRange 只覆盖动作短语的连续原声区间
 * @author Codex
 * @since 1.0.0
 */
public record RoutineCommandLearningEvidence(
        TaskIntent intent,
        TaskAudioRange actionAudioRange) {
}
