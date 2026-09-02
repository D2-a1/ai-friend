package com.aifriend.app.ui.profile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 我的页退出本机登录二次确认门禁测试。 */
class ProfileExitConfirmationTest {

    @Test
    fun firstRequestOnlyOpensConfirmation() {
        val decision = resolveProfileExitConfirmation(
            confirmationRequested = false,
            action = ProfileExitConfirmationAction.REQUEST,
        )

        assertTrue(decision.confirmationRequested)
        assertFalse(decision.exitRequested)
    }

    @Test
    fun cancelKeepsCurrentSession() {
        val decision = resolveProfileExitConfirmation(
            confirmationRequested = true,
            action = ProfileExitConfirmationAction.CANCEL,
        )

        assertFalse(decision.confirmationRequested)
        assertFalse(decision.exitRequested)
    }

    @Test
    fun confirmExitsOnlyAfterRequest() {
        val withoutRequest = resolveProfileExitConfirmation(
            confirmationRequested = false,
            action = ProfileExitConfirmationAction.CONFIRM,
        )
        val afterRequest = resolveProfileExitConfirmation(
            confirmationRequested = true,
            action = ProfileExitConfirmationAction.CONFIRM,
        )

        assertFalse(withoutRequest.exitRequested)
        assertTrue(afterRequest.exitRequested)
        assertFalse(afterRequest.confirmationRequested)
    }
}
