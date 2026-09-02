package com.aifriend.feature.help

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** 将帮助 ViewModel 绑定到独立页面。 */
@Composable
fun HelpRoute(
    viewModel: HelpViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    HelpScreen(
        state = state,
        onPlayGuidance = viewModel::playGuidance,
        onDismissError = viewModel::dismissError,
        onBack = {
            viewModel.leave()
            onBack()
        },
    )
}

/** 适老离线帮助页；所有主要交互目标至少 56dp。 */
@Composable
fun HelpScreen(
    state: HelpUiState,
    onPlayGuidance: () -> Unit,
    onDismissError: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "使用帮助",
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineLarge,
        )
        Text(
            text = "指导内容已保存在本机，播放时不会联网。",
            style = MaterialTheme.typography.bodyLarge,
        )
        HelpSection(
            title = "权限设置",
            body = "麦克风、通知和无障碍权限必须由您或家人在系统设置中亲自开启。返回设置页后会重新读取状态。",
        )
        HelpSection(
            title = "省电白名单",
            body = "如果小友在锁屏后停止，请在手机省电设置中允许小友后台运行。应用不会自动修改系统策略。",
        )
        HelpSection(
            title = "家人协助",
            body = "家人可以帮助绑定联系人、录入称呼和检查权限。每次发消息或打电话仍需要老人听完完整复述并明确确认。",
        )
        HelpSection(
            title = "联系失败怎么办",
            body = "断网、识别不唯一或微信页面不匹配时，应用会停止且不会自动补发。请返回首页，确认网络和权限后重新说完整指令。",
        )
        Button(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            enabled = !state.isPlaying,
            onClick = onPlayGuidance,
        ) {
            Text(if (state.isPlaying) "正在播放……" else "播放指导")
        }
        state.errorMessage?.let { message ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(message, style = MaterialTheme.typography.bodyLarge)
                    Button(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        onClick = onDismissError,
                    ) { Text("知道了") }
                }
            }
        }
        OutlinedButton(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            onClick = onBack,
        ) {
            Text("返回设置")
        }
    }
}

@Composable
private fun HelpSection(
    title: String,
    body: String,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(body, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
