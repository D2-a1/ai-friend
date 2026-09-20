package com.aifriend.feature.wechat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WechatMessageNodeTreeRuleTest {
    private val container = WechatMessageNodeShape(true, false, false, false, false)

    @Test fun singleAndNestedEmptyWechatContainersAllowVisualVerification() {
        assertTrue(WechatMessageNodeTreeRule.canUseVisualFallback(listOf(container)))
        assertTrue(WechatMessageNodeTreeRule.canUseVisualFallback(listOf(container, container)))
        assertTrue(WechatMessageNodeTreeRule.canUseVisualFallback(List(5) { container }))
    }

    @Test fun partialSemanticOrInteractiveTreeCannotBeOverriddenByOcr() {
        for (nonEmpty in listOf(container.copy(hasText = true), container.copy(hasDescription = true),
            container.copy(editable = true), container.copy(clickable = true),
            container.copy(belongsToWechat = false))) {
            assertFalse(WechatMessageNodeTreeRule.canUseVisualFallback(listOf(container, nonEmpty)))
        }
        assertFalse(WechatMessageNodeTreeRule.canUseVisualFallback(emptyList()))
    }

    @Test fun nestedContainerFallbackStillRequiresUniqueVisualButtonsAndSavedPoint() {
        val emptyTree = listOf(container, container)
        val point = WechatCalibrationPixelPoint(905, 2419)
        val send = WechatVisualTextLine("发送", 789, 2380, 895, 2439)
        val cancel = WechatVisualTextLine("取消", 365, 2387, 465, 2436)
        val headerCancel = WechatVisualTextLine("取消", 1092, 191, 1190, 240)
        fun verify(lines: List<WechatVisualTextLine>, savedPoint: WechatCalibrationPixelPoint = point) =
            WechatMessageNodeTreeRule.canUseVisualFallback(emptyTree) &&
                WechatShareVisualEvidenceRule.sendButton(lines, savedPoint)
        assertTrue(verify(listOf(send, cancel, headerCancel)))
        assertFalse(verify(listOf(send, headerCancel)))
        assertFalse(verify(listOf(send, send, cancel)))
        assertFalse(verify(listOf(send, cancel, cancel)))
        assertFalse(verify(listOf(send, cancel), point.copy(x = 400)))
    }
}
