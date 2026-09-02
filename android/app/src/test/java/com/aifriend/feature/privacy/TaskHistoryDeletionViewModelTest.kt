package com.aifriend.feature.privacy

import com.aifriend.contract.model.TaskHistoryDeletion
import com.aifriend.contract.model.TaskHistoryDeletionStatus
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 任务历史清除二次确认和状态映射测试。 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskHistoryDeletionViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun requiresSecondConfirmationBeforeRequest() = runTest(dispatcher) {
        val repository = FakeRepository(TaskHistoryDeletionStatus.CLEARING)
        val viewModel = TaskHistoryDeletionViewModel(repository)
        viewModel.requestConfirmation()
        assertEquals(TaskHistoryDeletionUiState.Confirming, viewModel.state.value)
        assertEquals(0, repository.clearCount)
    }

    @Test fun acceptedClearingImmediatelyInvokesLocalCleanup() = runTest(dispatcher) {
        val repository = FakeRepository(TaskHistoryDeletionStatus.CLEARING)
        val viewModel = TaskHistoryDeletionViewModel(repository)
        var localCleared = false
        viewModel.requestConfirmation()
        viewModel.confirm { localCleared = true }
        runCurrent()
        assertTrue(localCleared)
        assertEquals(TaskHistoryDeletionUiState.Clearing, viewModel.state.value)
    }

    @Test fun failedRequestDoesNotInvokeLocalCleanup() = runTest(dispatcher) {
        val viewModel = TaskHistoryDeletionViewModel(FakeRepository(null))
        var localCleared = false
        viewModel.requestConfirmation()
        viewModel.confirm { localCleared = true }
        runCurrent()
        assertFalse(localCleared)
        assertEquals(
            TaskHistoryDeletionUiState.Error("清除请求失败"),
            viewModel.state.value,
        )
    }

    @Test fun failedRefreshHidesTechnicalMessage() = runTest(dispatcher) {
        val viewModel = TaskHistoryDeletionViewModel(FakeRepository(null))

        viewModel.refresh()
        runCurrent()

        assertEquals(
            TaskHistoryDeletionUiState.Error("查询失败"),
            viewModel.state.value,
        )
    }

    @Test fun ignoresRepeatedSubmissionWhileRequestIsRunning() = runTest(dispatcher) {
        val repository = FakeRepository(TaskHistoryDeletionStatus.CLEARING)
        val viewModel = TaskHistoryDeletionViewModel(repository)
        viewModel.requestConfirmation()
        viewModel.confirm { }
        viewModel.confirm { }
        runCurrent()
        assertEquals(1, repository.clearCount)
    }

    private class FakeRepository(private val status: TaskHistoryDeletionStatus?) : TaskHistoryDeletionRepository {
        var clearCount = 0
        override suspend fun clear(): TaskHistoryDeletion { clearCount++; return value() }
        override suspend fun get(): TaskHistoryDeletion = value()
        private fun value(): TaskHistoryDeletion {
            val current = status ?: error("network")
            return TaskHistoryDeletion(current, OffsetDateTime.parse("2026-08-19T10:00:00Z"), if (current == TaskHistoryDeletionStatus.COMPLETED) OffsetDateTime.parse("2026-08-19T10:01:00Z") else null)
        }
    }
}
