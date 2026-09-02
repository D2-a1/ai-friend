package com.aifriend.task.infrastructure;

import java.util.List;
import java.util.UUID;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.task.application.TaskClientContext;
import com.aifriend.task.application.TaskContactCandidate;
import com.aifriend.task.application.TaskContactMatcherPort;
import com.aifriend.task.application.TaskSpeechRecognition;
import com.aifriend.voice.application.ValidatedAudioObject;

/**
 * 正式联系人称呼匹配器未加载时的失败关闭适配器。
 *
 * @author Codex
 * @since 1.0.0
 */
public class UnavailableTaskContactMatcherAdapter implements TaskContactMatcherPort {

    /** 创建失败关闭联系人匹配适配器。 */
    public UnavailableTaskContactMatcherAdapter() {
    }

    /** {@inheritDoc} */
    @Override
    public List<TaskContactCandidate> match(
            UUID ownerUserId,
            ValidatedAudioObject audioObject,
            TaskSpeechRecognition recognition,
            TaskClientContext context) {
        throw new BusinessException(ErrorCode.NO_CONTACT_MATCH);
    }
}
