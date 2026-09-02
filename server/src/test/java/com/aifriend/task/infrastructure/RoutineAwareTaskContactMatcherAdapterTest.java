package com.aifriend.task.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.task.application.TaskAsrSource;
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
import com.aifriend.voice.domain.AudioPurpose;

class RoutineAwareTaskContactMatcherAdapterTest {

    private final TaskContactMatcherPort delegate = mock(TaskContactMatcherPort.class);
    private final TaskIntentPort intentPort = mock(TaskIntentPort.class);
    private final TaskRoutineCommandMatcherPort routineMatcher =
            mock(TaskRoutineCommandMatcherPort.class);
    private final UUID ownerUserId = UUID.randomUUID();

    @Test
    void acousticIntentConflictMustStopBeforeContactMatching() {
        RoutineAwareTaskContactMatcherAdapter adapter = adapter();
        when(intentPort.parse("给张三打电话"))
                .thenReturn(new TaskIntentDecision(TaskIntent.VOICE_CALL, false));
        when(routineMatcher.match(eq(ownerUserId), any(), any(),
                eq(TaskIntent.VOICE_CALL), any()))
                .thenReturn(TaskRoutineCommandMatchDecision.conflict());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> adapter.match(ownerUserId, audio(), recognition(), context()));

        assertEquals(ErrorCode.AUDIO_SEGMENT_UNCERTAIN, exception.errorCode());
        verify(delegate, never()).match(any(), any(), any(), any());
    }

    @Test
    void corroboratedIntentMustDelegateToOwnerContactMatcher() {
        RoutineAwareTaskContactMatcherAdapter adapter = adapter();
        TaskContactCandidate candidate = candidate();
        when(intentPort.parse("给张三打电话"))
                .thenReturn(new TaskIntentDecision(TaskIntent.VOICE_CALL, false));
        when(routineMatcher.match(eq(ownerUserId), any(), any(),
                eq(TaskIntent.VOICE_CALL), any()))
                .thenReturn(TaskRoutineCommandMatchDecision.corroborated(
                        TaskIntent.VOICE_CALL));
        when(delegate.match(eq(ownerUserId), any(), any(), any()))
                .thenReturn(List.of(candidate));

        List<TaskContactCandidate> result = adapter.match(
                ownerUserId, audio(), recognition(), context());

        assertEquals(List.of(candidate), result);
        verify(delegate).match(eq(ownerUserId), any(), any(), any());
    }

    private RoutineAwareTaskContactMatcherAdapter adapter() {
        return new RoutineAwareTaskContactMatcherAdapter(
                delegate, intentPort, routineMatcher);
    }

    private TaskSpeechRecognition recognition() {
        TaskTranscriptCandidate primary = new TaskTranscriptCandidate(
                "给张三打电话",
                List.of(
                        new TaskRecognizedWord("给", 0, 100, 0.9D),
                        new TaskRecognizedWord("张三", 100, 300, 0.9D),
                        new TaskRecognizedWord("打", 300, 450, 0.9D),
                        new TaskRecognizedWord("电话", 450, 700, 0.9D)),
                0.9D, TaskAsrSource.PRIMARY, "primary-v1");
        return new TaskSpeechRecognition(
                primary.transcript(), List.of(primary), List.of(), 0.9D,
                "primary-v1", "assist-v1", "fusion-v1", "alignment-v1");
    }

    private ValidatedAudioObject audio() {
        return new ValidatedAudioObject(
                UUID.randomUUID(), ownerUserId, AudioPurpose.TASK,
                "audio/wav", new byte[] {1, 2, 3}, 1_000, "v1", 0L);
    }

    private TaskClientContext context() {
        return new TaskClientContext(
                "1.0.0", "wechat-v1", "rule-v1",
                "zh-Hans-CN-x-wugang", "dialect-v1", "assist-v1",
                "fusion-v1", "mfcc-v1", "threshold-v1");
    }

    private TaskContactCandidate candidate() {
        UUID contactId = UUID.randomUUID();
        return new TaskContactCandidate(
                UUID.randomUUID().toString(), contactId,
                "ct_" + contactId.toString().replace("-", ""),
                "张三", "老张", "UNIQUE", 1);
    }
}
