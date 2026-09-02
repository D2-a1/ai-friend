package com.aifriend.task.infrastructure;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.task.application.RoutineCommandLearningEvidence;
import com.aifriend.task.application.TaskActionPhraseCatalog;
import com.aifriend.task.application.TaskAsrSource;
import com.aifriend.task.application.TaskAudioRange;
import com.aifriend.task.application.TaskClientContext;
import com.aifriend.task.application.TaskContactCandidate;
import com.aifriend.task.application.TaskContactMatcherPort;
import com.aifriend.task.application.TaskIntentDecision;
import com.aifriend.task.application.TaskIntentPort;
import com.aifriend.task.application.TaskRecognizedWord;
import com.aifriend.task.application.TaskRoutineCommandMatchDecision;
import com.aifriend.task.application.TaskRoutineCommandMatcherPort;
import com.aifriend.task.application.TaskSpeechRecognition;
import com.aifriend.task.application.TaskTranscriptCandidate;
import com.aifriend.task.domain.TaskIntent;
import com.aifriend.voice.application.ValidatedAudioObject;

/**
 * 在联系人声学匹配前执行日常指令模板复核的显式装饰器。
 *
 * <p>任务解释已经隔离纠正旧子句后，本装饰器只从最终主方言词边界提取恰好一个
 * 已允许学习的动作范围。声学冲突直接要求全新重说；一致或无匹配才进入既有
 * owner/ACTIVE 联系人匹配，不能绕过完整复述和个人动作型确认。
 *
 * @author Codex
 * @since 1.0.0
 */
@Primary
@Component
public class RoutineAwareTaskContactMatcherAdapter
        implements TaskContactMatcherPort {

    private final TaskContactMatcherPort delegate;
    private final TaskIntentPort intentPort;
    private final TaskRoutineCommandMatcherPort routineCommandMatcherPort;

    /**
     * 创建日常指令复核联系人匹配装饰器。
     *
     * @param delegate 既有 MFCC/DTW 联系人匹配器
     * @param intentPort 本地有限关键词意图端口
     * @param routineCommandMatcherPort owner 日常指令声学复核端口
     */
    public RoutineAwareTaskContactMatcherAdapter(
            @Qualifier("mfccDtwTaskContactMatcherAdapter")
            TaskContactMatcherPort delegate,
            TaskIntentPort intentPort,
            TaskRoutineCommandMatcherPort routineCommandMatcherPort) {
        this.delegate = delegate;
        this.intentPort = intentPort;
        this.routineCommandMatcherPort = routineCommandMatcherPort;
    }

    /** {@inheritDoc} */
    @Override
    public List<TaskContactCandidate> match(
            UUID ownerUserId,
            ValidatedAudioObject audioObject,
            TaskSpeechRecognition recognition,
            TaskClientContext context) {
        TaskTranscriptCandidate primary = recognition.nBest().stream()
                .filter(candidate -> candidate.source() == TaskAsrSource.PRIMARY)
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.AUDIO_SEGMENT_UNCERTAIN));
        TaskIntentDecision intentDecision = intentPort.parse(primary.transcript());
        RoutineCommandLearningEvidence evidence = actionEvidence(
                intentDecision.intent(), primary.words());
        TaskRoutineCommandMatchDecision routineDecision =
                routineCommandMatcherPort.match(
                        ownerUserId, audioObject, context,
                        intentDecision.intent(), evidence);
        if (routineDecision == null || routineDecision.requiresRetry()) {
            throw new BusinessException(ErrorCode.AUDIO_SEGMENT_UNCERTAIN);
        }
        return delegate.match(ownerUserId, audioObject, recognition, context);
    }

    private RoutineCommandLearningEvidence actionEvidence(
            TaskIntent intent,
            List<TaskRecognizedWord> words) {
        List<String> allowedPhrases =
                TaskActionPhraseCatalog.routineLearningPhrases(intent);
        if (allowedPhrases.isEmpty()) {
            return null;
        }
        List<TaskAudioRange> matches = new ArrayList<>();
        for (int startIndex = 0; startIndex < words.size(); startIndex++) {
            StringBuilder phrase = new StringBuilder();
            for (int endIndex = startIndex; endIndex < words.size(); endIndex++) {
                phrase.append(normalizeWord(words.get(endIndex).text()));
                if (allowedPhrases.contains(phrase.toString())) {
                    matches.add(new TaskAudioRange(
                            words.get(startIndex).startMs(),
                            words.get(endIndex).endMs()));
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

    private String normalizeWord(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "");
    }
}
