package com.aifriend.task.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.StringJoiner;

import org.junit.jupiter.api.Test;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.task.domain.TaskIntent;
import com.aifriend.task.infrastructure.LocalKeywordTaskIntentAdapter;

class TaskUtteranceInterpretationServiceTest {

    private final TaskUtteranceInterpretationService service =
            new TaskUtteranceInterpretationService(
                    new LocalKeywordTaskIntentAdapter(),
                    new TaskAudioAlignmentService());

    @Test
    void shouldAlignOrdinaryMessageAfterIntentParsing() {
        TaskSpeechRecognition recognition = recognition(
                word("叫", 100, 240),
                word("二狗子", 260, 600),
                word("回来", 620, 900));

        TaskUtteranceInterpretation result = service.interpret(recognition, 1_000);

        assertEquals(TaskIntent.SEND_MESSAGE, result.intent());
        assertEquals(TaskInterpretationOutcome.READY, result.outcome());
        assertEquals(List.of(new TaskAudioRange(260, 900)),
                result.recognition().effectiveAudioRanges());
        assertEquals("二狗子回来", result.messageText());
        assertEquals(List.of(), result.corrections());
    }

    @Test
    void shouldCaptureExactlyOneExplicitCallActionRangeForLearning() {
        TaskUtteranceInterpretation result = service.interpret(recognition(
                word("给", 100, 220),
                word("女儿", 240, 520),
                word("打电话", 540, 860)), 1_000);

        assertEquals(new RoutineCommandLearningEvidence(
                TaskIntent.VOICE_CALL, new TaskAudioRange(540, 860)),
                result.routineCommandLearningEvidence());
    }

    @Test
    void implicitMessageVerbMustNotBecomeLearningEvidence() {
        TaskUtteranceInterpretation result = service.interpret(recognition(
                word("叫", 100, 240),
                word("二狗子", 260, 600),
                word("回来", 620, 900)), 1_000);

        assertNull(result.routineCommandLearningEvidence());
    }

    @Test
    void correctionMustOnlyLearnReplacementActionRange() {
        TaskUtteranceInterpretation result = service.interpret(recognition(
                word("给", 100, 220),
                word("女儿", 240, 520),
                word("打电话", 540, 860),
                word("不对", 880, 1_120),
                word("给", 1_140, 1_260),
                word("女儿", 1_280, 1_560),
                word("打视频", 1_580, 1_940)), 2_100);

        assertEquals(new RoutineCommandLearningEvidence(
                TaskIntent.VIDEO_CALL, new TaskAudioRange(1_580, 1_940)),
                result.routineCommandLearningEvidence());
    }

    @Test
    void multipleExplicitActionPhrasesMustNotProduceLearningEvidence() {
        TaskUtteranceInterpretation result = service.interpret(recognition(
                word("给", 100, 220),
                word("女儿", 240, 520),
                word("打电话", 540, 860),
                word("再", 880, 980),
                word("打电话", 1_000, 1_320)), 1_500);

        assertNull(result.routineCommandLearningEvidence());
    }

    @Test
    void shouldNotProduceMessageAudioRangeForCall() {
        TaskUtteranceInterpretation result = service.interpret(recognition(
                word("给", 100, 220),
                word("女儿", 240, 560),
                word("打电话", 580, 920)), 1_000);

        assertEquals(TaskIntent.VOICE_CALL, result.intent());
        assertEquals(List.of(), result.recognition().effectiveAudioRanges());
    }

    @Test
    void shouldUseOnlyReplacementContactAfterSplitCorrectionMarker() {
        TaskUtteranceInterpretation result = service.interpret(recognition(
                word("给", 100, 220),
                word("二狗子", 240, 560),
                word("打电话", 580, 920),
                word("不", 940, 1_060),
                word("对", 1_080, 1_200),
                word("给", 1_220, 1_340),
                word("老三", 1_360, 1_680),
                word("打电话", 1_700, 2_040)), 2_200);

        assertEquals(TaskIntent.VOICE_CALL, result.intent());
        assertEquals("给 老三 打电话", result.recognition().transcript());
        assertFalse(result.recognition().transcript().contains("二狗子"));
        assertEquals(List.of(), result.recognition().effectiveAudioRanges());
        assertEquals(List.of(new TaskCorrectionView(
                "CONTACT", new TaskAudioRange(100, 920),
                new TaskAudioRange(1_220, 2_040))), result.corrections());
    }

    @Test
    void shouldMarkChangedCallTypeAsActionCorrection() {
        TaskUtteranceInterpretation result = service.interpret(recognition(
                word("给", 100, 220),
                word("女儿", 240, 520),
                word("打电话", 540, 860),
                word("不对", 880, 1_120),
                word("给", 1_140, 1_260),
                word("女儿", 1_280, 1_560),
                word("打视频", 1_580, 1_940)), 2_100);

        assertEquals(TaskIntent.VIDEO_CALL, result.intent());
        assertEquals("ACTION", result.corrections().get(0).slot());
    }

    @Test
    void shouldKeepOnlyContinuousReplacementRangeForMessageCorrection() {
        TaskUtteranceInterpretation result = service.interpret(recognition(
                word("告诉", 100, 320),
                word("二狗子", 340, 680),
                word("明天回来", 700, 1_180),
                word("不对", 1_200, 1_440),
                word("告诉", 1_460, 1_680),
                word("老三", 1_700, 2_020),
                word("后天回来", 2_040, 2_520)), 2_700);

        assertEquals(TaskIntent.SEND_MESSAGE, result.intent());
        assertEquals("告诉 老三 后天回来", result.recognition().transcript());
        assertEquals(List.of(new TaskAudioRange(1_700, 2_520)),
                result.recognition().effectiveAudioRanges());
        assertEquals("老三后天回来", result.messageText());
        assertEquals(List.of(new TaskCorrectionView(
                "CONTENT", new TaskAudioRange(100, 1_180),
                new TaskAudioRange(1_460, 2_520))), result.corrections());
    }

    @Test
    void incompleteCorrectionMustRequireRetryWithoutGuessing() {
        TaskUtteranceInterpretation result = service.interpret(recognition(
                word("给", 100, 220),
                word("二狗子", 240, 560),
                word("打电话", 580, 920),
                word("不对", 940, 1_180),
                word("老三", 1_200, 1_500)), 1_700);

        assertEquals(TaskIntent.CORRECT, result.intent());
        assertEquals(TaskInterpretationOutcome.NEEDS_RETRY, result.outcome());
        assertEquals(List.of(), result.corrections());
        assertEquals(List.of(), result.recognition().effectiveAudioRanges());
    }

    @Test
    void contactOnlyMessageMustRequestContentRepeat() {
        TaskUtteranceInterpretation result = service.interpret(recognition(
                word("给", 100, 220),
                word("老三", 240, 560)), 700);

        assertEquals(TaskIntent.SEND_MESSAGE, result.intent());
        assertEquals(TaskInterpretationOutcome.NEEDS_CONTENT_REPEAT,
                result.outcome());
        assertEquals(List.of(), result.recognition().effectiveAudioRanges());
        assertNull(result.messageText());
    }

    @Test
    void multipleCorrectionsMustRequireRetryWithoutUsingLastClause() {
        TaskUtteranceInterpretation result = service.interpret(recognition(
                word("给女儿打电话", 100, 600),
                word("不对", 620, 840),
                word("给老三打电话", 860, 1_360),
                word("不对", 1_380, 1_600),
                word("给二狗子打电话", 1_620, 2_120)), 2_300);

        assertEquals(TaskIntent.CORRECT, result.intent());
        assertEquals(List.of(), result.corrections());
    }

    @Test
    void cancellationMustOverrideCorrection() {
        TaskUtteranceInterpretation result = service.interpret(recognition(
                word("给女儿打电话", 100, 600),
                word("不对", 620, 840),
                word("取消", 860, 1_100)), 1_300);

        assertEquals(TaskIntent.CANCEL, result.intent());
        assertEquals(TaskInterpretationOutcome.CANCELLED, result.outcome());
        assertEquals(List.of(), result.recognition().effectiveAudioRanges());
    }

    @Test
    void conflictingActionsWithoutCorrectionMustRequireRetry() {
        TaskUtteranceInterpretation result = service.interpret(recognition(
                word("告诉", 100, 300),
                word("女儿", 320, 620),
                word("打电话", 640, 980)), 1_100);

        assertEquals(TaskIntent.HELP, result.intent());
        assertEquals(TaskInterpretationOutcome.NEEDS_RETRY, result.outcome());
        assertEquals(List.of(), result.recognition().effectiveAudioRanges());
    }

    @Test
    void invalidWordTimestampMustFailClosed() {
        TaskSpeechRecognition recognition = recognition(
                word("叫", 100, 400),
                word("二狗子", 300, 700));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.interpret(recognition, 900));

        assertEquals(ErrorCode.AUDIO_SEGMENT_UNCERTAIN, exception.errorCode());
    }

    private TaskSpeechRecognition recognition(TaskRecognizedWord... words) {
        List<TaskRecognizedWord> wordList = List.of(words);
        StringJoiner transcript = new StringJoiner(" ");
        wordList.forEach(word -> transcript.add(word.text()));
        TaskTranscriptCandidate primary = new TaskTranscriptCandidate(
                transcript.toString(), wordList, 0.90D,
                TaskAsrSource.PRIMARY, "primary-v1");
        return new TaskSpeechRecognition(
                primary.transcript(), List.of(primary), List.of(), 0.90D,
                "primary-v1", "assist-v1", "fusion-v1", "align-v1");
    }

    private TaskRecognizedWord word(String text, int startMs, int endMs) {
        return new TaskRecognizedWord(text, startMs, endMs, 0.90D);
    }
}
