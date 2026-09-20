package com.aifriend.feature.wechat

import org.junit.Assert.assertEquals
import org.junit.Test

class WechatShareSendNodeRuleTest {
    private val send = WechatShareButtonNode(WechatShareButtonNode.Label.SEND,
        655, 2335, 1033, 2486, true)
    private val cancel = WechatShareButtonNode(WechatShareButtonNode.Label.CANCEL,
        225, 2335, 605, 2486, true)
    private val headerCancel = cancel.copy(left = 1092, top = 191, right = 1190, bottom = 240)
    private val point = WechatCalibrationPixelPoint(905, 2419)

    @Test fun backgroundSearchCancelMustNotInvalidateUniqueDialogButtons() {
        assertEquals(WechatShareSendNodeRule.Decision.VERIFIED,
            WechatShareSendNodeRule.verify(listOf(send, cancel, headerCancel), point))
    }

    @Test fun duplicateDialogCancelAndDuplicateSendRemainRejected() {
        assertEquals(WechatShareSendNodeRule.Decision.CANCEL_NOT_UNIQUE,
            WechatShareSendNodeRule.verify(listOf(send, cancel, cancel), point))
        assertEquals(WechatShareSendNodeRule.Decision.SEND_NOT_UNIQUE,
            WechatShareSendNodeRule.verify(listOf(send, send, cancel), point))
    }

    @Test fun headerCancelAloneCannotProveShareDialog() {
        assertEquals(WechatShareSendNodeRule.Decision.CANCEL_NOT_UNIQUE,
            WechatShareSendNodeRule.verify(listOf(send, headerCancel), point))
    }

    @Test fun sendMustBeClickableAndContainActualSavedPoint() {
        assertEquals(WechatShareSendNodeRule.Decision.SEND_NOT_CLICKABLE,
            WechatShareSendNodeRule.verify(listOf(send.copy(clickable = false), cancel), point))
        assertEquals(WechatShareSendNodeRule.Decision.POINT_OUTSIDE,
            WechatShareSendNodeRule.verify(listOf(send, cancel), point.copy(x = 400)))
    }

    @Test fun crossedButtonsAndEmptyBoundsCannotAuthorizeSending() {
        for (invalid in listOf(cancel.copy(right = send.right), cancel.copy(left = cancel.right))) {
            assertEquals(WechatShareSendNodeRule.Decision.INVALID_GEOMETRY,
                WechatShareSendNodeRule.verify(listOf(send, invalid), point))
        }
        assertEquals(WechatShareSendNodeRule.Decision.INVALID_GEOMETRY,
            WechatShareSendNodeRule.verify(listOf(send.copy(bottom = send.top), cancel), point))
    }
}
