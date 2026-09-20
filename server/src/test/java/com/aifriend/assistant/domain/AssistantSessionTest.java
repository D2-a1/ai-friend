package com.aifriend.assistant.domain;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.aifriend.assistant.domain.AssistantAnswer.Purpose;
import com.aifriend.assistant.domain.AssistantConversation.Turn;

class AssistantSessionTest {
    private static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");
    private static UUID id(int n) { return new UUID(0, n); }
    private static AssistantSession session() {
        return AssistantSession.create(id(1), id(2), Purpose.PUBLIC_KNOWLEDGE, "knowledge-model-v1", NOW);
    }
    private static Turn turn(int request, long version) { return new Turn(id(request), version, "怎样开启守护？", "在首页开启。"); }

    @Test void fourQuestionsCompleteWithoutDiscardingAnyHistoryAndFifthIsRejected() {
        AssistantSession current = session();
        for (int n = 0; n < 4; n++) {
            current = current.begin(n * 2L, id(10 + n), id(20 + n), NOW.plusSeconds(n * 2));
            assertThat(current.pending().orElseThrow().deadline()).isEqualTo(NOW.plusSeconds(n * 2 + 8));
            current = current.complete(n * 2L + 1, id(20 + n), Optional.of(turn(10 + n, n * 2L + 2)), NOW.plusSeconds(n * 2 + 1));
        }
        var finished = current;
        assertThat(finished.acceptedQuestions()).isEqualTo(4);
        assertThat(finished.conversation().turns()).hasSize(4);
        assertThat(finished.conversation().turns().get(0).requestId()).isEqualTo(id(10));
        assertThatThrownBy(() -> finished.begin(8, id(50), id(51), NOW.plusSeconds(8))).hasMessage("RESOURCE_LIMIT");
    }

    @Test void requestBoundaryAcceptsOneMillisecondBeforeButRejectsAtAndAfterDeadline() {
        var started = session().begin(0, id(10), id(20), NOW);
        assertThat(started.complete(1, id(20), Optional.of(turn(10, 2)), NOW.plusMillis(7999)).version()).isEqualTo(2);
        for (long millis : List.of(8000L, 8001L)) {
            assertThatThrownBy(() -> started.complete(1, id(20), Optional.of(turn(10, 2)), NOW.plusMillis(millis)))
                    .hasMessage("RESULT_STALE");
        }
    }

    @Test void idleBoundaryCannotBeRevivedByBeginningAnotherQuestion() {
        assertThat(session().begin(0, id(10), id(20), NOW.plusMillis(299999)).expiresAt()).isEqualTo(NOW.plusMillis(599999));
        for (long millis : List.of(300000L, 300001L)) {
            assertThatThrownBy(() -> session().begin(0, id(10), id(20), NOW.plusMillis(millis))).hasMessage("SESSION_EXPIRED");
        }
    }

    @Test void activeConversationStillCannotExceedFifteenMinutesAndRequestDeadlineIsClamped() {
        var value = session();
        for (int n = 0; n < 3; n++) {
            Instant at = NOW.plusSeconds(299L * (n + 1));
            value = value.begin(value.version(), id(10 + n), id(20 + n), at);
            value = value.complete(value.version(), id(20 + n), Optional.of(turn(10 + n, value.version() + 1)), at.plusMillis(1));
        }
        var last = value.begin(value.version(), id(30), id(40), NOW.plusSeconds(899));
        assertThat(last.expiresAt()).isEqualTo(NOW.plusSeconds(900));
        assertThat(last.pending().orElseThrow().deadline()).isEqualTo(last.expiresAt());
        assertThatThrownBy(() -> last.complete(last.version(), id(40), Optional.empty(), NOW.plusSeconds(900)))
                .hasMessage("SESSION_EXPIRED");
    }

    @Test void pendingQuestionRejectsConcurrentAdmissionAndWrongVersion() {
        var pending = session().begin(0, id(10), id(20), NOW);
        assertThatThrownBy(() -> pending.begin(1, id(11), id(21), NOW)).hasMessage("STALE_REQUEST");
        assertThatThrownBy(() -> pending.begin(0, id(11), id(21), NOW)).hasMessage("VERSION_MISMATCH");
        assertThat(session().version()).isZero();
    }

    @Test void completionMustMatchTokenRequestAndNextVersion() {
        var pending = session().begin(0, id(10), id(20), NOW);
        assertThatThrownBy(() -> pending.complete(1, id(21), Optional.of(turn(10, 2)), NOW)).hasMessage("RESULT_STALE");
        assertThatThrownBy(() -> pending.complete(1, id(20), Optional.of(turn(11, 2)), NOW)).hasMessage("RESULT_STALE");
        assertThatThrownBy(() -> pending.complete(1, id(20), Optional.of(turn(10, 3)), NOW)).hasMessage("RESULT_STALE");
    }

    @Test void timeoutDoesNotExtendIdleDeadlineOrPermitLateCompletionAndConsumesQuestion() {
        var pending = session().begin(0, id(10), id(20), NOW);
        assertThatThrownBy(() -> pending.timeout(1, id(20), NOW.plusMillis(7999))).hasMessage("STALE_REQUEST");
        var timedOut = pending.timeout(1, id(20), NOW.plusSeconds(8));
        assertThat(timedOut.pending()).isEmpty(); assertThat(timedOut.acceptedQuestions()).isEqualTo(1);
        assertThat(timedOut.expiresAt()).isEqualTo(pending.expiresAt());
        assertThat(timedOut.conversation().turns()).isEmpty();
        assertThatThrownBy(() -> timedOut.complete(1, id(20), Optional.of(turn(10, 2)), NOW.plusSeconds(8))).hasMessage("VERSION_MISMATCH");
    }

    @Test void failedAnswerStillConsumesOneOfFourQuestionsWithoutFabricatingHistory() {
        var completed = session().begin(0, id(10), id(20), NOW).complete(1, id(20), Optional.empty(), NOW.plusSeconds(1));
        assertThat(completed.acceptedQuestions()).isEqualTo(1); assertThat(completed.conversation().turns()).isEmpty();
    }

    @Test void closeClearsInMemoryHistoryAndPendingEligibilityAndRejectsLateResult() {
        var completed = session().begin(0, id(10), id(20), NOW).complete(1, id(20), Optional.of(turn(10, 2)), NOW.plusSeconds(1));
        var pending = completed.begin(2, id(11), id(21), NOW.plusSeconds(2));
        var closed = pending.close(3, NOW.plusSeconds(3));
        assertThat(closed.state()).isEqualTo(AssistantSession.State.CLOSED);
        assertThat(closed.pending()).isEmpty(); assertThat(closed.conversation().turns()).isEmpty();
        assertThat(closed.close(4, NOW.plusSeconds(4))).isSameAs(closed);
        assertThatThrownBy(() -> closed.complete(4, id(21), Optional.empty(), NOW.plusSeconds(4))).hasMessage("SESSION_CLOSED");
        assertThat(pending.conversation().turns()).hasSize(1); // 原快照不被偷偷修改。
    }

    @Test void closeAfterExpiryProducesExpiredNotReopenedState() {
        var expired = session().close(0, NOW.plusSeconds(300));
        assertThat(expired.state()).isEqualTo(AssistantSession.State.EXPIRED);
        assertThatThrownBy(() -> expired.begin(1, id(10), id(20), NOW.plusSeconds(301))).hasMessage("SESSION_EXPIRED");
    }

    @Test void timeRollbackAndSubMillisecondPrecisionFailClosed() {
        assertThatThrownBy(() -> session().begin(0, id(10), id(20), NOW.minusMillis(1))).hasMessage("CLOCK_UNRELIABLE");
        assertThatThrownBy(() -> session().begin(0, id(10), id(20), NOW.plusNanos(1))).hasMessage("INVALID_SESSION_TIME");
    }

    @Test void purposesAndTerminalStatesCannotCarryIncompatibleHistory() {
        var graph = new AssistantConversation(Purpose.CONTACT_GRAPH, List.of());
        assertThatThrownBy(() -> new AssistantSession(id(1), id(2), Purpose.PUBLIC_KNOWLEDGE, "p1", AssistantSession.State.OPEN,
                0, NOW, NOW, NOW.plusSeconds(300), 0, Optional.empty(), graph)).hasMessage("INVALID_ASSISTANT_SESSION");
        var history = new AssistantConversation(Purpose.PUBLIC_KNOWLEDGE, List.of(turn(10, 2)));
        assertThatThrownBy(() -> new AssistantSession(id(1), id(2), Purpose.PUBLIC_KNOWLEDGE, "p1", AssistantSession.State.CLOSED,
                2, NOW, NOW, NOW.plusSeconds(300), 1, Optional.empty(), history)).hasMessage("INVALID_ASSISTANT_SESSION");
    }

    @Test void contextIsBoundedByCodePointsBytesAndUniqueOrderedResultReferences() {
        assertThatCode(() -> new Turn(id(1), 1, "😀".repeat(500), "😀".repeat(360))).doesNotThrowAnyException();
        assertThatThrownBy(() -> new Turn(id(1), 1, "😀".repeat(501), "摘要")).hasMessage("INVALID_TEXT");
        assertThatThrownBy(() -> new Turn(id(1), 1, "问题", "😀".repeat(361))).hasMessage("INVALID_TEXT");
        assertThatThrownBy(() -> new Turn(id(1), 1, "问".repeat(501), "摘要")).hasMessage("INPUT_LIMIT");
        assertThatThrownBy(() -> new Turn(id(1), 1, "问题", "答".repeat(361))).hasMessage("INPUT_LIMIT");
        assertThatThrownBy(() -> new AssistantConversation(Purpose.PUBLIC_KNOWLEDGE, List.of(turn(1, 2), turn(1, 4))))
                .hasMessage("DUPLICATE_CONTEXT_REQUEST");
        assertThatThrownBy(() -> new AssistantConversation(Purpose.PUBLIC_KNOWLEDGE, List.of(turn(1, 4), turn(2, 2))))
                .hasMessage("UNORDERED_CONTEXT_VERSION");
        var four = new AssistantConversation(Purpose.PUBLIC_KNOWLEDGE, List.of(turn(1, 2), turn(2, 4), turn(3, 6), turn(4, 8)));
        assertThatThrownBy(() -> four.append(turn(5, 10))).hasMessage("RESOURCE_LIMIT");
    }

    @Test void conversationCopiesListAndDefaultStringsDoNotRevealQuestionsOrIdentifiers() {
        var entries = new ArrayList<>(List.of(turn(10, 2)));
        var context = new AssistantConversation(Purpose.PUBLIC_KNOWLEDGE, entries); entries.clear();
        assertThat(context.turns()).hasSize(1);
        assertThatThrownBy(() -> context.turns().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(context.toString()).doesNotContain("怎样", id(10).toString());
        assertThat(context.turns().get(0).toString()).doesNotContain("怎样", "首页", id(10).toString());
        assertThat(session().toString()).doesNotContain(id(1).toString(), id(2).toString());
    }
}
