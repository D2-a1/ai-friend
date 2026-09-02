package com.aifriend.feature.auth

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WechatLoginCallbackBrokerTest {

    @Test
    fun matchingStatePublishesRedactedAuthorizationCodeOnce() = runTest {
        val broker = WechatLoginCallbackBroker()
        val event = async(start = CoroutineStart.UNDISPATCHED) { broker.events.first() }

        assertTrue(broker.begin(STATE))
        broker.complete(STATE, "single-use-code", WechatLoginCallbackOutcome.AUTHORIZED)

        val authorized = event.await() as WechatLoginEvent.Authorized
        assertEquals("single-use-code", authorized.code)
        assertFalse(authorized.toString().contains("single-use-code"))
        assertTrue(broker.begin(SECOND_STATE))
    }

    @Test
    fun mismatchedStateFailsClosedAndReleasesPendingAttempt() = runTest {
        val broker = WechatLoginCallbackBroker()
        val event = async(start = CoroutineStart.UNDISPATCHED) { broker.events.first() }

        assertTrue(broker.begin(STATE))
        broker.complete(SECOND_STATE, "must-not-pass", WechatLoginCallbackOutcome.AUTHORIZED)

        assertEquals(
            "微信登录校验失败，请重新登录",
            (event.await() as WechatLoginEvent.Failed).message,
        )
        assertTrue(broker.begin(SECOND_STATE))
    }

    @Test
    fun cancellationNeverCreatesAuthorizedEvent() = runTest {
        val broker = WechatLoginCallbackBroker()
        val event = async(start = CoroutineStart.UNDISPATCHED) { broker.events.first() }

        assertTrue(broker.begin(STATE))
        broker.complete(STATE, null, WechatLoginCallbackOutcome.CANCELLED)

        assertEquals("您已取消微信登录", (event.await() as WechatLoginEvent.Failed).message)
    }

    @Test
    fun invalidOrConcurrentStateIsRejected() {
        val broker = WechatLoginCallbackBroker()

        assertFalse(broker.begin("invalid"))
        assertTrue(broker.begin(STATE))
        assertFalse(broker.begin(SECOND_STATE))
        broker.cancel(SECOND_STATE)
        assertFalse(broker.begin(SECOND_STATE))
        broker.cancel(STATE)
        assertTrue(broker.begin(SECOND_STATE))
    }

    @Test
    fun unsolicitedCallbackIsNotRetainedForFutureCollector() = runTest {
        val broker = WechatLoginCallbackBroker()

        broker.complete(STATE, "must-not-pass", WechatLoginCallbackOutcome.AUTHORIZED)

        val receivedEvents = mutableListOf<WechatLoginEvent>()
        val collector = backgroundScope.launch {
            broker.events.collect(receivedEvents::add)
        }
        testScheduler.runCurrent()
        collector.cancel()
        assertTrue(receivedEvents.isEmpty())
    }

    private companion object {
        const val STATE = "0123456789abcdef0123456789abcdef"
        const val SECOND_STATE = "abcdef0123456789abcdef0123456789"
    }
}
