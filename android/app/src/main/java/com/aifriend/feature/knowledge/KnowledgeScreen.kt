package com.aifriend.feature.knowledge

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aifriend.BuildConfig
import com.aifriend.app.ui.components.*
import com.aifriend.contract.model.*

/** 独立文字/语音入口；问答与引用只读，不执行链接、模型指令或联系人动作。 */
@Composable
fun KnowledgeRoute(viewModel: KnowledgeViewModel, onBack: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.enter()
                Lifecycle.Event.ON_RESUME -> viewModel.setVoiceForeground(true)
                Lifecycle.Event.ON_PAUSE -> viewModel.setVoiceForeground(false)
                Lifecycle.Event.ON_STOP -> viewModel.leave()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) viewModel.enter()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) viewModel.setVoiceForeground(true)
        onDispose { lifecycle.removeObserver(observer); viewModel.leave() }
    }
    val state by viewModel.state.collectAsState()
    // 即使回调来自旧页面/迟到授权也不启动录音；用户返回后须再次明确点击。
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }
    val conversation = state.conversation
    val busy = conversation.phase in setOf(KnowledgePhase.OPENING, KnowledgePhase.SUBMITTING, KnowledgePhase.PROCESSING)
    val available = state.consentsKnown && !state.loadingConsents && state.pendingConsent == null
    val canAsk = available && !busy && !conversation.canQueryOriginal && conversation.phase in setOf(KnowledgePhase.READY, KnowledgePhase.PRESENTING, KnowledgePhase.ERROR)
    val version = BuildConfig.VERSION_CODE.toLong()
    ElderPage {
        PageTitle("知识问答与亲友查询", "独立只读功能，不发消息、不拨号。支持文字或本机语音输入。")
        KnowledgeButton("返回", true) { viewModel.leave(); onBack() }
        SectionCard(title = "独立用途授权") {
            KnowledgeConsentPurpose.entries.forEach { purpose ->
                Text(purpose.title + if (!state.consentsKnown) "：待核实" else if (purpose in state.grants) "：已同意" else "：未同意")
                KnowledgeButton(if (purpose in state.grants) "撤回${purpose.title}" else "查看并选择同意", available) { viewModel.requestConsent(purpose) }
            }
            Text("公开问题请勿包含私人信息。未同意外部模型处理时，不会外发；服务端模型模式可能不可用。")
            KnowledgeButton(if (state.loadingConsents) "正在读取授权" else "刷新授权状态", !state.loadingConsents) { viewModel.reloadConsents() }
        }
        state.failure?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
        SectionCard(title = "选择用途并开始新会话") {
            KnowledgeButton("公开知识问答", available && !busy && !conversation.canQueryOriginal) { viewModel.start(AssistantPurpose.PUBLIC_KNOWLEDGE) }
            KnowledgeButton("亲友关系查询", available && KnowledgeConsentPurpose.GRAPH in state.grants && !busy && !conversation.canQueryOriginal) { viewModel.start(AssistantPurpose.CONTACT_GRAPH) }
        }
        Text(when (conversation.phase) {
            KnowledgePhase.IDLE -> "请选择用途"
            KnowledgePhase.OPENING -> "正在建立会话"
            KnowledgePhase.READY -> "可以输入"
            KnowledgePhase.SUBMITTING, KnowledgePhase.PROCESSING -> "正在处理，请稍候"
            KnowledgePhase.PRESENTING -> "本轮结果"
            KnowledgePhase.ERROR -> "本轮未完成"
            KnowledgePhase.CANCELLED -> "本次会话已在本机停止"
            KnowledgePhase.CLOSED -> "已离开会话"
        })
        OutlinedTextField(value = state.draft, onValueChange = viewModel::edit, enabled = canAsk,
            label = { Text(if (conversation.purpose == AssistantPurpose.CONTACT_GRAPH) "输入亲友称呼（最多100字）" else "输入公开知识问题（最多500字）") },
            modifier = Modifier.fillMaxWidth(), maxLines = 6)
        KnowledgeButton("提交", canAsk && state.draft.isNotBlank()) { viewModel.submit(version) }
        SectionCard(title = "语音提问") {
            Text("仅在本机转写，录音不上传。授权麦克风后请再次点击开始；提示播完后再说话。")
            if (conversation.purpose == AssistantPurpose.CONTACT_GRAPH) Text(KnowledgeGraphSpeechParser.HELP)
            Text(when (state.voice.phase) {
                KnowledgeVoicePhase.IDLE, KnowledgeVoicePhase.CLOSED -> "未在录音"
                KnowledgeVoicePhase.ACQUIRING -> "正在等待音频资源"
                KnowledgeVoicePhase.SPEAKING -> "正在播报，请先听完"
                KnowledgeVoicePhase.LISTENING -> "正在听，请说您的问题"
                KnowledgeVoicePhase.TRANSCRIBING -> "正在本机转写"
                KnowledgeVoicePhase.WAITING_RESULT -> "正在等待本轮结果"
                KnowledgeVoicePhase.AWAITING_CONTINUE -> "已暂停，可点击继续提问"
                KnowledgeVoicePhase.ERROR -> "语音暂未完成，可继续使用文字"
            })
            state.voice.failure?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
            if (state.audioCleanupPending) Text("上一轮音频释放尚未确认，暂不能再次开麦。")
            val voiceIdle = state.voice.phase in setOf(KnowledgeVoicePhase.IDLE, KnowledgeVoicePhase.CLOSED,
                KnowledgeVoicePhase.AWAITING_CONTINUE, KnowledgeVoicePhase.ERROR)
            KnowledgeButton("开始或继续语音提问", canAsk && voiceIdle && !state.audioCleanupPending) {
                if (viewModel.voiceUnavailable() == KnowledgeVoiceFailure.MICROPHONE_DENIED)
                    permission.launch(Manifest.permission.RECORD_AUDIO)
                else viewModel.listen(version)
            }
            KnowledgeButton("停止语音（保留文字会话）", !voiceIdle) { viewModel.stopListening() }
        }
        if (conversation.purpose == AssistantPurpose.CONTACT_GRAPH) KnowledgeButton("列出已绑定亲友", canAsk) {
            viewModel.graph(KnowledgeGraphQuery(KnowledgeGraphQueryType.LIST_CONTACTS), version)
        }
        if (conversation.canQueryOriginal) KnowledgeButton("查询刚才的问题结果（不重新提交）", available && !busy) { viewModel.queryOriginal() }
        conversation.failure?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
        if (conversation.cleanupPending) Text("远端关闭尚未确认；本页不会继续处理旧会话，请勿把此提示理解为数据已删除。")
        conversation.answer?.let { answer ->
            SectionCard(title = when (answer.answerMode) {
                AssistantAnswerMode.GENERATED -> "模型生成回答，请结合原文核对"
                AssistantAnswerMode.EXTRACTIVE -> "知识原文摘录"
                AssistantAnswerMode.TEMPLATE -> "亲友关系查询结果"
                AssistantAnswerMode.NONE -> "处理说明"
            }) {
                Text(answer.text)
                answer.citations.forEach { citation -> Text("参考：${citation.title}"); Text(citation.text) }
                answer.candidates.forEachIndexed { index, candidate ->
                    Text("第${index + 1}位亲友：" + candidate.aliases.joinToString("、"))
                    KnowledgeButton("查看第${index + 1}位亲友的称呼", canAsk) {
                        viewModel.graph(KnowledgeGraphQuery(KnowledgeGraphQueryType.LIST_ALIASES, contactId = candidate.contactId), version)
                    }
                }
            }
        }
        KnowledgeButton("取消并清空本次会话", true) { viewModel.cancel() }
    }
    state.pendingConsent?.let { purpose ->
        val revoke = purpose in state.grants
        AlertDialog(onDismissRequest = viewModel::dismissConsent,
            title = { Text(if (revoke) "确认撤回${purpose.title}？" else purpose.title) },
            text = { Text(if (revoke) "撤回后将停止并清空当前问答，后续相应用途不可使用。已经完成的外部处理无法撤销。" else purpose.explanation) },
            confirmButton = { Button(onClick = viewModel::confirmConsent) { Text(if (revoke) "确认撤回" else "明确同意") } },
            dismissButton = { OutlinedButton(onClick = viewModel::dismissConsent) { Text("取消") } })
    }
}

@Composable
private fun KnowledgeButton(text: String, enabled: Boolean, click: () -> Unit) {
    OutlinedButton(onClick = click, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(text) }
}
