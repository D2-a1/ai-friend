package com.aifriend.core.design

import com.aifriend.core.settings.FontLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 适老主题字体下限和放大档位测试。 */
class AiFriendThemeTest {

    @Test
    fun largeAndLargerTypographyNeverExposeSmallBodyText() {
        val large = elderFriendlyTypography(FontLevel.LARGE)
        val larger = elderFriendlyTypography(FontLevel.LARGER)

        assertEquals(22f, large.bodyLarge.fontSize.value)
        assertEquals(26f, larger.bodyLarge.fontSize.value)
        assertTrue(larger.headlineLarge.fontSize > large.headlineLarge.fontSize)
        assertTrue(larger.labelLarge.lineHeight > large.labelLarge.lineHeight)
    }
}
