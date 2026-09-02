package com.aifriend.app.ui.welcome

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aifriend.BuildConfig
import com.aifriend.app.ui.AiFriendUiState
import com.aifriend.app.ui.components.BrandHero
import com.aifriend.app.ui.components.ElderPage
import com.aifriend.app.ui.components.ErrorPanel
import com.aifriend.app.ui.components.InfoPanel
import com.aifriend.app.ui.components.LoadingContent
import com.aifriend.app.ui.components.SectionCard

/** 登录、授权、清理和错误状态的统一欢迎页。 */
@Composable
internal fun WelcomeStateContent(
    state: AiFriendUiState,
    deviceFingerprint: String,
    onWechatLogin: () -> Unit,
    onLocalLogin: () -> Unit,
    onGrantConsent: () -> Unit,
    onExit: () -> Unit,
    onRetry: () -> Unit,
    onRetryWipe: () -> Unit,
) {
    ElderPage {
        BrandHero()
        SectionCard {
            when (state) {
                AiFriendUiState.Loading -> LoadingContent("正在准备，请稍候…")
                AiFriendUiState.AccountWiping -> LoadingContent(
                    "正在安全清除本机账号数据，完成前不能登录。",
                )
                is AiFriendUiState.AccountWipeFailed -> {
                    ErrorPanel(state.message)
                    Button(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                        onClick = onRetryWipe,
                    ) {
                        Text("重试清除")
                    }
                }
                AiFriendUiState.SignedOut -> SignedOutContent(onWechatLogin, onLocalLogin)
                is AiFriendUiState.ConsentRequired -> ConsentContent(onGrantConsent, onExit)
                is AiFriendUiState.Error -> ErrorContent(state.message, onRetry)
                is AiFriendUiState.Ready -> Unit
            }
        }
        SectionCard(
            title = "本机设备编号",
            support = "只有加入服务器白名单的手机才能登录。长按编号可以复制。",
        ) {
            SelectionContainer {
                Text(
                    text = deviceFingerprint,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun SignedOutContent(
    onWechatLogin: () -> Unit,
    onLocalLogin: () -> Unit,
) {
    Text(text = "开始使用", style = MaterialTheme.typography.headlineSmall)
    Text(
        text = if (BuildConfig.WECHAT_LOGIN_ENABLED) {
            "使用微信完成登录，只获取必要的账号标识。"
        } else if (BuildConfig.DEBUG) {
            "体验版可以直接连接已部署的服务，不会调用微信登录。"
        } else {
            "当前安装包尚未配置微信登录。"
        },
        style = MaterialTheme.typography.bodyLarge,
    )
    InfoPanel("只处理登录所需信息，不读取聊天内容或全部好友。")
    if (BuildConfig.WECHAT_LOGIN_ENABLED) {
        Button(
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
            onClick = onWechatLogin,
        ) {
            Text("微信登录")
        }
    }
    if (BuildConfig.DEBUG) {
        OutlinedButton(
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
            onClick = onLocalLogin,
        ) {
            Text("进入体验版")
        }
        Text(
            text = "当前为测试入口，不会打开微信。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ConsentContent(
    onGrant: () -> Unit,
    onExit: () -> Unit,
) {
    Text(text = "先确认一件事", style = MaterialTheme.typography.headlineSmall)
    Text(
        text = "为了保存您的登录账号，需要处理必要的微信身份标识。不会读取聊天内容或全部好友。",
        style = MaterialTheme.typography.bodyLarge,
    )
    InfoPanel("您可以随时退出登录，也可以在设置中申请清除历史或注销账号。")
    Button(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        onClick = onGrant,
    ) {
        Text("同意并继续")
    }
    OutlinedButton(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        onClick = onExit,
    ) {
        Text("暂不同意并退出")
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
) {
    Text(text = "暂时没连接成功", style = MaterialTheme.typography.headlineSmall)
    ErrorPanel(message)
    Text(
        text = "请检查网络或稍后再试。不会自动重复发送任何操作。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        onClick = onRetry,
    ) {
        Text("重试")
    }
}
