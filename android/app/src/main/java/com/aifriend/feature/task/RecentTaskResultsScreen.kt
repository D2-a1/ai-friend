package com.aifriend.feature.task

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aifriend.contract.model.RecentTaskResult
import com.aifriend.contract.model.RecentTaskResultIntent
import com.aifriend.contract.model.TaskState
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 将最近任务结果 ViewModel 绑定到独立页面。 */
@Composable
fun RecentTaskResultsRoute(
    viewModel: RecentTaskResultsViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(Unit) { viewModel.load() }
    RecentTaskResultsScreen(
        state = state,
        onRefresh = viewModel::load,
        onBack = onBack,
    )
}

/** 只展示动作、终态和时间的适老最近任务结果页。 */
@Composable
fun RecentTaskResultsScreen(
    state: RecentTaskResultsUiState,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("最近任务结果", style = MaterialTheme.typography.headlineLarge)
        Text(
            "只显示做了什么、处理结果和时间，不显示录音或消息内容。",
            style = MaterialTheme.typography.bodyLarge,
        )
        when {
            state.isLoading && state.results.isEmpty() -> Text(
                "正在加载，请稍候。",
                style = MaterialTheme.typography.bodyLarge,
            )
            state.errorMessage != null && state.results.isEmpty() -> Text(
                state.errorMessage,
                style = MaterialTheme.typography.bodyLarge,
            )
            state.results.isEmpty() -> Text(
                "暂时没有最近任务结果。",
                style = MaterialTheme.typography.bodyLarge,
            )
            else -> {
                if (state.errorMessage != null) {
                    Text(state.errorMessage, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "上次成功读取的结果仍保留在下方。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else if (state.isLoading) {
                    Text(
                        "正在刷新，已有结果仍可查看。",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                state.results.forEach { result -> RecentTaskResultCard(result) }
            }
        }
        Button(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            enabled = !state.isLoading,
            onClick = onRefresh,
        ) { Text(if (state.isLoading) "正在刷新" else "刷新任务结果") }
        OutlinedButton(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            onClick = onBack,
        ) { Text("返回我的") }
    }
}

@Composable
private fun RecentTaskResultCard(result: RecentTaskResult) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(result.intent.chineseLabel(), style = MaterialTheme.typography.titleLarge)
            Text(result.state.chineseResult(), style = MaterialTheme.typography.bodyLarge)
            Text(
                "记录时间：${result.updatedAt.chineseTime()}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun RecentTaskResultIntent.chineseLabel(): String = when (this) {
    RecentTaskResultIntent.SEND_MESSAGE -> "发送消息"
    RecentTaskResultIntent.VOICE_CALL -> "微信语音通话"
    RecentTaskResultIntent.VIDEO_CALL -> "微信视频通话"
}

internal fun TaskState.chineseResult(): String = when (this) {
    TaskState.REJECTED -> "已拒绝，未执行"
    TaskState.CANCELLED -> "已取消，未执行"
    TaskState.SIMULATED -> "体验已完成，没有调用微信"
    TaskState.COMPLETED -> "已完成"
    TaskState.PARTIAL -> "部分完成"
    TaskState.FAILED -> "没有完成"
    else -> "仍在处理中"
}

private fun OffsetDateTime.chineseTime(): String = format(RESULT_TIME_FORMATTER)

private val RESULT_TIME_FORMATTER = DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.CHINA)
