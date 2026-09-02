package com.aifriend.core.network

import org.junit.Assert.assertEquals
import org.junit.Test

/** 用户可见中文异常说明过滤测试。 */
class ChineseUserMessagePresentationTest {

    @Test
    fun boundedChineseDomainMessageIsPreserved() {
        val message = IllegalStateException("连接音频存储超时，音频没有上传")
            .toChineseUserMessage("音频没有上传，请重新录制")

        assertEquals("连接音频存储超时，音频没有上传", message)
    }

    @Test
    fun englishTechnicalMessageUsesCallerFallback() {
        val message = IllegalStateException("upload client crashed")
            .toChineseUserMessage("音频没有上传，请重新录制")

        assertEquals("音频没有上传，请重新录制", message)
    }

    @Test
    fun mixedTechnicalMessageUsesCallerFallback() {
        val message = IllegalStateException("网络失败：HTTP client crashed")
            .toChineseUserMessage("任务没有完成，请重新说")

        assertEquals("任务没有完成，请重新说", message)
    }

    @Test
    fun invalidFallbackStillUsesFixedChineseMessage() {
        val message = IllegalStateException("SDK\nstack trace")
            .toChineseUserMessage("fallback failed")

        assertEquals("操作没有完成，请稍后再试", message)
    }
}
