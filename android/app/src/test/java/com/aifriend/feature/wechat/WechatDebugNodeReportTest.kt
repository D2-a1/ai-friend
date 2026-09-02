package com.aifriend.feature.wechat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WechatDebugNodeReportTest {
    @Test
    fun `report contains only structural fields and rejects unsafe identifiers`() {
        val report = WechatDebugNodeReport.render(
            stage = "HOME",
            nodes = listOf(
                WechatDebugNodeRecord(
                    depth = 1,
                    siblingIndex = 2,
                    childCount = 0,
                    className = "android.widget.ImageButton",
                    viewIdResourceName = "com.tencent.mm:id/search",
                    clickable = true,
                    editable = false,
                    enabled = true,
                    visibleToUser = true,
                    hasPositiveBounds = true,
                    centerOnScreen = true,
                    supportsClickAction = true,
                    supportsSetTextAction = false,
                    inputFocused = false,
                ),
                WechatDebugNodeRecord(
                    depth = 2,
                    siblingIndex = 0,
                    childCount = 0,
                    className = "unsafe\ncontact-name",
                    viewIdResourceName = "bad|id",
                    clickable = false,
                    editable = false,
                    enabled = true,
                    visibleToUser = true,
                    hasPositiveBounds = false,
                    centerOnScreen = false,
                    supportsClickAction = false,
                    supportsSetTextAction = false,
                    inputFocused = false,
                ),
            ),
            roles = mapOf(
                "SEARCH" to listOf(
                    WechatDebugRoleCandidate(
                        "SEARCH",
                        WechatDebugNodeRecord(
                            depth = null,
                            siblingIndex = null,
                            childCount = 0,
                            className = "android.widget.ImageButton",
                            viewIdResourceName = "com.tencent.mm:id/search",
                            clickable = true,
                            editable = false,
                            enabled = true,
                            visibleToUser = true,
                            hasPositiveBounds = true,
                            centerOnScreen = true,
                            supportsClickAction = true,
                            supportsSetTextAction = false,
                            inputFocused = false,
                        ),
                    ),
                ),
            ),
        )

        assertTrue(report.contains("AI_FRIEND_WECHAT_NODE_PROBE_V1"))
        assertTrue(
            report.contains(
                "NODE|1|2|0|android.widget.ImageButton|com.tencent.mm:id/search",
            ),
        )
        assertTrue(
            report.contains(
                "ROLE|SEARCH|android.widget.ImageButton|com.tencent.mm:id/search",
            ),
        )
        assertTrue(report.contains("|true|true|true|false|false"))
        assertTrue(report.contains("NODE|2|0|0|<invalid>|<invalid>"))
        assertFalse(report.contains("contact-name"))
    }

    @Test
    fun `report rejects unsafe stage text`() {
        val report = WechatDebugNodeReport.render(
            stage = "HOME\nsecret",
            nodes = emptyList(),
            roles = emptyMap(),
        )

        assertTrue(report.contains("stage=<invalid>"))
        assertFalse(report.contains("secret"))
    }
}
