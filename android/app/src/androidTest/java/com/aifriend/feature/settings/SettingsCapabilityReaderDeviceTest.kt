package com.aifriend.feature.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aifriend.feature.auth.AuthSession
import com.aifriend.feature.auth.AuthSessionRepository
import com.aifriend.feature.auth.WechatLoginDevice
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** API 29/31 上只读系统状态且不依赖微信页面的设备测试。 */
class SettingsCapabilityReaderDeviceTest {

    @Test
    fun accountFactTracksSessionWhileSystemFactsRemainBoundedTriState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val auth = DeviceFakeAuthSessionRepository()
        val reader = AndroidSettingsCapabilityReader(context, auth)

        val signedOut = reader.read()
        assertEquals(CapabilityReadState.UNAVAILABLE, signedOut.accountSession)
        assertBoundedSystemStates(signedOut)

        auth.session.value = AuthSession(
            userId = "us_device_settings",
            userStatus = "ACTIVE",
            displayName = null,
            accessTokenExpiresAt = Instant.parse("2026-08-22T12:00:00Z"),
            refreshTokenExpiresAt = Instant.parse("2026-09-22T12:00:00Z"),
        )

        val signedIn = reader.read()
        assertEquals(CapabilityReadState.AVAILABLE, signedIn.accountSession)
        assertBoundedSystemStates(signedIn)
    }

    private fun assertBoundedSystemStates(status: SettingsCapabilityStatus) {
        val allowed = CapabilityReadState.entries.toSet()
        assertTrue(status.network in allowed)
        assertTrue(status.microphone in allowed)
        assertTrue(status.notifications in allowed)
        assertTrue(status.restrictedWechatAccessibility in allowed)
        assertTrue(status.batteryOptimizationExemption in allowed)
    }
}

private class DeviceFakeAuthSessionRepository : AuthSessionRepository {
    override val session = MutableStateFlow<AuthSession?>(null)

    override suspend fun restore(): AuthSession? = session.value

    override suspend fun loginWithWechatCode(
        code: String,
        device: WechatLoginDevice,
    ): AuthSession = error("not used")

    override suspend fun refresh(): AuthSession = error("not used")

    override suspend fun clearLocalSession() {
        session.value = null
    }
}
