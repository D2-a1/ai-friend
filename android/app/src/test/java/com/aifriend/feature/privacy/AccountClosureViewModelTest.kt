package com.aifriend.feature.privacy

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

@OptIn(ExperimentalCoroutinesApi::class)
class AccountClosureViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun requiresBothExplicitConfirmationsBeforeRequest() = runTest(dispatcher) {
        val repository = FakeRepository()
        val viewModel = AccountClosureViewModel(repository)

        viewModel.continueConfirmation()

        assertEquals(AccountClosureUiState.Confirming, viewModel.state.value)
        assertEquals(0, repository.closeCount)
    }

    @Test
    fun acceptedRequestInvokesDurableWipeCallbackOnce() = runTest(dispatcher) {
        val repository = FakeRepository()
        val viewModel = AccountClosureViewModel(repository)
        var acceptedCount = 0
        viewModel.continueConfirmation()

        viewModel.confirm { acceptedCount++ }
        viewModel.confirm { acceptedCount++ }
        runCurrent()

        assertEquals(1, repository.closeCount)
        assertEquals(1, acceptedCount)
    }

    @Test
    fun failedRequestKeepsLocalDataAndRequiresBothConfirmationsAgain() = runTest(dispatcher) {
        val viewModel = AccountClosureViewModel(FakeRepository(fail = true))
        var accepted = false
        viewModel.continueConfirmation()

        viewModel.confirm { accepted = true }
        runCurrent()

        assertFalse(accepted)
        assertEquals(
            AccountClosureUiState.Error("注销申请没有可靠受理，请稍后重新确认"),
            viewModel.state.value,
        )
        viewModel.retryReview()
        assertEquals(AccountClosureUiState.Reviewing, viewModel.state.value)
    }

    private class FakeRepository(
        private val fail: Boolean = false,
    ) : AccountClosureRepository {
        var closeCount = 0
        override suspend fun close(): AccountClosureAcceptance {
            closeCount++
            if (fail) error("network")
            return AccountClosureAcceptance.Accepted
        }
    }
}
