package com.aifriend.app.ui.family

import com.aifriend.contract.model.Contact
import com.aifriend.contract.model.ContactStatus
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/** 家人页联系人状态摘要测试。 */
class FamilyContactSummaryTest {

    @Test
    fun summarizesOnlyNonSensitiveContactStates() {
        val summary = listOf(
            contact("active", ContactStatus.ACTIVE),
            contact("alias", ContactStatus.ACTIVE_NO_ALIAS),
            contact("verify", ContactStatus.PENDING_LOCAL_VERIFY),
            contact("reverify", ContactStatus.REVERIFY_REQUIRED),
            contact("revoked", ContactStatus.REVOKED),
        ).toFamilyContactSummary()

        assertEquals(4, summary.totalCount)
        assertEquals(1, summary.activeCount)
        assertEquals(1, summary.aliasRequiredCount)
        assertEquals(1, summary.localVerificationRequiredCount)
        assertEquals(1, summary.reverificationRequiredCount)
    }

    @Test
    fun emptyListProducesZeroSummary() {
        assertEquals(
            FamilyContactSummary(0, 0, 0, 0, 0),
            emptyList<Contact>().toFamilyContactSummary(),
        )
    }

    private fun contact(id: String, status: ContactStatus): Contact = Contact(
        id = id,
        status = status,
        aliasCount = 0,
        version = 1,
        createdAt = NOW,
        updatedAt = NOW,
        aliases = emptyList(),
    )

    private companion object {
        val NOW: OffsetDateTime = OffsetDateTime.parse("2026-08-29T08:00:00Z")
    }
}
