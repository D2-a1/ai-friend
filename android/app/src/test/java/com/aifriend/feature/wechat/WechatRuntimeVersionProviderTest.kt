package com.aifriend.feature.wechat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 微信运行时版本 token 边界测试。 */
class WechatRuntimeVersionProviderTest {
    @Test
    fun acceptsTypicalVersionAndExactMaximumLength() {
        assertEquals("8.0.76", sanitizeWechatRuntimeVersionToken("8.0.76"))
        assertEquals("v".repeat(100), sanitizeWechatRuntimeVersionToken("v".repeat(100)))
    }

    @Test
    fun rejectsMissingEmptyAndOversizedVersions() {
        assertNull(sanitizeWechatRuntimeVersionToken(null))
        assertNull(sanitizeWechatRuntimeVersionToken(""))
        assertNull(sanitizeWechatRuntimeVersionToken("v".repeat(101)))
    }

    @Test
    fun rejectsColonWithoutNormalizingTheValue() {
        assertNull(sanitizeWechatRuntimeVersionToken("8.0:76"))
        assertNull(sanitizeWechatRuntimeVersionToken(":8.0.76"))
    }

    @Test
    fun rejectsAsciiAndUnicodeWhitespace() {
        listOf(
            " 8.0.76",
            "8.0.76 ",
            "8.0\t76",
            "8.0\n76",
            "8.0\u00A076",
            "8.0\u300076",
        ).forEach { version ->
            assertNull(sanitizeWechatRuntimeVersionToken(version))
        }
    }
}
