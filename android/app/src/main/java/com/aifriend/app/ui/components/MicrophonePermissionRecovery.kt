package com.aifriend.app.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 打开当前应用的系统详情页，供用户自行恢复麦克风权限。
 *
 * @return 系统是否接受了页面跳转请求
 */
fun Context.openApplicationDetailsSettings(): Boolean = runCatching {
    startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName"),
        ),
    )
}.isSuccess

/** 麦克风权限被拒绝后展示的统一恢复入口。 */
@Composable
fun MicrophonePermissionRecoveryCard(
    onOpenAppPermissionSettings: () -> Unit,
    message: String = "请在系统设置中允许小友使用麦克风，返回后再点录音。",
    actionLabel: String = "打开麦克风权限设置",
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
            )
            OutlinedButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                onClick = onOpenAppPermissionSettings,
            ) {
                Text(actionLabel, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
