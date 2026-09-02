package com.aifriend.feature.task

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 任务取消门闩的代次隔离与迟到响应测试。 */
class TaskExecutionGateTest {

    @Test
    fun blockingCurrentTokenImmediatelyPreventsContinuation() {
        val gate = TaskExecutionGate()
        val token = gate.open()

        assertTrue(gate.canContinue(token))
        assertTrue(gate.block(token))

        assertTrue(gate.isCurrent(token))
        assertFalse(gate.canContinue(token))
    }

    @Test
    fun openingNewGenerationInvalidatesLateResponse() {
        val gate = TaskExecutionGate()
        val oldToken = gate.open()
        val currentToken = gate.open()

        assertFalse(gate.isCurrent(oldToken))
        assertFalse(gate.canContinue(oldToken))
        assertTrue(gate.canContinue(currentToken))
    }

    @Test
    fun staleCancellationCannotBlockNewGeneration() {
        val gate = TaskExecutionGate()
        val oldToken = gate.open()
        val currentToken = gate.open()

        assertFalse(gate.block(oldToken))
        assertTrue(gate.canContinue(currentToken))
    }
}
