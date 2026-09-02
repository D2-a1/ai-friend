package com.aifriend.core.settings

import kotlinx.coroutines.flow.Flow

/** 老人可调整的离线播报语速。 */
enum class SpeechRatePreference(
    val ttsRate: Float,
) {
    SLOW(0.8f),
    NORMAL(1.0f),
    FAST(1.2f),
}

/** App 本次离线播报的相对音量；不会修改手机系统媒体音量。 */
enum class SpeechVolumePreference(
    val ttsVolume: Float,
) {
    GENTLE(0.65f),
    NORMAL(0.85f),
    LOUD(1.0f),
}

/** 适老字体档位；MVP 不提供小字号。 */
enum class FontLevel {
    LARGE,
    LARGER,
}

/** 当前登录 owner 的本机显示与播报设置。 */
data class UserSettings(
    val speechRate: SpeechRatePreference = SpeechRatePreference.NORMAL,
    val speechVolume: SpeechVolumePreference = SpeechVolumePreference.NORMAL,
    val fontLevel: FontLevel = FontLevel.LARGE,
    val highContrast: Boolean = false,
)

/** 按登录 owner 隔离的本机设置端口。 */
interface UserSettingsRepository {
    val settings: Flow<UserSettings>

    suspend fun current(): UserSettings

    suspend fun updateSpeechRate(value: SpeechRatePreference)

    suspend fun updateSpeechVolume(value: SpeechVolumePreference)

    suspend fun updateFontLevel(value: FontLevel)

    suspend fun updateHighContrast(enabled: Boolean)

    /** 注销清理可在会话恢复前执行，因此必须能清除全部本机设置命名空间。 */
    suspend fun clearAll()
}
