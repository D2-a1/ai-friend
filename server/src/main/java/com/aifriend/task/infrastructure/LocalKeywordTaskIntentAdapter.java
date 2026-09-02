package com.aifriend.task.infrastructure;

import java.util.Locale;

import org.springframework.stereotype.Component;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.task.application.TaskActionPhraseCatalog;
import com.aifriend.task.application.TaskIntentDecision;
import com.aifriend.task.application.TaskIntentPort;
import com.aifriend.task.domain.TaskIntent;

/**
 * 本地有限关键词意图候选适配器。
 *
 * <p>取消和纠正优先于消息与通话词；解析结果仅用于生成候选状态，不能绕过联系人唯一性、
 * 完整复述和个人安全指令确认。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class LocalKeywordTaskIntentAdapter implements TaskIntentPort {

    private static final String[] CANCEL_MARKERS = {
        "取消", "不要了", "算了", "莫搞"
    };
    private static final String[] CORRECTION_MARKERS = {
        "不对", "不是", "说错了", "改成", "纠正"
    };
    private static final String[] HELP_MARKERS = {
        "帮助", "怎么用", "你会什么", "你能做什么"
    };
    private static final String[] LOW_INFORMATION_UTTERANCES = {
        "嗯", "好", "好的", "对", "是", "是的"
    };

    /** 创建本地有限意图适配器。 */
    public LocalKeywordTaskIntentAdapter() {
    }

    /** {@inheritDoc} */
    @Override
    public TaskIntentDecision parse(String transcript) {
        if (transcript == null || transcript.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        String normalized = normalize(transcript);
        if (containsAny(normalized, CANCEL_MARKERS)) {
            return decision(TaskIntent.CANCEL, false);
        }
        if (containsAny(normalized, CORRECTION_MARKERS)) {
            return decision(TaskIntent.CORRECT, false);
        }
        if (equalsAny(normalized, LOW_INFORMATION_UTTERANCES)
                || containsAny(normalized, HELP_MARKERS)) {
            return decision(TaskIntent.HELP, true);
        }
        boolean hasVideoCall = containsAny(
                normalized, TaskActionPhraseCatalog.videoCallPhrases());
        boolean hasVoiceCall = containsAny(
                normalized, TaskActionPhraseCatalog.voiceCallPhrases())
                && !isVideoOnly(normalized);
        boolean hasMessage = containsAny(
                normalized, TaskActionPhraseCatalog.messagePhrases());
        int actionCount = (hasVideoCall ? 1 : 0)
                + (hasVoiceCall ? 1 : 0)
                + (hasMessage ? 1 : 0);
        if (actionCount > 1) {
            return decision(TaskIntent.HELP, true);
        }
        if (hasVideoCall) {
            return decision(TaskIntent.VIDEO_CALL, false);
        }
        if (hasVoiceCall) {
            return decision(TaskIntent.VOICE_CALL, false);
        }
        return decision(TaskIntent.SEND_MESSAGE, false);
    }

    private String normalize(String value) {
        return value.strip().replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private TaskIntentDecision decision(TaskIntent intent, boolean requiresRetry) {
        return new TaskIntentDecision(intent, requiresRetry);
    }

    private boolean isVideoOnly(String value) {
        String withoutVideoMarkers = value;
        for (String marker : TaskActionPhraseCatalog.videoCallPhrases()) {
            withoutVideoMarkers = withoutVideoMarkers.replace(marker, "");
        }
        return !containsAny(
                withoutVideoMarkers, TaskActionPhraseCatalog.voiceCallPhrases());
    }

    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAny(String value, Iterable<String> candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private boolean equalsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.equals(candidate)) {
                return true;
            }
        }
        return false;
    }
}
