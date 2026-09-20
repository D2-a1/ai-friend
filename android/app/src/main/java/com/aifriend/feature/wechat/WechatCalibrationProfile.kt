package com.aifriend.feature.wechat

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 微信坐标校准格式；增加不兼容字段时必须升级版本并显式迁移。 */
const val WECHAT_CALIBRATION_SCHEMA_VERSION = 1

/** 归一化坐标分母；整数表示可避免不同 JSON/浮点实现产生漂移。 */
const val WECHAT_CALIBRATION_COORDINATE_SCALE = 1_000_000

/** 微信公开页面中由用户当面记录的校准目标。 */
@Serializable
enum class WechatCalibrationTarget {
    HOME_SEARCH,
    GLOBAL_SEARCH_INPUT,
    GLOBAL_SEARCH_PASTE,
    SEARCH_RESULT,
    CHAT_CONTACT_AVATAR,
    CONTACT_PROFILE_CALL_ENTRY,
    CALL_CHOICE_VOICE,
    CALL_CHOICE_VIDEO,
    SHARE_SEARCH_ENTRY,
    SHARE_SEARCH_INPUT,
    SHARE_SEARCH_PASTE,
    SHARE_SEARCH_RESULT,
    SHARE_SEND_CONFIRM,
    CHAT_INFO_MENU,
    CHAT_INFO_CONTACT_AVATAR,
}

/** 通话与消息各自保存完整点位，不强迫用户重做另一组校准。 */
enum class WechatCalibrationPurpose(
    val targets: List<WechatCalibrationTarget>,
) {
    CALL(
        listOf(
            WechatCalibrationTarget.HOME_SEARCH,
            WechatCalibrationTarget.GLOBAL_SEARCH_INPUT,
            WechatCalibrationTarget.GLOBAL_SEARCH_PASTE,
            WechatCalibrationTarget.SEARCH_RESULT,
            WechatCalibrationTarget.CHAT_INFO_MENU,
            WechatCalibrationTarget.CHAT_INFO_CONTACT_AVATAR,
            WechatCalibrationTarget.CONTACT_PROFILE_CALL_ENTRY,
            WechatCalibrationTarget.CALL_CHOICE_VOICE,
            WechatCalibrationTarget.CALL_CHOICE_VIDEO,
        ),
    ),
    MESSAGE(
        listOf(
            WechatCalibrationTarget.SHARE_SEARCH_ENTRY,
            WechatCalibrationTarget.SHARE_SEARCH_INPUT,
            WechatCalibrationTarget.SHARE_SEARCH_PASTE,
            WechatCalibrationTarget.SHARE_SEARCH_RESULT,
            WechatCalibrationTarget.SHARE_SEND_CONFIRM,
        ),
    ),
}

val WechatCalibrationProfile.supportsCall: Boolean
    get() = points.keys.containsAll(WechatCalibrationPurpose.CALL.targets)

val WechatCalibrationProfile.supportsLegacyCallWithoutChatAvatar: Boolean
    get() = !supportsCall && points.keys.containsAll(LEGACY_CALL_TARGETS_V1)

val WechatCalibrationProfile.supportsMessage: Boolean
    get() = points.keys.containsAll(WechatCalibrationPurpose.MESSAGE.targets)

@Serializable
enum class WechatCalibrationOrientation {
    PORTRAIT,
    LANDSCAPE,
}

/**
 * 设备、显示和微信版本的精确匹配键。
 *
 * 校准档案不按账号隔离；它描述同一台设备上的微信 UI，不包含联系人或微信号。
 */
@Serializable
data class WechatCalibrationProfileKey(
    val manufacturer: String,
    val model: String,
    val androidSdkInt: Int,
    val displayWidthPixels: Int,
    val displayHeightPixels: Int,
    val densityDpi: Int,
    val fontScalePermille: Int,
    val orientation: WechatCalibrationOrientation,
    val wechatVersion: String,
) {
    init {
        require(manufacturer == normalizeCalibrationDeviceFact(manufacturer, lowercase = true))
        require(model == normalizeCalibrationDeviceFact(model, lowercase = false))
        require(androidSdkInt >= Build.VERSION_CODES.Q)
        require(displayWidthPixels >= 2 && displayHeightPixels >= 2)
        require(densityDpi in MINIMUM_DENSITY_DPI..MAXIMUM_DENSITY_DPI)
        require(fontScalePermille in MINIMUM_FONT_SCALE_PERMILLE..MAXIMUM_FONT_SCALE_PERMILLE)
        require(sanitizeWechatRuntimeVersionToken(wechatVersion) == wechatVersion)
    }

    internal fun stableSortKey(): String = listOf(
        manufacturer,
        model,
        androidSdkInt,
        displayWidthPixels,
        displayHeightPixels,
        densityDpi,
        fontScalePermille,
        orientation.name,
        wechatVersion,
    ).joinToString("\u0000")
}

/** 百万分比坐标；只在档案与当前运行指纹完全一致时才允许换算为像素。 */
@Serializable
data class WechatNormalizedCalibrationPoint(
    val xMillionths: Int,
    val yMillionths: Int,
) {
    init {
        require(xMillionths in 0..WECHAT_CALIBRATION_COORDINATE_SCALE)
        require(yMillionths in 0..WECHAT_CALIBRATION_COORDINATE_SCALE)
    }

    fun toPixels(widthPixels: Int, heightPixels: Int): WechatCalibrationPixelPoint {
        require(widthPixels > 0 && heightPixels > 0)
        return WechatCalibrationPixelPoint(
            x = scaleToPixel(xMillionths, widthPixels),
            y = scaleToPixel(yMillionths, heightPixels),
        )
    }

    private fun scaleToPixel(value: Int, size: Int): Int =
        (
            (value.toLong() * (size - 1L) + WECHAT_CALIBRATION_COORDINATE_SCALE / 2L) /
                WECHAT_CALIBRATION_COORDINATE_SCALE
            ).toInt()

    companion object {
        fun fromPixels(
            x: Int,
            y: Int,
            widthPixels: Int,
            heightPixels: Int,
        ): WechatNormalizedCalibrationPoint? {
            if (widthPixels < 2 || heightPixels < 2 ||
                x !in 0 until widthPixels || y !in 0 until heightPixels
            ) {
                return null
            }
            return WechatNormalizedCalibrationPoint(
                xMillionths = scaleFromPixel(x, widthPixels),
                yMillionths = scaleFromPixel(y, heightPixels),
            )
        }

        private fun scaleFromPixel(value: Int, size: Int): Int =
            (
                (value.toLong() * WECHAT_CALIBRATION_COORDINATE_SCALE + (size - 1L) / 2L) /
                    (size - 1L)
                ).toInt()
    }
}

data class WechatCalibrationPixelPoint(val x: Int, val y: Int)

/** 一台设备、一个显示状态和一个微信版本的一份完整校准档案。 */
@Serializable
data class WechatCalibrationProfile(
    val schemaVersion: Int = WECHAT_CALIBRATION_SCHEMA_VERSION,
    val key: WechatCalibrationProfileKey,
    val points: Map<WechatCalibrationTarget, WechatNormalizedCalibrationPoint>,
    val updatedAtEpochMillis: Long,
) {
    init {
        require(schemaVersion == WECHAT_CALIBRATION_SCHEMA_VERSION)
        require(points.isNotEmpty())
        require(
            WechatCalibrationPurpose.entries.any { purpose ->
                points.keys.containsAll(purpose.targets)
            } || points.keys.containsAll(LEGACY_CALL_TARGETS_V1),
        )
        require(updatedAtEpochMillis >= 0L)
    }

    override fun toString(): String =
        "WechatCalibrationProfile(schemaVersion=$schemaVersion, key=$key, " +
            "pointCount=${points.size}, updatedAtEpochMillis=$updatedAtEpochMillis)"
}

/** 列表不设置设备数量上限；重复精确键视为损坏并失败关闭。 */
@Serializable
data class WechatCalibrationProfileSet(
    val schemaVersion: Int = WECHAT_CALIBRATION_SCHEMA_VERSION,
    val profiles: List<WechatCalibrationProfile> = emptyList(),
) {
    init {
        require(schemaVersion == WECHAT_CALIBRATION_SCHEMA_VERSION)
        require(profiles.map { it.key }.distinct().size == profiles.size)
    }
}

/** 当前运行环境指纹；任何字段不可读时返回 null。 */
fun interface WechatCalibrationFingerprintProvider {
    fun current(wechatVersion: String): WechatCalibrationProfileKey?
}

@Singleton
class AndroidWechatCalibrationFingerprintProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : WechatCalibrationFingerprintProvider {
    override fun current(wechatVersion: String): WechatCalibrationProfileKey? = runCatching {
        val metrics = context.resources.displayMetrics
        val configuration = context.resources.configuration
        val orientation = when (configuration.orientation) {
            Configuration.ORIENTATION_PORTRAIT -> WechatCalibrationOrientation.PORTRAIT
            Configuration.ORIENTATION_LANDSCAPE -> WechatCalibrationOrientation.LANDSCAPE
            else -> return null
        }
        WechatCalibrationProfileKey(
            manufacturer = normalizeCalibrationDeviceFact(Build.MANUFACTURER, lowercase = true)
                ?: return null,
            model = normalizeCalibrationDeviceFact(Build.MODEL, lowercase = false)
                ?: return null,
            androidSdkInt = Build.VERSION.SDK_INT,
            displayWidthPixels = metrics.widthPixels,
            displayHeightPixels = metrics.heightPixels,
            densityDpi = metrics.densityDpi,
            fontScalePermille = (configuration.fontScale * 1_000f).toInt(),
            orientation = orientation,
            wechatVersion = sanitizeWechatRuntimeVersionToken(wechatVersion) ?: return null,
        )
    }.getOrNull()
}

/** 同一应用安装内的可扩展校准档案注册表。 */
interface WechatCalibrationProfileRegistry {
    fun list(): List<WechatCalibrationProfile>

    fun findExact(key: WechatCalibrationProfileKey): WechatCalibrationProfile?

    fun upsert(profile: WechatCalibrationProfile): Boolean

    fun remove(key: WechatCalibrationProfileKey): Boolean
}

/**
 * 校准档案只包含设备事实和坐标，使用独立私有 SharedPreferences 原子提交。
 * 损坏内容读取为空、写入失败关闭，绝不静默覆盖损坏数据。
 */
@Singleton
class AndroidWechatCalibrationProfileRegistry @Inject constructor(
    @ApplicationContext context: Context,
) : WechatCalibrationProfileRegistry {
    private val preferences: SharedPreferences = context.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    @Synchronized
    override fun list(): List<WechatCalibrationProfile> =
        WechatCalibrationProfileCodec.decode(preferences.getString(PROFILES_KEY, null))
            ?.profiles
            .orEmpty()

    @Synchronized
    override fun findExact(key: WechatCalibrationProfileKey): WechatCalibrationProfile? =
        list().singleOrNull { profile -> profile.key == key }

    @Synchronized
    override fun upsert(profile: WechatCalibrationProfile): Boolean {
        val raw = preferences.getString(PROFILES_KEY, null)
        val current = WechatCalibrationProfileCodec.decode(raw)
            ?: return false
        val next = current.profiles
            .filterNot { existing -> existing.key == profile.key }
            .plus(profile)
        return preferences.edit()
            .putString(PROFILES_KEY, WechatCalibrationProfileCodec.encode(next))
            .commit()
    }

    @Synchronized
    override fun remove(key: WechatCalibrationProfileKey): Boolean {
        val raw = preferences.getString(PROFILES_KEY, null)
        val current = WechatCalibrationProfileCodec.decode(raw)
            ?: return false
        val next = current.profiles.filterNot { profile -> profile.key == key }
        if (next.size == current.profiles.size) return true
        return preferences.edit()
            .putString(PROFILES_KEY, WechatCalibrationProfileCodec.encode(next))
            .commit()
    }

    private companion object {
        const val PREFERENCES_NAME = "ai_friend_wechat_calibration_profiles"
        const val PROFILES_KEY = "profiles_json_v1"
    }
}

/** 严格 JSON 编解码；列表按精确键稳定排序，但不限制档案数量。 */
internal object WechatCalibrationProfileCodec {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        isLenient = false
    }

    fun decode(raw: String?): WechatCalibrationProfileSet? {
        if (raw == null) return WechatCalibrationProfileSet()
        if (raw.isBlank()) return null
        return runCatching { json.decodeFromString<WechatCalibrationProfileSet>(raw) }
            .getOrNull()
    }

    fun encode(profiles: List<WechatCalibrationProfile>): String {
        val normalized = profiles
            .sortedBy { profile -> profile.key.stableSortKey() }
            .map { profile ->
                profile.copy(points = profile.points.toSortedMap(compareBy { it.ordinal }))
            }
        return json.encodeToString(WechatCalibrationProfileSet(profiles = normalized))
    }
}

internal fun normalizeCalibrationDeviceFact(raw: String?, lowercase: Boolean): String? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (value.length > MAXIMUM_DEVICE_FACT_LENGTH || value.any(Character::isISOControl)) {
        return null
    }
    return if (lowercase) value.lowercase(Locale.ROOT) else value
}

private val LEGACY_CALL_TARGETS_V1 = WechatCalibrationPurpose.CALL.targets
    .filterNot {
        it == WechatCalibrationTarget.CHAT_INFO_MENU ||
            it == WechatCalibrationTarget.CHAT_INFO_CONTACT_AVATAR
    }

private const val MAXIMUM_DEVICE_FACT_LENGTH = 100
private const val MINIMUM_DENSITY_DPI = 72
private const val MAXIMUM_DENSITY_DPI = 1_000
private const val MINIMUM_FONT_SCALE_PERMILLE = 500
private const val MAXIMUM_FONT_SCALE_PERMILLE = 3_000
