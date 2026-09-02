package com.aifriend.feature.task

import com.aifriend.contract.model.ConfirmationAction
import com.aifriend.contract.model.ChannelPartResult
import com.aifriend.contract.model.ChannelResult
import com.aifriend.contract.model.RecentTaskResult
import com.aifriend.contract.model.RecentTaskResultIntent
import com.aifriend.contract.model.TaskClientContext
import com.aifriend.contract.model.TaskSession
import com.aifriend.contract.model.TaskState
import com.aifriend.contract.model.WechatActionPlan
import com.aifriend.core.audio.CapturedAudio
import java.time.OffsetDateTime
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
import org.junit.Before
import org.junit.Test

/** 最近任务结果加载与纯中文映射测试。 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecentTaskResultsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun loadsResultsOnlyWhenPageRequestsThem() = runTest(dispatcher) {
        val repository = FakeTaskRepository()
        val viewModel = RecentTaskResultsViewModel(repository)

        assertEquals(0, repository.loadCount)
        viewModel.load()
        runCurrent()

        assertEquals(1, repository.loadCount)
        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(1, viewModel.uiState.value.results.size)
    }

    @Test
    fun hidesTechnicalFailureBehindChineseMessage() = runTest(dispatcher) {
        val viewModel = RecentTaskResultsViewModel(FakeTaskRepository(failOnCalls = setOf(1)))

        viewModel.load()
        runCurrent()

        assertEquals(
            "最近任务结果加载失败，请稍后重试",
            viewModel.uiState.value.errorMessage,
        )
    }

    @Test
    fun refreshFailureKeepsLastSuccessfulResults() = runTest(dispatcher) {
        val repository = FakeTaskRepository(failOnCalls = setOf(2))
        val viewModel = RecentTaskResultsViewModel(repository)

        viewModel.load()
        runCurrent()
        val firstResults = viewModel.uiState.value.results

        viewModel.load()
        runCurrent()

        assertEquals(2, repository.loadCount)
        assertEquals(firstResults, viewModel.uiState.value.results)
        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(
            "最近任务结果加载失败，请稍后重试",
            viewModel.uiState.value.errorMessage,
        )
    }

    @Test
    fun terminalResultLabelsAreExplicitChinese() {
        assertEquals("发送消息", RecentTaskResultIntent.SEND_MESSAGE.chineseLabel())
        assertEquals("已取消，未执行", TaskState.CANCELLED.chineseResult())
        assertEquals("体验已完成，没有调用微信", TaskState.SIMULATED.chineseResult())
    }

    private class FakeTaskRepository(
        private val failOnCalls: Set<Int> = emptySet(),
    ) : TaskRepository {
        var loadCount = 0

        override suspend fun listRecentResults(): List<RecentTaskResult> {
            loadCount++
            if (loadCount in failOnCalls) error("network details")
            val time = OffsetDateTime.parse("2026-08-29T08:00:00Z")
            return listOf(
                RecentTaskResult(
                    sessionId = "ts_01JRESULT",
                    state = TaskState.COMPLETED,
                    intent = RecentTaskResultIntent.SEND_MESSAGE,
                    createdAt = time.minusMinutes(1),
                    updatedAt = time,
                ),
            )
        }

        override suspend fun create(
            audioObjectId: String,
            context: TaskClientContext,
            previousConfirmedContactId: String?,
            basicRecognitionAudio: CapturedAudio?,
        ): TaskSession = error("not used")

        override suspend fun select(
            sessionId: String,
            candidateId: String,
            expectedVersion: Long,
        ): TaskSession = error("not used")

        override suspend fun confirm(
            session: TaskSession,
            action: ConfirmationAction,
            templateId: String,
            recognizedAt: OffsetDateTime,
        ): TaskConfirmationOutcome = error("not used")

        override suspend fun reportChannelResult(
            sessionId: String,
            plan: WechatActionPlan,
            result: ChannelResult,
            parts: List<ChannelPartResult>,
            occurredAt: OffsetDateTime,
        ): TaskSession = error("not used")
    }
}
