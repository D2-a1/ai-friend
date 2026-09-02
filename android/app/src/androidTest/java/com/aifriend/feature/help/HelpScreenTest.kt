package com.aifriend.feature.help

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.aifriend.core.design.AiFriendTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** 帮助页离线内容、大热区与播报失败语义测试。 */
class HelpScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun fourOfflineTopicsAndExplicitPlaybackAreReachable() {
        var play = 0
        var back = 0
        setContent(
            onPlayGuidance = { play++ },
            onBack = { back++ },
        )

        composeRule.onNodeWithText("权限设置").assertIsDisplayed()
        composeRule.onNodeWithText("省电白名单").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("家人协助").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("联系失败怎么办").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("播放指导").performScrollTo().performClick()
        composeRule.onNodeWithText("返回设置").performScrollTo().performClick()

        composeRule.runOnIdle {
            assertEquals(1, play)
            assertEquals(1, back)
        }
    }

    @Test
    fun playingStateDisablesRepeatedPlayback() {
        setContent(state = HelpUiState(isPlaying = true))
        composeRule.onNodeWithText("正在播放……").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun failureStateDoesNotPretendSuccess() {
        setContent(
            state = HelpUiState(
                errorMessage = "离线播报不可用，请阅读屏幕上的指导文字。",
            ),
        )
        composeRule.onNodeWithText(
            "离线播报不可用，请阅读屏幕上的指导文字。",
        ).performScrollTo().assertIsDisplayed()
    }

    private fun setContent(
        state: HelpUiState = HelpUiState(),
        onPlayGuidance: () -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        composeRule.setContent {
            AiFriendTheme {
                HelpScreen(
                    state = state,
                    onPlayGuidance = onPlayGuidance,
                    onDismissError = {},
                    onBack = onBack,
                )
            }
        }
    }
}
