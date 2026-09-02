package com.aifriend.task.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;

class TaskAudioAlignmentServiceTest {

    private final TaskAudioAlignmentService service = new TaskAudioAlignmentService();

    @Test
    void shouldRemoveLeadingActionAtExactWordBoundary() {
        TaskTranscriptCandidate candidate = candidate(
                word("叫", 100, 260),
                word("二狗子", 260, 700),
                word("回来", 720, 1_020),
                word("吃饭", 1_040, 1_360));

        List<TaskAudioRange> ranges = service.align(candidate, 1_500);

        assertEquals(List.of(new TaskAudioRange(260, 1_360)), ranges);
    }

    @Test
    void shouldKeepWholeUtteranceWithoutRecognizedPrefix() {
        TaskTranscriptCandidate candidate = candidate(
                word("二狗子", 120, 520),
                word("回来", 560, 900));

        assertEquals(List.of(new TaskAudioRange(120, 900)),
                service.align(candidate, 1_000));
    }

    @Test
    void shouldFailClosedForExplicitCorrection() {
        TaskTranscriptCandidate candidate = candidate(
                word("叫", 100, 240),
                word("二狗子", 240, 600),
                word("不对", 620, 900),
                word("老三", 920, 1_220));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.align(candidate, 1_400));

        assertEquals(ErrorCode.AUDIO_SEGMENT_UNCERTAIN, exception.errorCode());
    }

    @Test
    void shouldFailClosedForEmbeddedActionWithoutContactBoundary() {
        TaskTranscriptCandidate candidate = candidate(
                word("给", 100, 220),
                word("二狗子", 220, 580),
                word("发消息", 600, 980),
                word("回来", 1_000, 1_280));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.align(candidate, 1_500));

        assertEquals(ErrorCode.AUDIO_SEGMENT_UNCERTAIN, exception.errorCode());
    }

    @Test
    void shouldFailClosedForOverlappingTimestamps() {
        TaskTranscriptCandidate candidate = candidate(
                word("叫", 100, 400),
                word("二狗子", 300, 700));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.align(candidate, 900));

        assertEquals(ErrorCode.AUDIO_SEGMENT_UNCERTAIN, exception.errorCode());
    }

    private TaskTranscriptCandidate candidate(TaskRecognizedWord... words) {
        return new TaskTranscriptCandidate(
                "临时转写", List.of(words), 0.90D,
                TaskAsrSource.PRIMARY, "primary-v1");
    }

    private TaskRecognizedWord word(String text, int startMs, int endMs) {
        return new TaskRecognizedWord(text, startMs, endMs, 0.90D);
    }
}
