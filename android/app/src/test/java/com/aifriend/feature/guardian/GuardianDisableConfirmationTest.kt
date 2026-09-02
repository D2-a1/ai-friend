package com.aifriend.feature.guardian

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 关闭小友守护的二次确认门禁测试。 */
class GuardianDisableConfirmationTest {

    @Test
    fun firstRequestOnlyOpensConfirmation() {
        val decision = resolveGuardianDisableConfirmation(
            confirmationRequested = false,
            action = GuardianDisableConfirmationAction.REQUEST,
        )

        assertTrue(decision.confirmationRequested)
        assertFalse(decision.disableRequested)
    }

    @Test
    fun cancelKeepsGuardianRunning() {
        val decision = resolveGuardianDisableConfirmation(
            confirmationRequested = true,
            action = GuardianDisableConfirmationAction.CANCEL,
        )

        assertFalse(decision.confirmationRequested)
        assertFalse(decision.disableRequested)
    }

    @Test
    fun confirmStopsOnlyAfterRequest() {
        val withoutRequest = resolveGuardianDisableConfirmation(
            confirmationRequested = false,
            action = GuardianDisableConfirmationAction.CONFIRM,
        )
        val afterRequest = resolveGuardianDisableConfirmation(
            confirmationRequested = true,
            action = GuardianDisableConfirmationAction.CONFIRM,
        )

        assertFalse(withoutRequest.disableRequested)
        assertTrue(afterRequest.disableRequested)
        assertFalse(afterRequest.confirmationRequested)
    }
}
