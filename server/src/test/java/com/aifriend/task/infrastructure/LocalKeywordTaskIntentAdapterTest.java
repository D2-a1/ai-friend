package com.aifriend.task.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.task.domain.TaskIntent;

class LocalKeywordTaskIntentAdapterTest {

    private final LocalKeywordTaskIntentAdapter adapter =
            new LocalKeywordTaskIntentAdapter();

    @Test
    void cancellationMustOverrideMessageKeyword() {
        assertEquals(TaskIntent.CANCEL,
                adapter.parse("不要了，不要给女儿发消息").intent());
    }

    @Test
    void correctionMustOverrideCallKeyword() {
        assertEquals(TaskIntent.CORRECT,
                adapter.parse("说错了，不是打电话").intent());
    }

    @Test
    void ordinaryCommandWithoutExplicitActionMustBecomeMessage() {
        var decision = adapter.parse("叫二狗子回来");

        assertEquals(TaskIntent.SEND_MESSAGE, decision.intent());
        assertFalse(decision.requiresRetry());
    }

    @Test
    void shouldRecognizeVideoAndVoiceCalls() {
        assertEquals(TaskIntent.VIDEO_CALL,
                adapter.parse("给女儿打视频").intent());
        assertEquals(TaskIntent.VOICE_CALL,
                adapter.parse("给女儿打电话").intent());
    }

    @Test
    void conflictingActionsMustRequireRetry() {
        var decision = adapter.parse("告诉女儿打电话");

        assertEquals(TaskIntent.HELP, decision.intent());
        assertTrue(decision.requiresRetry());
    }

    @Test
    void lowInformationAnswerMustRequireRetry() {
        var decision = adapter.parse("对");

        assertEquals(TaskIntent.HELP, decision.intent());
        assertTrue(decision.requiresRetry());
    }

    @Test
    void blankTranscriptMustFailClosed() {
        assertThrows(BusinessException.class, () -> adapter.parse("  "));
    }
}
