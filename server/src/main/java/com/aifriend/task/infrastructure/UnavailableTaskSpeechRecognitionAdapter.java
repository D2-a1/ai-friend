package com.aifriend.task.infrastructure;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.task.application.TaskClientContext;
import com.aifriend.task.application.TaskSpeechRecognition;
import com.aifriend.task.application.TaskSpeechRecognitionPort;
import com.aifriend.voice.application.ValidatedAudioObject;

/**
 * 正式 ASR 未接入时的失败关闭适配器。
 *
 * <p>该适配器不调用任何云端服务，也不使用音频字节、文件名或客户端文字伪造识别结果。
 *
 * @author Codex
 * @since 1.0.0
 */
public class UnavailableTaskSpeechRecognitionAdapter
        implements TaskSpeechRecognitionPort {

    /** 创建失败关闭 ASR 适配器。 */
    public UnavailableTaskSpeechRecognitionAdapter() {
    }

    /** {@inheritDoc} */
    @Override
    public TaskSpeechRecognition recognize(
            ValidatedAudioObject audioObject,
            TaskClientContext context) {
        throw new BusinessException(ErrorCode.ASR_UNAVAILABLE);
    }
}
