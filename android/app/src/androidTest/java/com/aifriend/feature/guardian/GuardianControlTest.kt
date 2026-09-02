package com.aifriend.feature.guardian

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.aifriend.core.design.AiFriendTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** 守护状态可见性和明确开关 Compose 测试。 */
class GuardianControlTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun sleepingStateRequiresConfirmationBeforeExplicitStop() {
        var stops = 0
        composeRule.setContent {
            AiFriendTheme {
                GuardianControl(
                    status = GuardianStatus(
                        GuardianMode.SLEEPING,
                        "正在等待您说两次小友",
                    ),
                    onEnable = {},
                    onDisable = { stops++ },
                )
            }
        }

        composeRule.onNodeWithText("正在等待您说两次小友").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            "小友守护状态，小友正在本机等待唤醒。正在等待您说两次小友",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("等待唤醒").assertIsDisplayed()
        composeRule.onNodeWithText("关闭小友守护").performClick()
        composeRule.onNodeWithText("确认关闭小友守护？").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, stops) }

        composeRule.onNodeWithText("继续守护").performClick()
        composeRule.runOnIdle { assertEquals(0, stops) }

        composeRule.onNodeWithText("关闭小友守护").performClick()
        composeRule.onNodeWithText("确认关闭").performClick()
        composeRule.runOnIdle { assertEquals(1, stops) }
    }

    @Test
    fun unavailableWakeModelShowsRetryInsteadOfFalseSuccess() {
        var retries = 0
        composeRule.setContent {
            AiFriendTheme {
                GuardianControl(
                    status = GuardianStatus(
                        GuardianMode.ERROR,
                        "正式本地双唤醒模型尚未接入，小友守护没有开启",
                    ),
                    onEnable = { retries++ },
                    onDisable = {},
                )
            }
        }

        composeRule.onNodeWithText("正式本地双唤醒模型尚未接入", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("重新检查并开启").performClick()
        composeRule.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun permissionDenialShowsSettingsRecoveryAndExplicitRetry() {
        var settingsOpens = 0
        var retries = 0
        composeRule.setContent {
            AiFriendTheme {
                GuardianControl(
                    status = GuardianStatus(
                        mode = GuardianMode.GUARDIAN_OFF,
                        message = "麦克风或通知权限已关闭，小友守护已停止",
                        permissionRecoveryRequired = true,
                    ),
                    onEnable = { retries++ },
                    onDisable = {},
                    onOpenAppPermissionSettings = { settingsOpens++ },
                )
            }
        }

        composeRule.onNodeWithText("打开应用权限设置").performClick()
        composeRule.onNodeWithText("检查权限并重新开启").performClick()
        composeRule.runOnIdle {
            assertEquals(1, settingsOpens)
            assertEquals(1, retries)
        }
    }
}
