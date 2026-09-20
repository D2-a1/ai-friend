package com.aifriend.feature.guardian

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianResourceHandoffTest {

    @Test
    fun absentServiceNeedsNoWait() = runTest {
        val status = MutableStateFlow(
            GuardianStatus(GuardianMode.SLEEPING, "正在等待唤醒"),
        )

        assertTrue(
            awaitGuardianResourceRelease(
                status = status,
                stopRequested = false,
                timeoutMillis = 1L,
            ),
        )
    }

    @Test
    fun waitsUntilServiceReportsResourcesReleased() = runTest {
        val status = MutableStateFlow(
            GuardianStatus(GuardianMode.SLEEPING, "正在等待唤醒"),
        )
        launch {
            delay(100L)
            status.value = GuardianStatus()
        }

        assertTrue(
            awaitGuardianResourceRelease(
                status = status,
                stopRequested = true,
                timeoutMillis = 500L,
            ),
        )
    }

    @Test
    fun timeoutFailsClosedWhileServiceRemainsActive() = runTest {
        val status = MutableStateFlow(
            GuardianStatus(GuardianMode.SLEEPING, "正在等待唤醒"),
        )

        assertFalse(
            awaitGuardianResourceRelease(
                status = status,
                stopRequested = true,
                timeoutMillis = 100L,
            ),
        )
    }

    @Test
    fun `任务页等到服务交接态而不要求服务停止`() = runTest {
        val status = MutableStateFlow(
            GuardianStatus(GuardianMode.PROCESSING, "正在理解"),
        )
        launch {
            delay(100L)
            status.value = GuardianStatus(GuardianMode.TASK_HANDOFF, "前台确认中")
        }

        assertTrue(awaitGuardianTaskHandoff(status, timeoutMillis = 500L))
        assertTrue(status.value.active)
    }

    @Test
    fun `交接前服务关闭必须失败而不接管麦克风`() = runTest {
        val status = MutableStateFlow(
            GuardianStatus(GuardianMode.PROCESSING, "正在理解"),
        )
        launch {
            delay(100L)
            status.value = GuardianStatus()
        }

        assertFalse(awaitGuardianTaskHandoff(status, timeoutMillis = 500L))
    }
}
