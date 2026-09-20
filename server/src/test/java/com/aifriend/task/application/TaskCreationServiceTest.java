package com.aifriend.task.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifriend.task.domain.TaskAction;
import com.aifriend.task.domain.TaskIntent;
import com.aifriend.task.domain.TaskState;

class TaskCreationServiceTest {

    @Test
    void shouldRequireSelectionForSingleAmbiguousCandidate() {
        TaskContactCandidate ambiguous = candidate("AMBIGUOUS");

        TaskState state = TaskCreationService.initialState(
                TaskInterpretationOutcome.READY, List.of(ambiguous));

        assertEquals(TaskState.AWAITING_SELECTION, state);
    }

    @Test
    void shouldAllowConfirmationForSingleUniqueCandidate() {
        TaskContactCandidate unique = candidate("UNIQUE");

        TaskState state = TaskCreationService.initialState(
                TaskInterpretationOutcome.READY, List.of(unique));

        assertEquals(TaskState.AWAITING_CONFIRMATION, state);
    }

    @Test
    void unresolvedCorrectionMustRequireRetry() {
        TaskState state = TaskCreationService.initialState(
                TaskInterpretationOutcome.NEEDS_RETRY, List.of());

        assertEquals(TaskState.NEEDS_RETRY, state);
    }

    @Test
    void missingMessageContentMustRequestTargetedRepeat() {
        TaskState state = TaskCreationService.initialState(
                TaskInterpretationOutcome.NEEDS_CONTENT_REPEAT, List.of());

        assertEquals(TaskState.NEEDS_CONTENT_REPEAT, state);
        assertEquals(Set.of(TaskAction.RETRY, TaskAction.CANCEL),
                TaskCreationService.initialActions(state, TaskIntent.SEND_MESSAGE));
        assertTrue(TaskCreationService.shouldMatchContacts(
                TaskInterpretationOutcome.NEEDS_CONTENT_REPEAT,
                TaskIntent.SEND_MESSAGE));
        assertTrue(TaskCreationService.shouldMatchContacts(
                TaskInterpretationOutcome.READY, TaskIntent.SEND_MESSAGE));
    }

    @Test
    void cancellationMustFinishWithoutContactCandidate() {
        TaskState state = TaskCreationService.initialState(
                TaskInterpretationOutcome.CANCELLED, List.of());

        assertEquals(TaskState.CANCELLED, state);
    }

    private TaskContactCandidate candidate(String scoreBand) {
        UUID contactId = UUID.randomUUID();
        return new TaskContactCandidate(
                UUID.randomUUID().toString(), contactId,
                "ct_" + contactId.toString().replace("-", ""),
                "张三", "二狗子", scoreBand, 1);
    }
}
