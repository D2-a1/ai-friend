package com.aifriend.feature.settings

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.aifriend.feature.auth.AuthSessionRepository
import com.aifriend.service.WechatAccessibilityService
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/** 单项系统能力的只读结果；读取异常不能伪装成明确关闭。 */
enum class CapabilityReadState {
    AVAILABLE,
    UNAVAILABLE,
    UNKNOWN,
}

/** 设置页展示的最小设备事实，不含微信页面、节点或用户内容。 */
data class SettingsCapabilityStatus(
    val network: CapabilityReadState,
    val microphone: CapabilityReadState,
    val notifications: CapabilityReadState,
    val restrictedWechatAccessibility: CapabilityReadState,
    val accountSession: CapabilityReadState,
    val batteryOptimizationExemption: CapabilityReadState,
)

/** 读取当前系统与会话能力状态，不申请、开启或修改任何权限。 */
fun interface SettingsCapabilityReader {
    fun read(): SettingsCapabilityStatus
}

/** Android 只读能力状态适配器。 */
@Singleton
class AndroidSettingsCapabilityReader @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val authSessionRepository: AuthSessionRepository,
) : SettingsCapabilityReader {
    override fun read(): SettingsCapabilityStatus = SettingsCapabilityStatus(
        network = readState(::isValidatedNetworkAvailable),
        microphone = readState {
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        },
        notifications = readState {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        },
        restrictedWechatAccessibility = readState(::isRestrictedWechatAccessibilityEnabled),
        accountSession = readState {
            authSessionRepository.session.value != null
        },
        batteryOptimizationExemption = readState {
            context.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(context.packageName)
        },
    )

    private fun isValidatedNetworkAvailable(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun isRestrictedWechatAccessibilityEnabled(): Boolean {
        val expectedComponent = ComponentName(context, WechatAccessibilityService::class.java)
        val manager = context.getSystemService(AccessibilityManager::class.java)
        return manager.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK,
        ).any { service ->
            val serviceInfo = service.resolveInfo.serviceInfo
            ComponentName(serviceInfo.packageName, serviceInfo.name) == expectedComponent
        }
    }

    private fun readState(block: () -> Boolean): CapabilityReadState = runCatching {
        if (block()) CapabilityReadState.AVAILABLE else CapabilityReadState.UNAVAILABLE
    }.getOrDefault(CapabilityReadState.UNKNOWN)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsCapabilityModule {
    @Binds
    abstract fun bindSettingsCapabilityReader(
        implementation: AndroidSettingsCapabilityReader,
    ): SettingsCapabilityReader
}
