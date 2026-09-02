package com.aifriend.feature.help

import com.aifriend.core.voice.OfflineSpeechPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 固定离线指导播放与失败关闭测试。 */
@OptIn(ExperimentalCoroutinesApi::class)
class HelpViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun successfulGuidanceUsesOnlyFixedOfflineSpeech() = runTest(dispatcher) {
        val speech = FakeOfflineSpeechPort(success = true)
        val viewModel = HelpViewModel(speech)

        viewModel.playGuidance()
        runCurrent()

        assertEquals(1, speech.spokenTexts.size)
        assertTrue(speech.spokenTexts.single().contains("不会自动补发"))
        assertFalse(viewModel.uiState.value.isPlaying)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun unavailableOfflineVoiceShowsReadableFailure() = runTest(dispatcher) {
        val viewModel = HelpViewModel(FakeOfflineSpeechPort(success = false))

        viewModel.playGuidance()
        runCurrent()

        assertFalse(viewModel.uiState.value.isPlaying)
        assertEquals(
            "离线播报不可用，请阅读屏幕上的指导文字。",
            viewModel.uiState.value.errorMessage,
        )
    }
}

private class FakeOfflineSpeechPort(
    private val success: Boolean,
) : OfflineSpeechPort {
    val spokenTexts = mutableListOf<String>()

    override suspend fun prepare(): Boolean = success

    override suspend fun speak(text: String): Boolean {
        spokenTexts += text
        return success
    }

    override fun close() = Unit
}
