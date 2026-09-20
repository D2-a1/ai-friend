package com.aifriend.feature.wechat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WechatVisualTextEvidenceRuleTest {
    @Test
    fun contactProfileAcceptsOnlyStrictInlineLocatorAndExactCallEntry() {
        val observation = WechatVisualTextEvidenceRule.contactProfile(
            listOf(
                line("联系人 Jack", 100, 200, 500, 280),
                line("微信号：Friend_01", 100, 400, 700, 480),
                line("音视频通话", 100, 1_500, 600, 1_590),
                line("音视频通话推荐", 100, 1_700, 700, 1_790),
            ),
        )

        try {
            assertEquals(1, observation.locatorCandidates.size)
            assertTrue(observation.locatorCandidates.single().contentEquals("Friend_01".toCharArray()))
            assertEquals(1, observation.callEntryMatchCount)
        } finally {
            observation.clear()
        }
    }

    @Test
    fun contactProfileAcceptsSameRowSplitLocatorButRejectsUnrelatedAsciiLine() {
        val observation = WechatVisualTextEvidenceRule.contactProfile(
            listOf(
                line("微信号：", 100, 400, 280, 480),
                line("Friend_02", 300, 400, 650, 480),
                line("Another_99", 100, 800, 600, 880),
                line("音视频通话", 100, 1_500, 600, 1_590),
            ),
        )

        try {
            assertEquals(1, observation.locatorCandidates.size)
            assertTrue(observation.locatorCandidates.single().contentEquals("Friend_02".toCharArray()))
        } finally {
            observation.clear()
        }
    }

    @Test
    fun contactProfileDoesNotTreatLooseOrConfusableTextAsIdentity() {
        val observation = WechatVisualTextEvidenceRule.contactProfile(
            listOf(
                line("微信号 Friend.01", 100, 400, 700, 480),
                line("微 信 号：Friend_01", 100, 500, 700, 580),
                line("音视频通话推荐", 100, 1_500, 600, 1_590),
            ),
        )

        try {
            assertTrue(observation.locatorCandidates.isEmpty())
            assertEquals(0, observation.callEntryMatchCount)
        } finally {
            observation.clear()
        }
    }

    @Test
    fun contactProfileAcceptsOnlyWhitespaceOrSameRowSplitForFixedCallEntry() {
        val spaced = WechatVisualTextEvidenceRule.contactProfile(
            listOf(line(" 音视频  通话 ", 100, 1_500, 600, 1_590)),
        )
        val split = WechatVisualTextEvidenceRule.contactProfile(
            listOf(
                line("音视频", 100, 1_500, 310, 1_590),
                line("通话", 320, 1_500, 470, 1_590),
            ),
        )
        val differentRows = WechatVisualTextEvidenceRule.contactProfile(
            listOf(
                line("音视频", 100, 1_500, 310, 1_590),
                line("通话", 320, 1_700, 470, 1_790),
            ),
        )
        val extraText = WechatVisualTextEvidenceRule.contactProfile(
            listOf(line("音视频 通话 推荐", 100, 1_500, 700, 1_590)),
        )
        val decorated = WechatVisualTextEvidenceRule.contactProfile(
            listOf(line("☎音视频通话›", 100, 1_500, 700, 1_590)),
        )
        val iconMisreadAsLetter = WechatVisualTextEvidenceRule.contactProfile(
            listOf(line("A音视频通话", 100, 1_500, 700, 1_590)),
        )
        val iconMisreadAsDigit = WechatVisualTextEvidenceRule.contactProfile(
            listOf(line("音视频通话2", 100, 1_500, 700, 1_590)),
        )
        val tooManyDecorations = WechatVisualTextEvidenceRule.contactProfile(
            listOf(line("AB音视频通话2", 100, 1_500, 700, 1_590)),
        )

        assertEquals(1, spaced.callEntryMatchCount)
        assertEquals(1, split.callEntryMatchCount)
        assertEquals(0, differentRows.callEntryMatchCount)
        assertEquals(0, extraText.callEntryMatchCount)
        assertEquals(1, decorated.callEntryMatchCount)
        assertEquals(1, iconMisreadAsLetter.callEntryMatchCount)
        assertEquals(1, iconMisreadAsDigit.callEntryMatchCount)
        assertEquals(0, tooManyDecorations.callEntryMatchCount)
    }

    @Test
    fun callChoiceRequiresOneExactVoiceAndVideoLabel() {
        val observation = WechatVisualTextEvidenceRule.callChoice(
            listOf(
                line("语音通话", 100, 800, 500, 900),
                line("视频通话", 100, 1_000, 500, 1_100),
                line("视频通话推荐", 100, 1_200, 700, 1_300),
            ),
        )

        assertEquals(1, observation.voiceMatchCount)
        assertEquals(1, observation.videoMatchCount)
    }

    @Test
    fun callChoiceAcceptsWhitespaceButRejectsAdditionalWords() {
        val observation = WechatVisualTextEvidenceRule.callChoice(
            listOf(
                line("语音 通话", 100, 800, 500, 900),
                line("视频 通话", 100, 1_000, 500, 1_100),
                line("视频 通话 推荐", 100, 1_200, 700, 1_300),
            ),
        )

        assertEquals(1, observation.voiceMatchCount)
        assertEquals(1, observation.videoMatchCount)
    }

    @Test
    fun visualFallbackIsAllowedOnlyWhenNodeEvidenceIsCompletelyEmpty() {
        val capturedAt = java.time.OffsetDateTime.parse("2026-09-06T00:00:00Z")
        val locatorOnly = WechatSemanticContactProfileEvidence(
            locatorCandidates = listOf(WechatSemanticLocatorEvidence("a".repeat(64), true)),
            callEntryCandidates = emptyList(),
            capturedAt = capturedAt,
        )
        val actionOnly = WechatSemanticContactProfileEvidence(
            locatorCandidates = emptyList(),
            callEntryCandidates = listOf(
                WechatSemanticActionNodeEvidence(0, "音视频通话", true, true, false),
            ),
            capturedAt = capturedAt,
        )
        val emptyProfile = WechatSemanticContactProfileEvidence(
            emptyList(),
            emptyList(),
            capturedAt,
        )
        val emptyChoice = WechatSemanticCallChoiceEvidence(emptyList(), capturedAt)
        val partialChoice = WechatSemanticCallChoiceEvidence(
            listOf(WechatSemanticActionNodeEvidence(0, "语音通话", true, true, false)),
            capturedAt,
        )

        assertTrue(WechatVisualFallbackPolicy.allows(null as WechatSemanticContactProfileEvidence?))
        assertTrue(WechatVisualFallbackPolicy.allows(emptyProfile))
        assertFalse(WechatVisualFallbackPolicy.allows(locatorOnly))
        assertFalse(WechatVisualFallbackPolicy.allows(actionOnly))
        assertTrue(WechatVisualFallbackPolicy.allows(null as WechatSemanticCallChoiceEvidence?))
        assertTrue(WechatVisualFallbackPolicy.allows(emptyChoice))
        assertFalse(WechatVisualFallbackPolicy.allows(partialChoice))
    }

    private fun line(text: String, left: Int, top: Int, right: Int, bottom: Int) =
        WechatVisualTextLine(text, left, top, right, bottom)
}
