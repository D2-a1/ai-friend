package com.aifriend.template.application;

/**
 * 已消费 TASK 音频的受限只读端口。
 *
 * <p>实现必须复验 owner、TASK 用途、已消费状态、对象版本、摘要、解码时长和留存截止；
 * 不得读取已删除、过期或其他任务用途的对象。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface RoutineCommandAudioEvidencePort {

    /**
     * 事务外读取学习任务唯一指定的已消费 TASK 音频。
     *
     * @param job Outbox 固化的 owner、音频和原始留存截止
     * @return 需要由调用方关闭并清零的短期音频快照
     */
    RoutineCommandAudioSnapshot read(RoutineCommandLearningJob job);
}
