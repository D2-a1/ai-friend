package com.aifriend.voice.application;

import java.util.List;

/**
 * 多个音频对象原子消费时执行的有界数据库回调。
 *
 * <p>实现不得调用对象存储、ASR、微信或其他外部网络服务。
 *
 * @param <T> 业务消费结果类型
 * @author Codex
 * @since 1.0.0
 */
@FunctionalInterface
public interface AudioObjectBatchConsumer<T> {

    /**
     * 在全部音频对象已加锁复验后执行业务数据库写入。
     *
     * @param validatedAudioObjects 按业务请求原顺序排列的已校验音频快照
     * @return 业务消费结果
     */
    T consume(List<ValidatedAudioObject> validatedAudioObjects);
}
