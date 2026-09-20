package com.aifriend.task.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.StringJoiner;

import org.junit.jupiter.api.Test;

import com.aifriend.task.application.TaskAsrSource;
import com.aifriend.task.application.TaskAudioAlignmentService;
import com.aifriend.task.application.TaskConversationContext;
import com.aifriend.task.application.TaskDraftRevision;
import com.aifriend.task.application.TaskInterpretationOutcome;
import com.aifriend.task.application.TaskMatchedContactView;
import com.aifriend.task.application.TaskPayload;
import com.aifriend.task.application.TaskProcessingVersionsView;
import com.aifriend.task.application.TaskRecognizedWord;
import com.aifriend.task.application.TaskSpeechRecognition;
import com.aifriend.task.application.TaskTranscriptCandidate;
import com.aifriend.task.application.TaskUnderstandingView;
import com.aifriend.task.application.TaskUtteranceInterpretationService;
import com.aifriend.task.domain.TaskIntent;
import com.aifriend.task.domain.TaskRevisionMode;

class LocalContextualTaskUnderstandingAdapterTest {

    private final LocalContextualTaskUnderstandingAdapter adapter =
            new LocalContextualTaskUnderstandingAdapter(
                    new TaskUtteranceInterpretationService(
                            new LocalKeywordTaskIntentAdapter(),
                            new TaskAudioAlignmentService()));

    @Test
    void actionCorrectionMustPreserveContactAndChangeOnlyCallType() {
        TaskDraftRevision revision = adapter.revise(
                payload(TaskIntent.VOICE_CALL, null),
                recognition(word("不对", 0, 200),
                        word("是", 220, 300),
                        word("视频通话", 320, 800)),
                900,
                TaskRevisionMode.CORRECTION);

        assertEquals(TaskIntent.VIDEO_CALL, revision.intent());
        assertEquals(TaskInterpretationOutcome.READY, revision.outcome());
        assertFalse(revision.replaceContact());
        assertFalse(revision.replaceSourceAudio());
    }

    @Test
    void contactCorrectionMustPreserveActionAndRematchOwnerContacts() {
        TaskDraftRevision revision = adapter.revise(
                payload(TaskIntent.VOICE_CALL, null),
                recognition(word("联系人不对", 0, 400),
                        word("是", 420, 500),
                        word("二女儿", 520, 900)),
                1_000,
                TaskRevisionMode.CORRECTION);

        assertEquals(TaskIntent.VOICE_CALL, revision.intent());
        assertEquals(TaskInterpretationOutcome.READY, revision.outcome());
        assertTrue(revision.replaceContact());
    }

    @Test
    void messageContentCorrectionMustUseOnlyTailAudio() {
        TaskDraftRevision revision = adapter.revise(
                payload(TaskIntent.SEND_MESSAGE, "明天回来"),
                recognition(word("内容", 0, 180),
                        word("改成", 200, 420),
                        word("后天", 440, 680),
                        word("回来", 700, 980)),
                1_100,
                TaskRevisionMode.CORRECTION);

        assertEquals(TaskIntent.SEND_MESSAGE, revision.intent());
        assertEquals(TaskInterpretationOutcome.READY, revision.outcome());
        assertEquals("后天回来", revision.messageText());
        assertTrue(revision.replaceSourceAudio());
        assertEquals(440,
                revision.recognition().effectiveAudioRanges().get(0).startMs());
        assertEquals(980,
                revision.recognition().effectiveAudioRanges().get(0).endMs());
    }

    @Test
    void unclearCorrectionMustRequestAnotherTurnWithoutGuessing() {
        TaskDraftRevision revision = adapter.revise(
                payload(TaskIntent.VOICE_CALL, null),
                recognition(word("不对", 0, 200),
                        word("我说错了", 220, 600)),
                700,
                TaskRevisionMode.CORRECTION);

        assertEquals(TaskIntent.VOICE_CALL, revision.intent());
        assertEquals(TaskInterpretationOutcome.NEEDS_RETRY, revision.outcome());
        assertFalse(revision.replaceContact());
        assertNull(revision.messageText());
    }

    private TaskPayload payload(TaskIntent intent, String messageText) {
        TaskMatchedContactView contact = new TaskMatchedContactView(
                "ct_demo1234", "老大", "老大");
        TaskUnderstandingView understanding = new TaskUnderstandingView(
                intent, contact, "给老大打电话", messageText, List.of(),
                List.of(), 0.9D, new TaskProcessingVersionsView(
                        "wugang", "dialect-v1", "asr-v1", "assist-v1",
                        "fusion-v1", "align-v1", "template-v1", "threshold-v1"));
        return new TaskPayload(
                null, understanding, List.of(), "旧摘要", Set.of(),
                null, null, null, null,
                TaskConversationContext.initial("给老大打电话", "旧摘要"));
    }

    private TaskSpeechRecognition recognition(TaskRecognizedWord... words) {
        List<TaskRecognizedWord> wordList = List.of(words);
        StringJoiner transcript = new StringJoiner(" ");
        wordList.forEach(word -> transcript.add(word.text()));
        TaskTranscriptCandidate primary = new TaskTranscriptCandidate(
                transcript.toString(), wordList, 0.9D,
                TaskAsrSource.PRIMARY, "asr-v1");
        return new TaskSpeechRecognition(
                primary.transcript(), List.of(primary), List.of(), 0.9D,
                "asr-v1", "assist-v1", "fusion-v1", "align-v1");
    }

    private TaskRecognizedWord word(String text, int startMs, int endMs) {
        return new TaskRecognizedWord(text, startMs, endMs, 0.9D);
    }
}