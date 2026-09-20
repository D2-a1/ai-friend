package com.aifriend.task.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TaskConversationContextTest {

    @Test
    void shouldCountOnlyUserTurnsAndRetainLatestEightEntries() {
        TaskConversationContext context = TaskConversationContext.initial(
                "给老大打电话", "给老大发起微信语音通话");

        for (int index = 0; index < 6; index++) {
            context = context
                    .appendUser(TaskConversationTurnType.USER_CORRECTION,
                            "第" + index + "次纠正")
                    .appendSystemRehearsal("第" + index + "次复述");
        }

        assertEquals(7, context.turnNumber());
        assertEquals(8, context.turns().size());
        assertEquals("第2次纠正", context.turns().get(0).text());
        assertEquals("第5次复述", context.turns().get(7).text());
    }

    @Test
    void decodedContextMustAlsoBeBoundedToLatestEightEntries() {
        java.util.List<TaskConversationTurn> turns =
                new java.util.ArrayList<>();
        for (int index = 0; index < 12; index++) {
            turns.add(new TaskConversationTurn(
                    index + 1,
                    TaskConversationTurnType.USER_CORRECTION,
                    "第" + index + "轮"));
        }

        TaskConversationContext context =
                new TaskConversationContext(12, turns);

        assertEquals(8, context.turns().size());
        assertEquals("第4轮", context.turns().get(0).text());
        assertEquals("第11轮", context.turns().get(7).text());
    }
    @Test
    void blankSystemRehearsalMustNotCreateFakeTurn() {
        TaskConversationContext context = TaskConversationContext.empty()
                .appendUser(TaskConversationTurnType.USER_RETRY, "重新说一次")
                .appendSystemRehearsal(null);

        assertEquals(1, context.turnNumber());
        assertEquals(1, context.turns().size());
        assertEquals(TaskConversationTurnType.USER_RETRY,
                context.turns().get(0).type());
    }
}