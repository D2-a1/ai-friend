package com.aifriend.task.application;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.task.domain.TaskIntent;

/**
 * 有限意图、明确纠正关系和最终连续原声范围解释服务。
 *
 * <p>只接受恰好一个、完整落在主方言词边界上的纠正标记。纠正后的完整子句
 * 独立决定最终动作并独立参与联系人匹配；旧子句不会进入候选、摘要或原声范围。
 * 多次纠正、残缺替换或动作冲突统一要求重说，不按最后一句猜测。
 *
 * @author Codex
 * @since 1.0.0
 */
@Service
public class TaskUtteranceInterpretationService {

    private static final int MAXIMUM_PRIMARY_WORDS = 120;
    private static final int MAXIMUM_MESSAGE_TEXT_LENGTH = 500;
    private static final List<String> CORRECTION_MARKERS = List.of(
            "不对", "说错了", "改成", "纠正", "不是");

    private final TaskIntentPort intentPort;
    private final TaskAudioAlignmentService alignmentService;

    /**
     * 创建任务语音解释服务。
     *
     * @param intentPort 本地有限意图解析端口
     * @param alignmentService 连续原声范围计算服务
     */
    public TaskUtteranceInterpretationService(
            TaskIntentPort intentPort,
            TaskAudioAlignmentService alignmentService) {
        this.intentPort = intentPort;
        this.alignmentService = alignmentService;
    }

    /**
     * 解释主方言首选证据并隔离被纠正的旧子句。
     *
     * @param recognition 主辅融合后的原始识别证据
     * @param actualDurationMs 已解码真实音频时长
     * @return 最终有限意图、有效识别证据和纠正映射
     * @throws BusinessException 当主方言时间戳或原声范围不可靠时抛出
     */
    public TaskUtteranceInterpretation interpret(
            TaskSpeechRecognition recognition,
            int actualDurationMs) {
        TaskTranscriptCandidate primary = requirePrimaryCandidate(
                recognition, actualDurationMs);
        TaskIntentDecision wholeDecision = intentPort.parse(recognition.transcript());
        if (wholeDecision.intent() == TaskIntent.CANCEL) {
            return interpretation(TaskIntent.CANCEL, TaskInterpretationOutcome.CANCELLED,
                    withEvidence(recognition, primary, List.of()), null, List.of(), null);
        }
        if (wholeDecision.intent() == TaskIntent.CORRECT) {
            return resolveCorrection(recognition, primary, actualDurationMs);
        }
        if (isMessageContentMissing(wholeDecision, primary)) {
            return interpretation(TaskIntent.SEND_MESSAGE,
                    TaskInterpretationOutcome.NEEDS_CONTENT_REPEAT,
                    withEvidence(recognition, primary, List.of()), null, List.of(), null);
        }
        if (!isCompleteCommunication(wholeDecision, primary)) {
            return interpretation(TaskIntent.HELP, TaskInterpretationOutcome.NEEDS_RETRY,
                    withEvidence(recognition, primary, List.of()), null, List.of(), null);
        }
        List<TaskAudioRange> ranges = effectiveRanges(
                wholeDecision.intent(), primary, actualDurationMs);
        return interpretation(wholeDecision.intent(), TaskInterpretationOutcome.READY,
                withEvidence(recognition, primary, ranges),
                messageText(wholeDecision.intent(), primary, ranges), List.of(),
                learningEvidence(wholeDecision.intent(), primary));
    }

    private TaskUtteranceInterpretation resolveCorrection(
            TaskSpeechRecognition recognition,
            TaskTranscriptCandidate primary,
            int actualDurationMs) {
        List<MarkerSpan> markers = findCorrectionMarkers(primary.words());
        if (markers.size() != 1) {
            return unresolvedCorrection(recognition, primary);
        }
        MarkerSpan marker = markers.get(0);
        if (marker.startIndex() == 0
                || marker.endIndex() >= primary.words().size()) {
            return unresolvedCorrection(recognition, primary);
        }
        List<TaskRecognizedWord> supersededWords = List.copyOf(
                primary.words().subList(0, marker.startIndex()));
        List<TaskRecognizedWord> replacementWords = List.copyOf(
                primary.words().subList(marker.endIndex(), primary.words().size()));
        TaskTranscriptCandidate superseded = clause(primary, supersededWords);
        TaskTranscriptCandidate replacement = clause(primary, replacementWords);
        TaskIntentDecision supersededDecision = intentPort.parse(
                superseded.transcript());
        TaskIntentDecision replacementDecision = intentPort.parse(
                replacement.transcript());
        if (!isCompleteCommunication(supersededDecision, superseded)
                || !isCompleteCommunication(replacementDecision, replacement)) {
            return unresolvedCorrection(recognition, primary);
        }
        List<TaskAudioRange> ranges = effectiveRanges(
                replacementDecision.intent(), replacement, actualDurationMs);
        TaskCorrectionView correction = new TaskCorrectionView(
                correctionSlot(supersededDecision.intent(),
                        replacementDecision.intent()),
                rangeOf(supersededWords), rangeOf(replacementWords));
        return interpretation(
                replacementDecision.intent(),
                TaskInterpretationOutcome.READY,
                withEvidence(recognition, replacement, ranges),
                messageText(replacementDecision.intent(), replacement, ranges),
                List.of(correction),
                learningEvidence(replacementDecision.intent(), replacement));
    }

    private TaskUtteranceInterpretation unresolvedCorrection(
            TaskSpeechRecognition recognition,
            TaskTranscriptCandidate primary) {
        return interpretation(TaskIntent.CORRECT, TaskInterpretationOutcome.NEEDS_RETRY,
                withEvidence(recognition, primary, List.of()), null, List.of(), null);
    }

    private TaskUtteranceInterpretation interpretation(
            TaskIntent intent,
            TaskInterpretationOutcome outcome,
            TaskSpeechRecognition recognition,
            String messageText,
            List<TaskCorrectionView> corrections,
            RoutineCommandLearningEvidence learningEvidence) {
        return new TaskUtteranceInterpretation(
                intent, outcome, recognition, messageText, corrections, learningEvidence);
    }

    private String messageText(
            TaskIntent intent,
            TaskTranscriptCandidate primary,
            List<TaskAudioRange> ranges) {
        if (intent != TaskIntent.SEND_MESSAGE) {
            return null;
        }
        if (ranges.size() != 1) {
            throw uncertain();
        }
        TaskAudioRange range = ranges.get(0);
        List<TaskRecognizedWord> matchingWords = primary.words().stream()
                .filter(word -> word.startMs() >= range.startMs()
                        && word.endMs() <= range.endMs())
                .toList();
        if (matchingWords.isEmpty()
                || matchingWords.get(0).startMs() != range.startMs()
                || matchingWords.get(matchingWords.size() - 1).endMs() != range.endMs()) {
            throw uncertain();
        }
        StringBuilder messageBuilder = new StringBuilder();
        matchingWords.forEach(word -> messageBuilder.append(normalizeWord(word.text())));
        String message = messageBuilder.toString();
        if (!StringUtils.hasText(message) || message.length() > MAXIMUM_MESSAGE_TEXT_LENGTH) {
            throw uncertain();
        }
        return message;
    }

    private RoutineCommandLearningEvidence learningEvidence(
            TaskIntent intent,
            TaskTranscriptCandidate primary) {
        List<String> allowedPhrases = TaskActionPhraseCatalog
                .routineLearningPhrases(intent);
        if (allowedPhrases.isEmpty()) {
            return null;
        }
        List<TaskAudioRange> matches = new ArrayList<>();
        for (int startIndex = 0; startIndex < primary.words().size(); startIndex++) {
            StringBuilder phrase = new StringBuilder();
            for (int endIndex = startIndex;
                    endIndex < primary.words().size();
                    endIndex++) {
                phrase.append(normalizeWord(primary.words().get(endIndex).text()));
                if (allowedPhrases.contains(phrase.toString())) {
                    matches.add(new TaskAudioRange(
                            primary.words().get(startIndex).startMs(),
                            primary.words().get(endIndex).endMs()));
                }
                if (allowedPhrases.stream().noneMatch(
                        candidate -> candidate.startsWith(phrase.toString()))) {
                    break;
                }
            }
        }
        return matches.size() == 1
                ? new RoutineCommandLearningEvidence(intent, matches.get(0))
                : null;
    }

    private TaskSpeechRecognition withEvidence(
            TaskSpeechRecognition source,
            TaskTranscriptCandidate primary,
            List<TaskAudioRange> ranges) {
        return new TaskSpeechRecognition(
                primary.transcript(), List.of(primary), ranges,
                source.confidence(), source.primaryAsrModelVersion(),
                source.mandarinAssistModelVersion(), source.fusionRuleVersion(),
                source.alignmentVersion());
    }

    private List<TaskAudioRange> effectiveRanges(
            TaskIntent intent,
            TaskTranscriptCandidate primary,
            int actualDurationMs) {
        return intent == TaskIntent.SEND_MESSAGE
                ? alignmentService.align(primary, actualDurationMs) : List.of();
    }

    private TaskTranscriptCandidate requirePrimaryCandidate(
            TaskSpeechRecognition recognition,
            int actualDurationMs) {
        if (recognition == null || actualDurationMs <= 0) {
            throw uncertain();
        }
        TaskTranscriptCandidate primary = recognition.nBest().stream()
                .filter(candidate -> candidate != null
                        && candidate.source() == TaskAsrSource.PRIMARY)
                .findFirst()
                .orElseThrow(this::uncertain);
        validateWords(primary.words(), actualDurationMs);
        return primary;
    }

    private void validateWords(
            List<TaskRecognizedWord> words,
            int actualDurationMs) {
        if (words.isEmpty() || words.size() > MAXIMUM_PRIMARY_WORDS) {
            throw uncertain();
        }
        int previousEndMs = 0;
        for (TaskRecognizedWord word : words) {
            boolean invalid = word == null || !StringUtils.hasText(word.text())
                    || word.startMs() < previousEndMs
                    || word.endMs() <= word.startMs()
                    || word.endMs() > actualDurationMs
                    || !Double.isFinite(word.confidence())
                    || word.confidence() < 0.0D || word.confidence() > 1.0D;
            if (invalid) {
                throw uncertain();
            }
            previousEndMs = word.endMs();
        }
    }

    private List<MarkerSpan> findCorrectionMarkers(
            List<TaskRecognizedWord> words) {
        List<MarkerSpan> matches = new ArrayList<>();
        for (int startIndex = 0; startIndex < words.size(); startIndex++) {
            StringBuilder candidate = new StringBuilder();
            for (int endIndex = startIndex; endIndex < words.size(); endIndex++) {
                candidate.append(normalizeWord(words.get(endIndex).text()));
                String value = candidate.toString();
                if (CORRECTION_MARKERS.contains(value)) {
                    MarkerSpan span = new MarkerSpan(startIndex, endIndex + 1);
                    if (!matches.contains(span)) {
                        matches.add(span);
                    }
                    break;
                }
                if (CORRECTION_MARKERS.stream().noneMatch(
                        marker -> marker.startsWith(value))) {
                    break;
                }
            }
        }
        return matches;
    }

    private TaskTranscriptCandidate clause(
            TaskTranscriptCandidate source,
            List<TaskRecognizedWord> words) {
        return new TaskTranscriptCandidate(
                transcript(words), words, source.confidence(),
                source.source(), source.modelVersion());
    }

    private String transcript(List<TaskRecognizedWord> words) {
        StringJoiner joiner = new StringJoiner(" ");
        words.forEach(word -> joiner.add(word.text().strip()));
        return joiner.toString();
    }

    private TaskAudioRange rangeOf(List<TaskRecognizedWord> words) {
        return new TaskAudioRange(
                words.get(0).startMs(), words.get(words.size() - 1).endMs());
    }

    private String correctionSlot(TaskIntent superseded, TaskIntent replacement) {
        if (superseded != replacement) {
            return "ACTION";
        }
        return replacement == TaskIntent.SEND_MESSAGE ? "CONTENT" : "CONTACT";
    }

    private boolean isCompleteCommunication(
            TaskIntentDecision decision,
            TaskTranscriptCandidate candidate) {
        if (decision == null || decision.requiresRetry()
                || !requiresContact(decision.intent())) {
            return false;
        }
        if (decision.intent() != TaskIntent.SEND_MESSAGE) {
            return true;
        }
        return !isMessageContentMissing(decision, candidate);
    }

    private boolean isMessageContentMissing(
            TaskIntentDecision decision,
            TaskTranscriptCandidate candidate) {
        if (decision == null || decision.requiresRetry()
                || decision.intent() != TaskIntent.SEND_MESSAGE) {
            return false;
        }
        List<TaskRecognizedWord> words = candidate.words();
        if (words.size() < 2) {
            return true;
        }
        String firstWord = normalizeWord(words.get(0).text());
        boolean onlyRecipientAfterPrefix = words.size() == 2
                && List.of("给", "告诉", "叫", "喊").contains(firstWord);
        return onlyRecipientAfterPrefix;
    }

    private boolean requiresContact(TaskIntent intent) {
        return intent == TaskIntent.SEND_MESSAGE
                || intent == TaskIntent.VOICE_CALL
                || intent == TaskIntent.VIDEO_CALL;
    }

    private String normalizeWord(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "");
    }

    private BusinessException uncertain() {
        return new BusinessException(ErrorCode.AUDIO_SEGMENT_UNCERTAIN);
    }

    private record MarkerSpan(int startIndex, int endIndex) {
    }
}
