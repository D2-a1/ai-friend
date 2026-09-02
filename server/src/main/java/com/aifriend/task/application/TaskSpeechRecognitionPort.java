package com.aifriend.task.application;

import com.aifriend.voice.application.ValidatedAudioObject;

/**
 * 主方言和普通话辅助 ASR 原始证据端口。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface TaskSpeechRecognitionPort {

    /**
     * 在数据库事务外识别当前 TASK 音频。
     *
     * @param audioObject 已完整校验的 TASK 音频快照
     * @param context 客户端版本上下文
     * @return 尚未解析纠正关系的临时识别证据
     */
    TaskSpeechRecognition recognize(
            ValidatedAudioObject audioObject,
            TaskClientContext context);
}
