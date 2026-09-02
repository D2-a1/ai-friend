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

/** 适老化永久账号注销页面。 */
@Composable
fun AccountClosureRoute(
    viewModel: AccountClosureViewModel,
    onAccepted: () -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("永久注销账号", style = MaterialTheme.typography.headlineMedium)
        when (val current = state) {
            AccountClosureUiState.Reviewing -> {
                Text(
                    "注销会删除账号、亲友绑定、称呼、安全指令、任务记录和设置，且不能恢复。",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text("服务端受理后会立即停用旧登录。在线数据在 72 小时内清除；隔离备份按既定保留周期处理。")
                Button(
                    onClick = viewModel::continueConfirmation,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("我已了解，继续") }
            }
            AccountClosureUiState.Confirming -> {
                Text("请最后确认：这是永久注销，不是退出登录。", style = MaterialTheme.typography.titleLarge)
                Button(
                    onClick = { viewModel.confirm(onAccepted) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("确认永久注销") }
                OutlinedButton(
                    onClick = viewModel::cancelConfirmation,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("暂不注销") }
            }
            AccountClosureUiState.Submitting -> Text(
                "正在确认服务端是否已可靠受理，请不要重复操作。",
                style = MaterialTheme.typography.titleLarge,
            )
            is AccountClosureUiState.Error -> {
                Text(current.message.toChineseUiMessage("注销操作没有完成，请稍后重试。"), style = MaterialTheme.typography.titleLarge)
                Text("本机登录和数据尚未清除。如需重试，必须重新完成两次确认。")
                Button(
                    onClick = viewModel::retryReview,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("重新查看注销说明") }
            }
        }
        if (state != AccountClosureUiState.Submitting) {
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("返回") }
        }
    }
}
