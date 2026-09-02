package com.aifriend.task.application;

import com.aifriend.voice.application.ValidatedAudioObject;

/**
 * 只提供辅助证据的普通话本地识别引擎端口。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface TaskMandarinAssistAsrEnginePort {

    /**
     * 获取不含路径和正文的引擎配置快照。
     *
     * @return 引擎配置快照
     */
    TaskAsrEngineDescriptor descriptor();

    /**
     * 识别已校验 TASK 音频，但结果不得单独决定联系人或执行。
     *
     * @param audioObject 已校验音频快照
     * @return 有界 N-best 结果
     */
    TaskAsrEngineResult recognize(ValidatedAudioObject audioObject);
}
