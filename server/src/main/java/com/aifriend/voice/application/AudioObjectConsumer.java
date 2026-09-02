package com.aifriend.voice.application;

/**
 * 已校验音频的一次性业务消费回调。
 *
 * <p>回调在锁定音频元数据的短事务内执行，只能做有界的领域校验和数据库写入。
 * 禁止在回调中调用 ASR、对象存储、微信或其他不可回滚的外部能力。
 *
 * @param <T> 业务消费结果类型
 * @author Codex
 * @since 1.0.0
 */
@FunctionalInterface
public interface AudioObjectConsumer<T> {

    /**
     * 在当前数据库事务内消费已校验音频。
     *
     * @param audioObject 已通过完整内容校验的音频快照
     * @return 业务消费结果
     */
    T consume(ValidatedAudioObject audioObject);
}
