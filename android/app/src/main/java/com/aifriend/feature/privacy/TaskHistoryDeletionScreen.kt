package com.aifriend.feature.privacy

import com.aifriend.app.ui.components.toChineseUiMessage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 适老化任务录音与历史清除页面。 */
@Composable
fun TaskHistoryDeletionRoute(viewModel: TaskHistoryDeletionViewModel, onAccepted: () -> Unit, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("清除任务录音与历史", style = MaterialTheme.typography.headlineMedium)
        Text("会清除任务录音、任务文字和最近任务结果。不会删除账号、亲友、称呼、安全指令和设置。", style = MaterialTheme.typography.bodyLarge)
        when (val current = state) {
            TaskHistoryDeletionUiState.Idle -> Button(onClick = viewModel::requestConfirmation, modifier = Modifier.fillMaxWidth()) { Text("开始清除") }
            TaskHistoryDeletionUiState.Confirming -> { Text("请再次确认：清除后不能恢复。", style = MaterialTheme.typography.titleLarge); Button(onClick = { viewModel.confirm(onAccepted) }, modifier = Modifier.fillMaxWidth()) { Text("确认清除任务录音与历史") }; OutlinedButton(onClick = viewModel::cancelConfirmation, modifier = Modifier.fillMaxWidth()) { Text("暂不清除") } }
            TaskHistoryDeletionUiState.Submitting -> Text("正在提交清除请求，请稍候。", style = MaterialTheme.typography.titleLarge)
            TaskHistoryDeletionUiState.Clearing -> { Text("正在清除。完成前不会显示为已完成。"); Button(onClick = viewModel::refresh, modifier = Modifier.fillMaxWidth()) { Text("刷新状态") } }
            TaskHistoryDeletionUiState.Completed -> Text("任务录音与历史已清除。", style = MaterialTheme.typography.titleLarge)
            is TaskHistoryDeletionUiState.Error -> { Text(current.message.toChineseUiMessage("清除操作没有完成，请稍后重试。")); Button(onClick = viewModel::requestConfirmation, modifier = Modifier.fillMaxWidth()) { Text("重新确认清除") }; OutlinedButton(onClick = viewModel::refresh, modifier = Modifier.fillMaxWidth()) { Text("查询已有清除状态") } }
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("返回") }
    }
}
