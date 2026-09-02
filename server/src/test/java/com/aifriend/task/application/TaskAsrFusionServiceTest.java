package com.aifriend.task.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;

class TaskAsrFusionServiceTest {

    private final TaskAsrFusionService service = new TaskAsrFusionService();

    @Test
    void shouldKeepPrimaryCandidateWhenTranscriptsAgree() {
        TaskAsrEngineResult primary = result(
                "叫二狗子回来", TaskAsrSource.PRIMARY, "primary-v1", 0.90D);
        TaskAsrEngineResult assist = result(
                "叫 二狗子 回来", TaskAsrSource.MANDARIN_ASSIST,
                "assist-v1", 0.80D);

        TaskAsrFusionResult fused = service.fuse(primary, assist);

        assertEquals(TaskAsrSource.PRIMARY, fused.selectedCandidate().source());
        assertEquals(2, fused.evidenceCandidates().size());
        assertEquals(0.80D, fused.confidence());
    }

    @Test
    void shouldNotAllowMandarinAssistToReplacePrimaryText() {
        TaskAsrEngineResult primary = result(
                "叫二狗子回来", TaskAsrSource.PRIMARY, "primary-v1", 0.70D);
        TaskAsrEngineResult assist = result(
                "叫老三回来", TaskAsrSource.MANDARIN_ASSIST,
                "assist-v1", 0.99D);

        TaskAsrFusionResult fused = service.fuse(primary, assist);

        assertEquals("叫二狗子回来", fused.selectedCandidate().transcript());
        assertEquals(0.70D, fused.confidence());
    }

    @Test
    void shouldFailClosedWhenActionEvidenceConflicts() {
        TaskAsrEngineResult primary = result(
                "给二狗子打电话", TaskAsrSource.PRIMARY, "primary-v1", 0.90D);
        TaskAsrEngineResult assist = result(
                "给二狗子发消息", TaskAsrSource.MANDARIN_ASSIST,
                "assist-v1", 0.90D);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.fuse(primary, assist));

        assertEquals(ErrorCode.AUDIO_SEGMENT_UNCERTAIN, exception.errorCode());
    }

    private TaskAsrEngineResult result(
            String transcript,
            TaskAsrSource source,
            String modelVersion,
            double confidence) {
        TaskTranscriptCandidate candidate = new TaskTranscriptCandidate(
                transcript,
                List.of(new TaskRecognizedWord(transcript, 100, 800, confidence)),
                confidence,
                source,
                modelVersion);
        return new TaskAsrEngineResult(List.of(candidate), modelVersion);
    }
}
