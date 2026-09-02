package com.aifriend.feature.wechat

import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidWechatSemanticCallUiPortTest {
    @Test
    fun contactProfileUsesOnlyFixedQueriesAndConsumesExactSelfNodeOnce() {
        val locator = FakeNode(text = "微信号：Friend_01")
        val foreignLocator = FakeNode(
            text = "微信号：Foreign_01",
            packageName = "other.package",
        )
        val exactCallEntry = FakeNode(text = "音视频通话", clickResult = true)
        val paddedCallEntry = FakeNode(text = " 音视频通话 ")
        val foreignCallEntry = FakeNode(
            text = "音视频通话",
            packageName = "other.package",
        )
        val root = FakeNode(
            queryResults = mapOf(
                "微信号" to listOf(locator, foreignLocator),
                "音视频通话" to
                    listOf(exactCallEntry, paddedCallEntry, foreignCallEntry),
            ),
        )
        val port = AndroidWechatSemanticCallUiPort(root)
        val now = OffsetDateTime.parse("2026-08-31T12:00:00Z")

        val evidence = checkNotNull(
            port.readContactProfileEvidence(LOCATOR_SALT, now),
        )

        assertEquals(listOf("微信号", "音视频通话"), root.queries)
        assertEquals(1, evidence.locatorCandidates.size)
        assertEquals(
            WechatTargetLocatorDigest.compute("Friend_01", LOCATOR_SALT),
            evidence.locatorCandidates.single().targetLocatorSha256,
        )
        assertEquals(1, evidence.callEntryCandidates.size)
        val callEntry = evidence.callEntryCandidates.single()
        assertEquals("音视频通话", callEntry.text)
        assertTrue(port.clickContactProfileCallEntry(callEntry.handle))
        assertFalse(port.clickContactProfileCallEntry(callEntry.handle))
        assertEquals(1, exactCallEntry.clickCount)
        assertEquals(0, paddedCallEntry.clickCount)
        assertTrue(locator.released)
        assertTrue(foreignLocator.released)
        assertTrue(exactCallEntry.released)
        assertTrue(paddedCallEntry.released)
        assertTrue(foreignCallEntry.released)
        port.close()
    }

    @Test
    fun clickRevalidatesWechatPackageAndCurrentNodeFacts() {
        val mutations = listOf<(FakeNode) -> Unit>(
            { node -> node.packageName = "other.package" },
            { node -> node.visibleToUser = false },
            { node -> node.enabled = false },
            { node -> node.selfClickable = false },
            { node -> node.text = "视频通话" },
        )

        mutations.forEach { mutate ->
            val action = FakeNode(text = "语音通话", clickResult = true)
            val opposite = FakeNode(text = "视频通话")
            val root = FakeNode(
                queryResults = mapOf(
                    "语音通话" to listOf(action),
                    "视频通话" to listOf(opposite),
                ),
            )
            val port = AndroidWechatSemanticCallUiPort(root)
            val evidence = checkNotNull(port.readCallChoiceEvidence(NOW))
            val handle = evidence.actionCandidates.single { it.text == "语音通话" }.handle

            mutate(action)

            assertFalse(port.clickCallChoice(handle))
            assertEquals(0, action.clickCount)
            assertTrue(action.released)
            assertTrue(opposite.released)
            port.close()
        }
    }

    @Test
    fun contactProfilePreservesVisibilityAndSelfClickabilityForCorePolicy() {
        val locator = FakeNode(text = "微信号：Friend_02", visibleToUser = false)
        val callEntry = FakeNode(
            text = "音视频通话",
            visibleToUser = false,
            enabled = false,
            selfClickable = false,
        )
        val root = FakeNode(
            queryResults = mapOf(
                "微信号" to listOf(locator),
                "音视频通话" to listOf(callEntry),
            ),
        )
        val port = AndroidWechatSemanticCallUiPort(root)

        val evidence = checkNotNull(
            port.readContactProfileEvidence(LOCATOR_SALT, NOW),
        )

        assertFalse(evidence.locatorCandidates.single().visibleToUser)
        val action = evidence.callEntryCandidates.single()
        assertFalse(action.visibleToUser)
        assertFalse(action.enabled)
        assertFalse(action.selfClickable)
        port.close()
        assertEquals(0, callEntry.clickCount)
        assertTrue(callEntry.released)
    }

    @Test
    fun choiceRequiresSeparateExactVoiceAndVideoQueriesAndClicksOnlyChosenHandle() {
        val voice = FakeNode(text = "语音通话")
        val paddedVoice = FakeNode(text = "语音通话 ")
        val video = FakeNode(text = "视频通话", clickResult = true)
        val root = FakeNode(
            queryResults = mapOf(
                "语音通话" to listOf(voice, paddedVoice),
                "视频通话" to listOf(video),
            ),
        )
        val port = AndroidWechatSemanticCallUiPort(root)

        val evidence = checkNotNull(port.readCallChoiceEvidence(NOW))

        assertEquals(listOf("语音通话", "视频通话"), root.queries)
        assertEquals(listOf("语音通话", "视频通话"), evidence.actionCandidates.map { it.text })
        val videoHandle = evidence.actionCandidates.single { it.text == "视频通话" }.handle
        assertTrue(port.clickCallChoice(videoHandle))
        assertEquals(0, voice.clickCount)
        assertEquals(0, paddedVoice.clickCount)
        assertEquals(1, video.clickCount)
        assertTrue(voice.released)
        assertTrue(paddedVoice.released)
        assertTrue(video.released)
        assertFalse(port.clickCallChoice(videoHandle))
        port.close()
    }

    @Test
    fun excessiveFixedQueryResultsFailClosedAndReleaseEveryNode() {
        val excessive = List(9) { index -> FakeNode(text = "微信号：Friend_$index") }
        val root = FakeNode(queryResults = mapOf("微信号" to excessive))
        val port = AndroidWechatSemanticCallUiPort(root)

        assertNull(port.readContactProfileEvidence(LOCATOR_SALT, NOW))
        assertTrue(excessive.all { it.released })
        assertEquals(listOf("微信号"), root.queries)
        port.close()
    }

    @Test
    fun portAllowsOnlyOneEvidenceRead() {
        val root = FakeNode(
            queryResults = mapOf(
                "语音通话" to listOf(FakeNode(text = "语音通话")),
                "视频通话" to listOf(FakeNode(text = "视频通话")),
            ),
        )
        val port = AndroidWechatSemanticCallUiPort(root)

        assertNotNull(port.readCallChoiceEvidence(NOW))
        assertNull(port.readCallChoiceEvidence(NOW.plusSeconds(1)))
        assertNull(port.readContactProfileEvidence(LOCATOR_SALT, NOW.plusSeconds(1)))
        port.close()
    }

    private class FakeNode(
        override var text: CharSequence? = null,
        override var packageName: String? = WechatSemanticCallContract.WECHAT_PACKAGE,
        override var visibleToUser: Boolean = true,
        override var enabled: Boolean = true,
        override var selfClickable: Boolean = true,
        private val clickResult: Boolean = false,
        private val queryResults: Map<String, List<FakeNode>> = emptyMap(),
    ) : WechatSemanticAccessibilityNode {
        val queries = mutableListOf<String>()
        var clickCount = 0
            private set
        var released = false
            private set

        override fun findByText(fixedText: String): List<WechatSemanticAccessibilityNode> {
            queries += fixedText
            return queryResults[fixedText].orEmpty()
        }

        override fun clickSelf(): Boolean {
            clickCount++
            return clickResult
        }

        override fun release() {
            released = true
        }
    }

    private companion object {
        const val LOCATOR_SALT = "0123456789abcdef0123456789abcdef"
        val NOW: OffsetDateTime = OffsetDateTime.parse("2026-08-31T12:00:00Z")
    }
}
