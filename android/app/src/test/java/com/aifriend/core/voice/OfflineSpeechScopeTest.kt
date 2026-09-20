package com.aifriend.core.voice

import javax.inject.Singleton
import org.junit.Assert.assertFalse
import org.junit.Test

class OfflineSpeechScopeTest {

    @Test
    fun lifecycleOwnersMustNotShareOneClosableSpeechEngine() {
        assertFalse(
            AndroidOfflineSpeechAdapter::class.java.isAnnotationPresent(Singleton::class.java),
        )
    }
}
