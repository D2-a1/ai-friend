package com.aifriend.task.infrastructure;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.aifriend.task.application.TaskActionPhraseCatalog;
import com.aifriend.task.application.TaskAudioRange;
import com.aifriend.task.application.TaskConversationUnderstandingPort;
import com.aifriend.task.application.TaskConversationPreferences;
import com.aifriend.task.application.TaskDraftRevision;
import com.aifriend.task.application.TaskInterpretationOutcome;
import com.aifriend.task.application.TaskPayload;
import com.aifriend.task.application.TaskRecognizedWord;
import com.aifriend.task.application.TaskSpeechRecognition;
import com.aifriend.task.application.TaskTranscriptCandidate;
import com.aifriend.task.application.TaskUtteranceInterpretation;
import com.aifriend.task.application.TaskUtteranceInterpretationService;
import com.aifriend.task.domain.TaskIntent;
import com.aifriend.task.domain.TaskRevisionMode;

/**
 * 未配置语义模型时的保守上下文理解回退。
 *
 * <p>该实现只接受明确的动作词、联系人纠正标记和消息内容边界；无法确定时返回
 * NEEDS_RETRY，由交互层在同一唤醒会话内继续追问。它不是 AI 模型，也不得被宣传为
 * 语义模型能力。生产环境显式启用真实模型后，首选模型 Bean 处理请求；本实现保留为
 * 未启用模型时的安全基线，不会在模型故障时接管并猜测执行。
 */
@Component
public class LocalContextualTaskUnderstandingAdapter
        implements TaskConversationUnderstandingPort {

    private static final List<String> CANCEL_MARKERS = List.of(
            "取消", "不要了", "算了", "莫搞");
    private static final List<String> CONTACT_MARKERS = List.of(
            "联系人不对", "人不对", "换个人", "换成", "不是", "给");
    private static final List<String> CONTENT_MARKERS = List.of(
            "内容改成", "消息改成", "改成");

    private final TaskUtteranceInterpretationService interpretationService;

    /**
     * 创建保守回退。
     *
     * @param interpretationService 既有明确任务语句解释器
     */
    public LocalContextualTaskUnderstandingAdapter(
            TaskUtteranceInterpretationService interpretationService) {
        this.interpretationService = interpretationService;
    }

    /** {@inheritDoc} */
    @Override
    public TaskDraftRevision revise(
            TaskPayload current,
            TaskSpeechRecognition recognition,
            int actualDurationMs,
            TaskRevisionMode mode,
            TaskConversationPreferences preferences) {
        if (mode == TaskRevisionMode.FULL_RETRY) {
            TaskUtteranceInterpretation result = interpretationService.interpret(
                    recognition, actualDurationMs);
            return new TaskDraftRevision(
                    result.intent(), result.outcome(), result.recognition(),
                    result.messageText(), result.corrections(), true,
                    result.outcome() == TaskInterpretationOutcome.READY,
                    result.routineCommandLearningEvidence());
        }
        String normalized = normalize(recognition.transcript());
        if (containsAny(normalized, CANCEL_MARKERS)) {
            return result(TaskIntent.CANCEL, TaskInterpretationOutcome.CANCELLED,
                    recognition, null, false, false);
        }
        TaskIntent currentIntent = current.understanding().intent();
        TaskDraftRevision content = contentCorrection(
                currentIntent, recognition, actualDurationMs, normalized);
        if (content != null) {
            return content;
        }
        TaskIntent explicitAction = explicitAction(normalized);
        boolean replaceContact = containsAny(normalized, CONTACT_MARKERS)
                && !isActionOnlyCorrection(normalized);
        if (explicitAction == null && !replaceContact) {
            return result(currentIntent, TaskInterpretationOutcome.NEEDS_RETRY,
                    recognition, current.understanding().messageText(), false, false);
        }
        TaskIntent nextIntent = explicitAction == null ? currentIntent : explicitAction;
        String messageText = nextIntent == TaskIntent.SEND_MESSAGE
                ? current.understanding().messageText() : null;
        if (nextIntent == TaskIntent.SEND_MESSAGE && !StringUtils.hasText(messageText)) {
            return result(nextIntent, TaskInterpretationOutcome.NEEDS_CONTENT_REPEAT,
                    recognition, null, replaceContact, false);
        }
        return result(nextIntent, TaskInterpretationOutcome.READY,
                recognition, messageText, replaceContact, false);
    }

    private TaskDraftRevision contentCorrection(
            TaskIntent currentIntent,
            TaskSpeechRecognition recognition,
            int actualDurationMs,
            String normalized) {
        if (currentIntent != TaskIntent.SEND_MESSAGE) {
            return null;
        }
        String marker = CONTENT_MARKERS.stream()
                .filter(normalized::contains)
                .findFirst().orElse(null);
        if (marker == null) {
            return null;
        }
        List<TaskRecognizedWord> tail = tailAfterMarker(
                recognition, marker, actualDurationMs);
        if (tail.isEmpty()) {
            return result(TaskIntent.SEND_MESSAGE,
                    TaskInterpretationOutcome.NEEDS_CONTENT_REPEAT,
                    recognition, null, false, false);
        }
        StringBuilder message = new StringBuilder();
        tail.forEach(word -> message.append(normalize(word.text())));
        if (message.isEmpty() || message.length() > 500) {
            return result(TaskIntent.SEND_MESSAGE,
                    TaskInterpretationOutcome.NEEDS_CONTENT_REPEAT,
                    recognition, null, false, false);
        }
        TaskAudioRange range = new TaskAudioRange(
                tail.get(0).startMs(), tail.get(tail.size() - 1).endMs());
        TaskTranscriptCandidate source = recognition.nBest().get(0);
        TaskTranscriptCandidate candidate = new TaskTranscriptCandidate(
                message.toString(), tail, source.confidence(),
                source.source(), source.modelVersion());
        TaskSpeechRecognition effective = new TaskSpeechRecognition(
                message.toString(), List.of(candidate), List.of(range),
                recognition.confidence(), recognition.primaryAsrModelVersion(),
                recognition.mandarinAssistModelVersion(),
                recognition.fusionRuleVersion(), recognition.alignmentVersion());
        return result(TaskIntent.SEND_MESSAGE, TaskInterpretationOutcome.READY,
                effective, message.toString(), false, true);
    }

    private List<TaskRecognizedWord> tailAfterMarker(
            TaskSpeechRecognition recognition,
            String marker,
            int actualDurationMs) {
        if (recognition.nBest().isEmpty()) {
            return List.of();
        }
        List<TaskRecognizedWord> words = recognition.nBest().get(0).words();
        List<TaskRecognizedWord> prefix = new ArrayList<>();
        StringBuilder joined = new StringBuilder();
        int markerEnd = -1;
        for (TaskRecognizedWord word : words) {
            if (word == null || word.startMs() < 0 || word.endMs() <= word.startMs()
                    || word.endMs() > actualDurationMs) {
                return List.of();
            }
            prefix.add(word);
            joined.append(normalize(word.text()));
            int index = joined.indexOf(marker);
            if (index >= 0 && index + marker.length() == joined.length()) {
                markerEnd = prefix.size();
                break;
            }
            if (index >= 0 && index + marker.length() < joined.length()) {
                return List.of();
            }
        }
        return markerEnd >= 0 && markerEnd < words.size()
                ? List.copyOf(words.subList(markerEnd, words.size())) : List.of();
    }

    private TaskIntent explicitAction(String value) {
        boolean video = containsAny(value, TaskActionPhraseCatalog.videoCallPhrases());
        boolean voice = containsAny(value, TaskActionPhraseCatalog.voiceCallPhrases())
                && !video;
        boolean message = containsAny(value, TaskActionPhraseCatalog.messagePhrases());
        int count = (video ? 1 : 0) + (voice ? 1 : 0) + (message ? 1 : 0);
        if (count != 1) {
            return null;
        }
        if (video) return TaskIntent.VIDEO_CALL;
        if (voice) return TaskIntent.VOICE_CALL;
        return TaskIntent.SEND_MESSAGE;
    }

    private boolean isActionOnlyCorrection(String value) {
        String reduced = value;
        for (String marker : List.of("不对", "不是", "是", "改成", "要", "打", "发起")) {
            reduced = reduced.replace(marker, "");
        }
        for (String phrase : TaskActionPhraseCatalog.videoCallPhrases()) {
            reduced = reduced.replace(phrase, "");
        }
        for (String phrase : TaskActionPhraseCatalog.voiceCallPhrases()) {
            reduced = reduced.replace(phrase, "");
        }
        for (String phrase : TaskActionPhraseCatalog.messagePhrases()) {
            reduced = reduced.replace(phrase, "");
        }
        return reduced.isBlank();
    }

    private TaskDraftRevision result(
            TaskIntent intent,
            TaskInterpretationOutcome outcome,
            TaskSpeechRecognition recognition,
            String messageText,
            boolean replaceContact,
            boolean replaceSourceAudio) {
        return new TaskDraftRevision(intent, outcome, recognition, messageText,
                List.of(), replaceContact, replaceSourceAudio);
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "")
                .toLowerCase(Locale.ROOT);
    }

    private boolean containsAny(String value, Iterable<String> candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) return true;
        }
        return false;
    }
}
