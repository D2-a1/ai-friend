package com.aifriend.feature.knowledge

import androidx.lifecycle.ViewModel
import com.aifriend.contract.model.*
import com.aifriend.feature.auth.AuthSessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class KnowledgePageState(
    val conversation: KnowledgeUiState = KnowledgeUiState(),
    val draft: String = "",
    val grants: Set<KnowledgeConsentPurpose> = emptySet(),
    val loadingConsents: Boolean = false,
    val consentsKnown: Boolean = false,
    val pendingConsent: KnowledgeConsentPurpose? = null,
    val failure: KnowledgeFailure? = null,
    val voice: KnowledgeVoiceState = KnowledgeVoiceState(),
    val audioCleanupPending: Boolean = false,
) {
    override fun toString() = "KnowledgePageState[phase=${conversation.phase}]"
}

/** Activity可保留VM，但正文、授权弹窗及会话仅由可见页面持有，不使用SavedState或磁盘。 */
@HiltViewModel
class KnowledgeViewModel @Inject constructor(
    private val repository: KnowledgeRepository,
    private val consents: KnowledgeConsentAccess,
    private val auth: AuthSessionRepository,
    private val voices: KnowledgeVoiceFactory,
) : ViewModel() {
    private val mutableState = MutableStateFlow(KnowledgePageState())
    val state = mutableState.asStateFlow()
    private var page: Page? = null
    private var cleanupDebt = false
    private var closingPages = 0
    private var voiceForeground = false
    private var closingAudio = 0
    private var audioCleanupDebt = false
    private class Page(val login: KnowledgeLogin, val scope: CoroutineScope, val conversation: KnowledgeConversation) {
        var consentJob: Job? = null
        var voice: KnowledgeVoiceFlow? = null
        var voiceObserver: Job? = null
        var voiceMonitor: Job? = null
        val audioClosures = mutableListOf<Job>()
        var spokenKey: String? = null
        var awaitingVoiceAnswer = false
    }

    fun enter() {
        if (page != null) { current(); return }
        val identity = auth.session.value?.takeIf { it.userStatus == "ACTIVE" }
        if (identity == null) { mutableState.value = KnowledgePageState(failure = KnowledgeFailure.AUTH_REQUIRED); return }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val p = Page(KnowledgeLogin(identity, auth.loginEpoch), scope, KnowledgeConversation(repository, auth, scope))
        page = p
        mutableState.value = KnowledgePageState(conversation = KnowledgeUiState(cleanupPending = cleanupDebt || closingPages > 0),
            audioCleanupPending = closingAudio > 0 || audioCleanupDebt)
        scope.launch {
            p.conversation.state.collect {
                if (page === p) mutableState.value = state.value.copy(conversation = it.copy(cleanupPending = it.cleanupPending || cleanupDebt || closingPages > 0))
                if (page === p) presentVoiceResult(p)
            }
        }
        scope.launch { auth.session.collect { if (page === p) current() } }
        reloadConsents()
    }

    /** 离开/后台/切号先清空UI，独立旧scope仅保留有界关闭任务，不接受迟到结果。 */
    fun leave() {
        val old = page
        if (old != null) stopVoice(old)
        voiceForeground = false
        page = null
        mutableState.value = KnowledgePageState()
        if (old == null) return
        old.consentJob?.cancel()
        closingPages++
        old.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                old.conversation.shutdown(); cleanupDebt = cleanupDebt || old.conversation.state.value.cleanupPending
                old.audioClosures.toList().joinAll()
            }
            finally {
                closingPages--
                val visible = page?.conversation?.state?.value ?: KnowledgeUiState()
                mutableState.value = state.value.copy(conversation = visible.copy(cleanupPending = visible.cleanupPending || cleanupDebt || closingPages > 0))
                old.scope.cancel()
            }
        }
    }

    fun reloadConsents() {
        val p = current() ?: return
        if (p.consentJob?.isActive == true) return
        stopVoice(p)
        // 权限重新读取期间，不继续旧会话或保留旧关系结果。
        p.conversation.cancel()
        mutableState.value = state.value.copy(conversation = p.conversation.state.value, grants = emptySet(), consentsKnown = false, loadingConsents = true, pendingConsent = null, draft = "", failure = null)
        p.consentJob = p.scope.launch {
            try {
                val grants = consents.list(p.login)
                if (current() === p) mutableState.value = state.value.copy(grants = grants, consentsKnown = true)
            } catch (e: CancellationException) { throw e }
                catch (e: KnowledgeException) { if (page === p) mutableState.value = state.value.copy(failure = e.failure) }
            finally { if (page === p) mutableState.value = state.value.copy(loadingConsents = false) }
        }
    }

    fun requestConsent(purpose: KnowledgeConsentPurpose) {
        if (current() == null || !state.value.consentsKnown || state.value.loadingConsents) return
        page?.let { stopVoice(it) }
        mutableState.value = state.value.copy(pendingConsent = purpose)
    }
    fun dismissConsent() { mutableState.value = state.value.copy(pendingConsent = null) }
    fun confirmConsent() {
        val p = current() ?: return
        val purpose = state.value.pendingConsent ?: return
        if (state.value.loadingConsents || !state.value.consentsKnown) return
        val decision = if (purpose in state.value.grants) ConsentDecision.REVOKED else ConsentDecision.GRANTED
        stopVoice(p)
        p.conversation.cancel()
        mutableState.value = state.value.copy(conversation = p.conversation.state.value, pendingConsent = null, draft = "", grants = emptySet(), consentsKnown = false, loadingConsents = true, failure = null)
        p.consentJob = p.scope.launch {
            try {
                consents.change(p.login, purpose, decision)
                val grants = consents.list(p.login)
                if (current() === p) mutableState.value = state.value.copy(grants = grants, consentsKnown = true)
            } catch (e: CancellationException) { throw e }
                catch (e: KnowledgeException) { if (page === p) mutableState.value = state.value.copy(failure = e.failure) }
            finally { if (page === p) mutableState.value = state.value.copy(loadingConsents = false) }
        }
    }

    fun start(purpose: AssistantPurpose) {
        val p = allowed(purpose) ?: return
        if (p.conversation.state.value.canQueryOriginal) return
        stopVoice(p)
        mutableState.value = state.value.copy(draft = "", failure = null)
        p.conversation.start(purpose)
    }
    fun edit(value: String) {
        if (current() == null) return
        val limit = if (state.value.conversation.purpose == AssistantPurpose.CONTACT_GRAPH) 100 else 500
        if (value.codePointCount(0, value.length) <= limit) mutableState.value = state.value.copy(draft = value)
    }
    fun submit(appVersion: Long) {
        val p = allowed(state.value.conversation.purpose) ?: return
        val text = state.value.draft
        if (text.isBlank()) return
        stopVoice(p)
        val question = if (state.value.conversation.purpose == AssistantPurpose.PUBLIC_KNOWLEDGE)
            KnowledgeQuestion.Text(text, "zh-CN", appVersion)
        else KnowledgeQuestion.Graph(KnowledgeGraphQuery(KnowledgeGraphQueryType.FIND_CONTACT_BY_ALIAS, aliasText = text), "zh-CN", appVersion)
        p.conversation.submit(question)
        mutableState.value = state.value.copy(draft = "")
    }
    fun graph(query: KnowledgeGraphQuery, appVersion: Long) {
        val p = allowed(AssistantPurpose.CONTACT_GRAPH) ?: return
        if (state.value.conversation.purpose != AssistantPurpose.CONTACT_GRAPH) return
        stopVoice(p)
        p.conversation.submit(KnowledgeQuestion.Graph(query, "zh-CN", appVersion))
    }
    fun queryOriginal() { allowed(state.value.conversation.purpose)?.conversation?.queryOriginal() }
    fun cancel() {
        val p = current() ?: return
        stopVoice(p)
        p.conversation.cancel()
        mutableState.value = state.value.copy(conversation = p.conversation.state.value, draft = "")
    }

    /** 权限弹窗、后台或锁屏不得自动恢复；再次录音必须由明确按钮发起。 */
    fun setVoiceForeground(visible: Boolean) {
        voiceForeground = visible
        if (!visible) page?.let { stopVoice(it) }
    }

    fun voiceUnavailable(): KnowledgeVoiceFailure? = voices.unavailable()

    fun listen(appVersion: Long) {
        val p = allowed(state.value.conversation.purpose) ?: return
        if (!voiceForeground || !canSubmit(p) || closingAudio > 0 || audioCleanupDebt) return
        voices.unavailable()?.let {
            mutableState.value = state.value.copy(voice = KnowledgeVoiceState(KnowledgeVoicePhase.ERROR, it)); return
        }
        var flow = p.voice
        if (flow == null || flow.state.value.phase == KnowledgeVoicePhase.CLOSED) {
            flow = voices.create(p.scope, { voiceCurrent(p) }, { text -> submitVoice(p, text, appVersion) }, { cancel() })
            p.voice = flow
            val owned = flow
            p.voiceObserver = p.scope.launch {
                owned.state.collect {
                    if (page === p && p.voice === owned) {
                        mutableState.value = state.value.copy(voice = it)
                        presentVoiceResult(p)
                    }
                }
            }
            p.voiceMonitor = p.scope.launch {
                while (page === p && p.voice === owned) {
                    if (!voiceCurrent(p)) { stopVoice(p); break }
                    delay(250)
                }
            }
        }
        flow.listen()
    }

    fun stopListening() { page?.let { stopVoice(it) } }

    private fun canSubmit(p: Page): Boolean = p.conversation.state.value.let {
        !it.canQueryOriginal && it.phase in setOf(KnowledgePhase.READY, KnowledgePhase.PRESENTING, KnowledgePhase.ERROR)
    }

    private fun voiceCurrent(p: Page): Boolean = current() === p && voiceForeground &&
        state.value.consentsKnown && !state.value.loadingConsents && state.value.pendingConsent == null &&
        (p.conversation.state.value.purpose != AssistantPurpose.CONTACT_GRAPH || KnowledgeConsentPurpose.GRAPH in state.value.grants) &&
        voices.unavailable() == null

    private fun submitVoice(p: Page, text: String, appVersion: Long): KnowledgeVoiceSubmission {
        if (!voiceCurrent(p) || !canSubmit(p)) return KnowledgeVoiceSubmission.Rejected
        val question = if (p.conversation.state.value.purpose == AssistantPurpose.CONTACT_GRAPH) {
            val query = KnowledgeGraphSpeechParser.parse(text, p.conversation.state.value.answer?.candidates.orEmpty())
                ?: return KnowledgeVoiceSubmission.UnsupportedGraphQuestion
            KnowledgeQuestion.Graph(query, "zh-CN", appVersion)
        } else KnowledgeQuestion.Text(text, "zh-CN", appVersion)
        // 先由本次真实提交取得回答资格，不能把进入WAITING_RESULT时仍在屏幕上的旧答案播报。
        p.awaitingVoiceAnswer = true
        p.conversation.submit(question)
        return if (p.conversation.state.value.phase in setOf(KnowledgePhase.SUBMITTING, KnowledgePhase.PROCESSING, KnowledgePhase.PRESENTING))
            KnowledgeVoiceSubmission.Submitted else KnowledgeVoiceSubmission.Rejected
    }

    private fun presentVoiceResult(p: Page) {
        val flow = p.voice ?: return
        if (!p.awaitingVoiceAnswer || flow.state.value.phase != KnowledgeVoicePhase.WAITING_RESULT || !voiceCurrent(p)) return
        val result = p.conversation.state.value
        if (result.phase == KnowledgePhase.ERROR) { p.awaitingVoiceAnswer = false; flow.pauseForResultFailure(); return }
        val answer = result.answer ?: return
        if (result.phase != KnowledgePhase.PRESENTING || p.spokenKey == answer.requestKey) return
        p.spokenKey = answer.requestKey
        p.awaitingVoiceAnswer = false
        // 仅受检纯文本。关系候选使用同一受检结果，不访问联系人或执行端口。
        val body = if (result.purpose == AssistantPurpose.CONTACT_GRAPH && answer.candidates.isNotEmpty())
            answer.candidates.mapIndexed { i, c -> "第${i + 1}位，${c.aliases.joinToString("、")}" }.joinToString("。")
        else answer.text
        flow.present(body, continueListening = answer.status in setOf(AssistantResultStatus.ANSWERED,
            AssistantResultStatus.EVIDENCE_ONLY, AssistantResultStatus.NEEDS_CLARIFICATION))
    }

    private fun stopVoice(p: Page) {
        val old = p.voice ?: return
        p.voice = null; p.voiceObserver?.cancel(); p.voiceMonitor?.cancel(); p.spokenKey = null; p.awaitingVoiceAnswer = false
        old.stop(); closingAudio++
        if (page === p) mutableState.value = state.value.copy(voice = old.state.value, audioCleanupPending = true)
        val cleanup = p.scope.launch(start = CoroutineStart.LAZY) {
            try { old.shutdown() }
            finally {
                audioCleanupDebt = audioCleanupDebt || old.state.value.failure == KnowledgeVoiceFailure.CLEANUP_FAILED
                closingAudio--
                mutableState.value = state.value.copy(audioCleanupPending = closingAudio > 0 || audioCleanupDebt,
                    voice = if (audioCleanupDebt) KnowledgeVoiceState(KnowledgeVoicePhase.ERROR, KnowledgeVoiceFailure.CLEANUP_FAILED) else state.value.voice)
            }
        }
        p.audioClosures += cleanup
        cleanup.invokeOnCompletion { p.audioClosures.remove(cleanup) }
        cleanup.start()
    }
    private fun allowed(purpose: AssistantPurpose): Page? {
        val p = current() ?: return null
        if (!state.value.consentsKnown || state.value.loadingConsents || state.value.pendingConsent != null) return null
        if (purpose == AssistantPurpose.CONTACT_GRAPH && KnowledgeConsentPurpose.GRAPH !in state.value.grants) {
            mutableState.value = state.value.copy(failure = KnowledgeFailure.ACCESS_DENIED); return null
        }
        return p
    }
    private fun current(): Page? {
        val p = page ?: return null
        if (auth.loginEpoch != p.login.epoch || auth.session.value?.userId != p.login.expected.userId || auth.session.value?.userStatus != "ACTIVE") {
            leave(); mutableState.value = KnowledgePageState(failure = KnowledgeFailure.AUTH_CHANGED); return null
        }
        return p
    }
    override fun onCleared() { leave(); super.onCleared() }
}
