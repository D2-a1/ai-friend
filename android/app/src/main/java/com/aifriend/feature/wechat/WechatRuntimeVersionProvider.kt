package com.aifriend.feature.wechat

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** 只读获取当前设备已安装微信的版本 token。 */
fun interface WechatRuntimeVersionProvider {
    /** 微信未安装、包信息不可读或版本 token 不合格时返回 null。 */
    fun readCurrentVersion(): String?
}

/** 仅通过系统包管理器读取官方微信包版本，不记录或持久化结果。 */
@Singleton
class AndroidWechatRuntimeVersionProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : WechatRuntimeVersionProvider {
    override fun readCurrentVersion(): String? = runCatching {
        val packageInfo = packageInfo(context.packageManager)
        sanitizeWechatRuntimeVersionToken(packageInfo.versionName)
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun packageInfo(packageManager: PackageManager): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                WECHAT_PACKAGE,
                PackageManager.PackageInfoFlags.of(0L),
            )
        } else {
            packageManager.getPackageInfo(WECHAT_PACKAGE, 0)
        }

    private companion object {
        const val WECHAT_PACKAGE = "com.tencent.mm"
    }
}

/** 保留原值但拒绝空值、空白、冒号或超过协议上限的版本 token。 */
internal fun sanitizeWechatRuntimeVersionToken(rawVersion: String?): String? {
    val version = rawVersion ?: return null
    if (version.isEmpty() || version.length > MAX_WECHAT_VERSION_TOKEN_LENGTH) return null
    if (version.any { character ->
            character == ':' ||
                Character.isWhitespace(character) ||
                Character.isSpaceChar(character)
        }
    ) {
        return null
    }
    return version
}

private const val MAX_WECHAT_VERSION_TOKEN_LENGTH = 100
