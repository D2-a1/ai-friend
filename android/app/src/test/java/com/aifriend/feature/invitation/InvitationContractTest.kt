package com.aifriend.feature.invitation

import com.aifriend.contract.model.InvitationSessionView
import java.time.OffsetDateTime
import org.junit.Assert.assertNull
import org.junit.Test

class InvitationContractTest {

    @Test
    fun inviterDisplayNameIsOptionalWhenNoFactSourceExists() {
        val view = InvitationSessionView(
            relationshipSummary =
                InvitationSessionView.RelationshipSummary.`将你添加为已绑定亲友`,
            consentPolicyVersion = "invitation-consent-v1",
            expiresAt = OffsetDateTime.parse("2026-08-09T03:30:00Z"),
            readyForConsent = true,
            csrfToken = "c".repeat(43),
        )

        assertNull(view.inviterDisplayName)
    }
}
