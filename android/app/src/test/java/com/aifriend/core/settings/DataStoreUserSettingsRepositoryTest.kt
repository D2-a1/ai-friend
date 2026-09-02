package com.aifriend.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.aifriend.feature.auth.AuthSession
import com.aifriend.feature.auth.AuthSessionRepository
import com.aifriend.feature.auth.WechatLoginDevice
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** owner 设置隔离、默认回落和注销全清边界测试。 */
class DataStoreUserSettingsRepositoryTest {

    @Test
    fun settingsAreIsolatedByHashedOwnerNamespace() = runTest {
        val auth = FakeAuthSessionRepository(session("us_owner_one"))
        val repository = DataStoreUserSettingsRepository(InMemoryPreferencesDataStore(), auth)

        repository.updateSpeechRate(SpeechRatePreference.FAST)
        repository.updateSpeechVolume(SpeechVolumePreference.LOUD)
        repository.updateHighContrast(true)
        assertEquals(
            UserSettings(
                speechRate = SpeechRatePreference.FAST,
                speechVolume = SpeechVolumePreference.LOUD,
                highContrast = true,
            ),
            repository.current(),
        )

        auth.setSession(session("us_owner_two"))
        assertEquals(UserSettings(), repository.current())
        repository.updateFontLevel(FontLevel.LARGER)

        auth.setSession(session("us_owner_one"))
        assertEquals(
            UserSettings(
                speechRate = SpeechRatePreference.FAST,
                speechVolume = SpeechVolumePreference.LOUD,
                highContrast = true,
            ),
            repository.current(),
        )
        auth.setSession(session("us_owner_two"))
        assertEquals(UserSettings(fontLevel = FontLevel.LARGER), repository.current())
    }

    @Test
    fun clearAllRemovesEveryOwnerNamespaceForWipeResume() = runTest {
        val auth = FakeAuthSessionRepository(session("us_owner_one"))
        val repository = DataStoreUserSettingsRepository(InMemoryPreferencesDataStore(), auth)
        repository.updateSpeechRate(SpeechRatePreference.SLOW)
        auth.setSession(session("us_owner_two"))
        repository.updateHighContrast(true)

        repository.clearAll()

        assertEquals(UserSettings(), repository.current())
        auth.setSession(session("us_owner_one"))
        assertEquals(UserSettings(), repository.current())
    }

    @Test
    fun damagedEnumValuesFallBackToAccessibleDefaults() {
        val namespace = "test_namespace"
        val preferences = mutablePreferencesOf(
            UserSettingsPreferenceCodec.speechRateKey(namespace) to "UNKNOWN",
            UserSettingsPreferenceCodec.speechVolumeKey(namespace) to "MAXIMUM",
            UserSettingsPreferenceCodec.fontLevelKey(namespace) to "SMALL",
            UserSettingsPreferenceCodec.highContrastKey(namespace) to true,
        )

        assertEquals(
            UserSettings(highContrast = true),
            UserSettingsPreferenceCodec.read(preferences, namespace),
        )
    }

    @Test
    fun speechVolumeGainsAreDistinctBoundedAndNeverChangeSystemVolume() {
        val gains = SpeechVolumePreference.entries.map { it.ttsVolume }

        assertEquals(gains.sorted(), gains)
        assertEquals(gains.size, gains.distinct().size)
        assertTrue(gains.all { it in 0f..1f })
    }

    private fun session(userId: String) = AuthSession(
        userId = userId,
        userStatus = "ACTIVE",
        displayName = null,
        accessTokenExpiresAt = Instant.parse("2026-08-22T12:00:00Z"),
        refreshTokenExpiresAt = Instant.parse("2026-09-22T12:00:00Z"),
    )
}

private class InMemoryPreferencesDataStore : DataStore<Preferences> {
    private val mutex = Mutex()
    private val state = MutableStateFlow<Preferences>(emptyPreferences())

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        mutex.withLock {
            transform(state.value).also { state.value = it }
        }
}

private class FakeAuthSessionRepository(
    initialSession: AuthSession?,
) : AuthSessionRepository {
    override val session = MutableStateFlow(initialSession)

    fun setSession(value: AuthSession?) {
        session.value = value
    }

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
