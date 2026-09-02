package com.aifriend.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 亲友邀请撤销二次确认门禁测试。 */
class InvitationRevocationConfirmationTest {

    @Test
    fun requestStoresOnlyTheSelectedInvitation() {
        val decision = resolveInvitationRevocationConfirmation(
            pendingInvitationId = null,
            action = InvitationRevocationConfirmationAction.REQUEST,
            requestedInvitationId = "invitation-one",
        )

        assertEquals("invitation-one", decision.pendingInvitationId)
        assertNull(decision.revokedInvitationId)
    }

    @Test
    fun blankRequestIsIgnored() {
        val decision = resolveInvitationRevocationConfirmation(
            pendingInvitationId = null,
            action = InvitationRevocationConfirmationAction.REQUEST,
            requestedInvitationId = "  ",
        )

        assertNull(decision.pendingInvitationId)
        assertNull(decision.revokedInvitationId)
    }

    @Test
    fun cancelClearsPendingInvitationWithoutRevoking() {
        val decision = resolveInvitationRevocationConfirmation(
            pendingInvitationId = "invitation-one",
            action = InvitationRevocationConfirmationAction.CANCEL,
        )

        assertNull(decision.pendingInvitationId)
        assertNull(decision.revokedInvitationId)
    }

    @Test
    fun confirmRevokesOnlyThePreviouslyRequestedInvitation() {
        val withoutRequest = resolveInvitationRevocationConfirmation(
            pendingInvitationId = null,
            action = InvitationRevocationConfirmationAction.CONFIRM,
        )
        val afterRequest = resolveInvitationRevocationConfirmation(
            pendingInvitationId = "invitation-one",
            action = InvitationRevocationConfirmationAction.CONFIRM,
        )

        assertNull(withoutRequest.revokedInvitationId)
        assertEquals("invitation-one", afterRequest.revokedInvitationId)
        assertNull(afterRequest.pendingInvitationId)
    }
}
