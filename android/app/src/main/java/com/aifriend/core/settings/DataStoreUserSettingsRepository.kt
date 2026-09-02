package com.aifriend.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.aifriend.feature.auth.AuthSessionRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.ExperimentalCoroutinesApi

/** 设置专用 DataStore，避免与会话、删除闩或其他偏好混存。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SettingsDataStore

/**
 * 使用 owner 摘要作为 key 命名空间；退出登录不删除，切换账号也不会继承其他人的设置。
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreUserSettingsRepository @Inject constructor(
    @param:SettingsDataStore private val dataStore: DataStore<Preferences>,
    private val authSessionRepository: AuthSessionRepository,
) : UserSettingsRepository {
    override val settings: Flow<UserSettings> = authSessionRepository.session
        .flatMapLatest { session ->
            val ownerNamespace = session?.userId?.let(::ownerNamespace)
                ?: return@flatMapLatest flowOf(UserSettings())
            dataStore.data
                .catch { cause ->
                    if (cause is IOException) emit(emptyPreferences()) else throw cause
                }
                .map { preferences -> UserSettingsPreferenceCodec.read(preferences, ownerNamespace) }
        }
        .distinctUntilChanged()

    override suspend fun current(): UserSettings = settings.first()

    override suspend fun updateSpeechRate(value: SpeechRatePreference) {
        updateForCurrentOwner { preferences, namespace ->
            preferences[UserSettingsPreferenceCodec.speechRateKey(namespace)] = value.name
        }
    }

    override suspend fun updateSpeechVolume(value: SpeechVolumePreference) {
        updateForCurrentOwner { preferences, namespace ->
            preferences[UserSettingsPreferenceCodec.speechVolumeKey(namespace)] = value.name
        }
    }

    override suspend fun updateFontLevel(value: FontLevel) {
        updateForCurrentOwner { preferences, namespace ->
            preferences[UserSettingsPreferenceCodec.fontLevelKey(namespace)] = value.name
        }
    }

    override suspend fun updateHighContrast(enabled: Boolean) {
        updateForCurrentOwner { preferences, namespace ->
            preferences[UserSettingsPreferenceCodec.highContrastKey(namespace)] = enabled
        }
    }

    override suspend fun clearAll() {
        dataStore.edit { preferences -> preferences.clear() }
    }

    private suspend fun updateForCurrentOwner(
        block: (MutablePreferences, String) -> Unit,
    ) {
        val userId = checkNotNull(authSessionRepository.session.value?.userId) {
            "设置只能由已登录账号修改"
        }
        val expectedOwner = ownerNamespace(userId)
        dataStore.edit { preferences ->
            check(authSessionRepository.session.value?.userId?.let(::ownerNamespace) == expectedOwner) {
                "设置写入期间登录账号已变化"
            }
            block(preferences, expectedOwner)
        }
    }

    private fun ownerNamespace(userId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(userId.encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }
}

/** 纯偏好编解码器；未知或损坏值一律回落安全默认值。 */
internal object UserSettingsPreferenceCodec {
    fun read(preferences: Preferences, ownerNamespace: String): UserSettings = UserSettings(
        speechRate = enumValueOrDefault(
            preferences[speechRateKey(ownerNamespace)],
            SpeechRatePreference.NORMAL,
        ),
        speechVolume = enumValueOrDefault(
            preferences[speechVolumeKey(ownerNamespace)],
            SpeechVolumePreference.NORMAL,
        ),
        fontLevel = enumValueOrDefault(
            preferences[fontLevelKey(ownerNamespace)],
            FontLevel.LARGE,
        ),
        highContrast = preferences[highContrastKey(ownerNamespace)] ?: false,
    )

    fun speechRateKey(ownerNamespace: String) =
        stringPreferencesKey("owner_${ownerNamespace}_speech_rate")

    fun speechVolumeKey(ownerNamespace: String) =
        stringPreferencesKey("owner_${ownerNamespace}_speech_volume")

    fun fontLevelKey(ownerNamespace: String) =
        stringPreferencesKey("owner_${ownerNamespace}_font_level")

    fun highContrastKey(ownerNamespace: String) =
        booleanPreferencesKey("owner_${ownerNamespace}_high_contrast")

    private inline fun <reified T : Enum<T>> enumValueOrDefault(
        rawValue: String?,
        defaultValue: T,
    ): T = enumValues<T>().firstOrNull { it.name == rawValue } ?: defaultValue
}

@Module
@InstallIn(SingletonComponent::class)
abstract class UserSettingsModule {
    @Binds
    abstract fun bindUserSettingsRepository(
        implementation: DataStoreUserSettingsRepository,
    ): UserSettingsRepository

    companion object {
        @Provides
        @Singleton
        @SettingsDataStore
        fun provideSettingsDataStore(
            @ApplicationContext context: Context,
        ): DataStore<Preferences> = PreferenceDataStoreFactory.create {
            context.preferencesDataStoreFile(SETTINGS_FILE_NAME)
        }

        private const val SETTINGS_FILE_NAME = "ai_friend_user_settings.preferences_pb"
    }
}
