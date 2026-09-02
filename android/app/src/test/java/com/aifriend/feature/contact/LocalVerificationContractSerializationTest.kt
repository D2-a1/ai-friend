package com.aifriend.feature.contact

import com.aifriend.contract.model.LocalVerificationRequest
import com.aifriend.contract.model.WechatPageType
import com.aifriend.core.network.NetworkModule
import java.time.OffsetDateTime
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalVerificationContractSerializationTest {

    @Test
    fun requestSerializesWithRegisteredWechatPageTypeAndNoClientDigest() {
        val request = LocalVerificationRequest(
            stableLocator = "test_stable_locator",
            pageType = WechatPageType.CONTACT_PROFILE,
            friendConfirmed = true,
            locatorObservationCount = 1,
            locatorUnique = true,
            wechatVersion = "8.0.56",
            ruleVersion = "wechat-contact-v1",
            verifiedAt = OffsetDateTime.parse("2026-08-09T03:00:00Z"),
            expectedContactVersion = 1,
            currentRemark = null,
        )

        val encoded = NetworkModule.provideJson().encodeToString(request)

        assertTrue(encoded.contains("\"pageType\":\"CONTACT_PROFILE\""))
        assertTrue(encoded.contains("\"locatorObservationCount\":1"))
        assertFalse(encoded.contains("locatorDigest"))
    }
}
