package com.aifriend.app.ui.profile

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.aifriend.app.ui.components.ActionCard
import com.aifriend.app.ui.components.ActionTone
import com.aifriend.app.ui.components.ElderPage
import com.aifriend.app.ui.components.PageTitle
import com.aifriend.app.ui.components.SectionCard

/** 我的页面集中承载个性化、设置和隐私能力。 */
@Composable
internal fun ProfileScreen(
    onOpenKnowledge: () -> Unit,
    onOpenWakeWord: () -> Unit,
    onOpenSafetyCommands: () -> Unit,
    onOpenVoiceCollection: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRecentTaskResults: () -> Unit,
    onOpenTaskHistoryDeletion: () -> Unit,
    onOpenAccountClosure: () -> Unit,
    onExit: () -> Unit,
) {
    var exitConfirmationRequested by remember { mutableStateOf(false) }
    fun handleExitConfirmation(action: ProfileExitConfirmationAction) {
        val decision = resolveProfileExitConfirmation(exitConfirmationRequested, action)
        exitConfirmationRequested = decision.confirmationRequested
        if (decision.exitRequested) onExit()
    }
    ElderPage {
        PageTitle("我的", "调整小友并管理个人数据")
        SectionCard(title = "知识与亲友查询") {
            ActionCard("知识问答与关系查询", "独立授权，只读查询，不发送消息或拨号", onOpenKnowledge)
        }
        SectionCard(
            title = "语音与个性化",
            support = "录制个人说法，帮助小友更懂您。",
        ) {
            ActionCard("录制小友唤醒词", "每遍说一次“小友”，只加密保存在本机", onOpenWakeWord)
            ActionCard("安全指令与确认词", "录制四类动作说法，并查看确认与否认短词", onOpenSafetyCommands)
            ActionCard("测试语音采集", "仅保存您明确提交的测试样本", onOpenVoiceCollection)
        }
        SectionCard(
            title = "设置与帮助",
            support = "调整显示、播报和查看离线帮助。",
        ) {
            ActionCard("打开设置与帮助", "大字、高对比度、语速和能力状态", onOpenSettings)
        }
        SectionCard(
            title = "任务记录",
            support = "只显示动作、处理结果和时间。",
        ) {
            ActionCard("最近任务结果", "不显示录音、消息内容或联系人", onOpenRecentTaskResults)
        }
        SectionCard(
            title = "隐私与账号",
            support = "清除、退出和注销操作都会再次确认。",
        ) {
            ActionCard("清除任务录音与历史", "清除后无法恢复", onOpenTaskHistoryDeletion)
            ActionCard(
                title = "退出本机登录",
                support = "退出后需要重新登录，不会删除账号数据",
                onClick = {
                    handleExitConfirmation(ProfileExitConfirmationAction.REQUEST)
                },
            )
            ActionCard(
                title = "永久注销账号",
                support = "删除账号及相关数据",
                onClick = onOpenAccountClosure,
                tone = ActionTone.DESTRUCTIVE,
            )
        }
    }
    if (exitConfirmationRequested) {
        AlertDialog(
            onDismissRequest = {
                handleExitConfirmation(ProfileExitConfirmationAction.CANCEL)
            },
            title = { Text("确认退出本机登录？") },
            text = {
                Text(
                    "退出后需要重新登录，正在运行的小友守护也会停止。" +
                        "不会删除账号、联系人或服务器上的数据。",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        handleExitConfirmation(ProfileExitConfirmationAction.CONFIRM)
                    },
                ) {
                    Text("确认退出")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        handleExitConfirmation(ProfileExitConfirmationAction.CANCEL)
                    },
                ) {
                    Text("继续使用")
                }
            },
        )
    }
}

/** 我的页退出登录确认动作。 */
internal enum class ProfileExitConfirmationAction {
    REQUEST,
    CANCEL,
    CONFIRM,
}

/** 退出登录确认结果；只有已展示确认后再次确认才允许退出。 */
internal data class ProfileExitConfirmationDecision(
    val confirmationRequested: Boolean,
    val exitRequested: Boolean,
)

/** 将退出登录的首次请求、取消和明确确认收口为有限状态。 */
internal fun resolveProfileExitConfirmation(
    confirmationRequested: Boolean,
    action: ProfileExitConfirmationAction,
): ProfileExitConfirmationDecision = when (action) {
    ProfileExitConfirmationAction.REQUEST -> ProfileExitConfirmationDecision(
        confirmationRequested = true,
        exitRequested = false,
    )
    ProfileExitConfirmationAction.CANCEL -> ProfileExitConfirmationDecision(
        confirmationRequested = false,
        exitRequested = false,
    )
    ProfileExitConfirmationAction.CONFIRM -> ProfileExitConfirmationDecision(
        confirmationRequested = false,
        exitRequested = confirmationRequested,
    )
}
