package com.aifriend.app.ui

import com.aifriend.app.ui.components.toChineseUiMessage
import com.aifriend.app.ui.navigation.MainDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 页面中文显示策略的回归测试。 */
class UiLanguagePolicyTest {

    @Test
    fun `中文业务提示保持原文`() {
        assertEquals(
            "操作没有完成，请稍后重试。",
            "操作没有完成，请稍后重试。".toChineseUiMessage("备用提示"),
        )
    }

    @Test
    fun `含英文技术信息时使用中文备用提示`() {
        assertEquals(
            "连接失败，请稍后重试。",
            "请求失败，状态码为五百，server error".toChineseUiMessage("连接失败，请稍后重试。"),
        )
    }

    @Test
    fun `空提示使用中文备用提示`() {
        assertEquals(
            "操作没有完成。",
            "   ".toChineseUiMessage("操作没有完成。"),
        )
    }

    @Test
    fun `顶级导航固定为三个中文页面`() {
        assertEquals(3, MainDestination.entries.size)
        assertEquals(setOf("首页", "家人", "我的"), MainDestination.entries.map { it.label }.toSet())
        assertTrue(MainDestination.entries.map { it.route }.toSet().size == 3)
        MainDestination.entries.forEach { destination ->
            assertFalse(destination.label.any { it.isLatinLetter() })
            assertFalse(destination.shortLabel.any { it.isLatinLetter() })
        }
    }

    private fun Char.isLatinLetter(): Boolean = this in 'A'..'Z' || this in 'a'..'z'
}
