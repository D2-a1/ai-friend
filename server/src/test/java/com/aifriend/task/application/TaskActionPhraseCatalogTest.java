package com.aifriend.task.application;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.aifriend.task.domain.TaskIntent;

class TaskActionPhraseCatalogTest {

    @Test
    void faXinXiIsAvailableToParsingAndRoutineLearning() {
        assertTrue(TaskActionPhraseCatalog.messagePhrases().contains("发信息"));
        assertTrue(TaskActionPhraseCatalog.routineLearningPhrases(TaskIntent.SEND_MESSAGE)
                .contains("发信息"));
    }
}
