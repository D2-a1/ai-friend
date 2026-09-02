package com.aifriend.task.application;

import java.util.List;
import java.util.UUID;

import com.aifriend.voice.application.ValidatedAudioObject;

/**
 * owner 范围联系人称呼声学匹配端口。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface TaskContactMatcherPort {

    /**
     * 使用原声和转写候选信号匹配最多三个有效联系人。
     *
     * @param ownerUserId JWT 派生 owner UUID
     * @param audioObject 已校验任务音频
     * @param recognition 临时识别结果
     * @param context 客户端模型版本
     * @return 按可靠度排序的 owner 范围候选
     */
    List<TaskContactCandidate> match(
            UUID ownerUserId,
            ValidatedAudioObject audioObject,
            TaskSpeechRecognition recognition,
            TaskClientContext context);
}
