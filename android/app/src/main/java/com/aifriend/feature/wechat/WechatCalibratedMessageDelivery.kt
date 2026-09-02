package com.aifriend.feature.wechat

import android.content.Context
import com.aifriend.BuildConfig
import com.aifriend.contract.model.WechatActionPlan
import com.aifriend.contract.model.WechatActionType
import com.aifriend.core.audio.CapturedAudio
import com.tencent.mm.opensdk.modelbase.BaseResp
import com.tencent.mm.opensdk.modelmsg.SendMessageToWX
import com.tencent.mm.opensdk.modelmsg.WXFileObject
import com.tencent.mm.opensdk.modelmsg.WXMediaMessage
import com.tencent.mm.opensdk.modelmsg.WXTextObject
import com.tencent.mm.opensdk.openapi.WXAPIFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** 当前一次微信公开分享页选择请求；定位明文只存当前进程内存。 */
data class WechatCalibratedMessageSelectionRequest(
    val planId: String,
    val transaction: String,
    val profile: WechatCalibrationProfile,
    val targetSearchLocator: CharArray,
    val wechatVersion: String,
    val openedAt: OffsetDateTime,
    val expiresAt: OffsetDateTime,
    internal val completion: CompletableDeferred<Boolean>,
) {
    override fun toString(): String =
        "WechatCalibratedMessageSelectionRequest(planId=$planId, transaction=<redacted>, " +
            "profileKey=${profile.key}, targetSearchLocator=<redacted>, openedAt=$openedAt, " +
            "expiresAt=$expiresAt)"
}

/** 分享页请求一次租出；新请求不得覆盖正在执行的旧请求。 */
@Singleton
class WechatCalibratedMessageSelectionBroker @Inject constructor() {
    private val pending = AtomicReference<WechatCalibratedMessageSelectionRequest?>(null)

    fun arm(
        plan: WechatActionPlan,
        transaction: String,
        profile: WechatCalibrationProfile,
        wechatVersion: String,
        now: OffsetDateTime,
    ): WechatCalibratedMessageSelectionRequest? {
        if (!profile.supportsMessage || !plan.expiresAt.isAfter(now)) return null
        val request = WechatCalibratedMessageSelectionRequest(
            planId = plan.planId,
            transaction = transaction,
            profile = profile,
            targetSearchLocator = plan.targetSearchLocator.toCharArray(),
            wechatVersion = wechatVersion,
            openedAt = now,
            expiresAt = plan.expiresAt,
            completion = CompletableDeferred(),
        )
        if (!pending.compareAndSet(null, request)) {
            request.targetSearchLocator.fill('\u0000')
            return null
        }
        return request
    }

    fun take(packageName: String, now: OffsetDateTime): WechatCalibratedMessageSelectionRequest? {
        if (packageName != WechatSemanticCallContract.WECHAT_PACKAGE) return null
        while (true) {
            val request = pending.get() ?: return null
            if (!request.expiresAt.isAfter(now) || now.isBefore(request.openedAt)) {
                if (pending.compareAndSet(request, null)) finish(request, false)
                continue
            }
            if (pending.compareAndSet(request, null)) return request
        }
    }

    fun isPending(): Boolean = pending.get() != null

    fun clear(transaction: String? = null) {
        while (true) {
            val request = pending.get() ?: return
            if (transaction != null && request.transaction != transaction) return
            if (pending.compareAndSet(request, null)) {
                finish(request, false)
                return
            }
        }
    }

    fun finish(request: WechatCalibratedMessageSelectionRequest, success: Boolean) {
        request.targetSearchLocator.fill('\u0000')
        request.completion.complete(success)
    }
}

/** 只以 transaction 关联 OpenSDK 回调，不保存消息或联系人。 */
@Singleton
class WechatMessageOpenSdkCallbackBroker @Inject constructor() {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Int>>()

    fun register(transaction: String): CompletableDeferred<Int>? {
        val deferred = CompletableDeferred<Int>()
        return if (pending.putIfAbsent(transaction, deferred) == null) deferred else null
    }

    fun complete(transaction: String?, errorCode: Int) {
        if (transaction.isNullOrBlank()) return
        pending.remove(transaction)?.complete(errorCode)
    }

    fun cancel(transaction: String) {
        pending.remove(transaction)?.cancel()
    }
}

/** 当前计划通过验签与精确校准后才创建一次性交付端口。 */
fun interface WechatMessageHandoffPortFactory {
    fun create(plan: WechatActionPlan): WechatMessageHandoffPort?
}

@Singleton
class AndroidWechatMessageHandoffPortFactory @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val proofVerifier: WechatTargetLocatorProofVerifier,
    private val runtimeVersionProvider: WechatRuntimeVersionProvider,
    private val fingerprintProvider: WechatCalibrationFingerprintProvider,
    private val profileRegistry: WechatCalibrationProfileRegistry,
    private val selectionBroker: WechatCalibratedMessageSelectionBroker,
    private val callbackBroker: WechatMessageOpenSdkCallbackBroker,
) : WechatMessageHandoffPortFactory {
    override fun create(plan: WechatActionPlan): WechatMessageHandoffPort? {
        val now = OffsetDateTime.now()
        if (plan.action != WechatActionType.SEND_AUDIO_AND_TEXT ||
            plan.audioObjectId.isNullOrBlank() ||
            BuildConfig.WECHAT_APP_ID.isBlank() ||
            proofVerifier.verify(plan, now) == null
        ) {
            return null
        }
        val version = runtimeVersionProvider.readCurrentVersion() ?: return null
        if (version != plan.targetLocatorProof.wechatVersion) return null
        val key = fingerprintProvider.current(version) ?: return null
        val profile = profileRegistry.findExact(key)?.takeIf { it.supportsMessage } ?: return null
        return AndroidWechatMessageHandoffPort(
            context = context,
            plan = plan,
            profile = profile,
            wechatVersion = version,
            selectionBroker = selectionBroker,
            callbackBroker = callbackBroker,
        )
    }
}

/** 从设置页显式打开一次不含用户数据的微信分享校准页。 */
fun interface WechatMessageCalibrationShareLauncher {
    fun launch(): Boolean
}

@Singleton
class AndroidWechatMessageCalibrationShareLauncher @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : WechatMessageCalibrationShareLauncher {
    override fun launch(): Boolean {
        if (BuildConfig.WECHAT_APP_ID.isBlank()) return false
        val api = WXAPIFactory.createWXAPI(context, BuildConfig.WECHAT_APP_ID, true)
        if (!api.registerApp(BuildConfig.WECHAT_APP_ID)) return false
        val request = SendMessageToWX.Req().apply {
            transaction = "aifriend_calibration_" +
                UUID.randomUUID().toString().replace("-", "")
            message = WXMediaMessage(WXTextObject(CALIBRATION_TEXT))
            scene = SendMessageToWX.Req.WXSceneSession
        }
        return runCatching { api.sendReq(request) }.getOrDefault(false)
    }

    private companion object {
        const val CALIBRATION_TEXT = "AI好友消息发送校准（记录点位时不会发送）"
    }
}

private class AndroidWechatMessageHandoffPort(
    context: Context,
    private val plan: WechatActionPlan,
    private val profile: WechatCalibrationProfile,
    private val wechatVersion: String,
    private val selectionBroker: WechatCalibratedMessageSelectionBroker,
    private val callbackBroker: WechatMessageOpenSdkCallbackBroker,
) : WechatMessageHandoffPort {
    private val api = WXAPIFactory.createWXAPI(context, BuildConfig.WECHAT_APP_ID, true)

    init {
        api.registerApp(BuildConfig.WECHAT_APP_ID)
    }

    override suspend fun handoffAudio(audio: CapturedAudio): WechatMessageHandoffOutcome {
        val bytes = audio.wavBytes.copyOf()
        return try {
            val media = WXMediaMessage(WXFileObject(bytes)).apply {
                title = AUDIO_TITLE
                description = AUDIO_DESCRIPTION
            }
            handoff(media)
        } finally {
            bytes.fill(0)
        }
    }

    override suspend fun handoffText(text: String): WechatMessageHandoffOutcome =
        handoff(WXMediaMessage(WXTextObject(text)))

    private suspend fun handoff(message: WXMediaMessage): WechatMessageHandoffOutcome {
        val now = OffsetDateTime.now()
        if (!plan.expiresAt.isAfter(now)) return WechatMessageHandoffOutcome.FAILED
        val transaction = "aifriend_msg_" + UUID.randomUUID().toString().replace("-", "")
        val callback = callbackBroker.register(transaction)
            ?: return WechatMessageHandoffOutcome.FAILED
        val selection = selectionBroker.arm(
            plan = plan,
            transaction = transaction,
            profile = profile,
            wechatVersion = wechatVersion,
            now = now,
        ) ?: run {
            callbackBroker.cancel(transaction)
            return WechatMessageHandoffOutcome.FAILED
        }
        val request = SendMessageToWX.Req().apply {
            this.transaction = transaction
            this.message = message
            scene = SendMessageToWX.Req.WXSceneSession
        }
        return try {
            val accepted = try {
                api.sendReq(request)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                false
            }
            if (!accepted) return WechatMessageHandoffOutcome.FAILED
            val selectionAccepted = withTimeoutOrNull(SELECTION_TIMEOUT.toMillis()) {
                selection.completion.await()
            } == true
            if (!selectionAccepted) return WechatMessageHandoffOutcome.FAILED
            val errorCode = withTimeoutOrNull(CALLBACK_TIMEOUT.toMillis()) { callback.await() }
            if (errorCode == BaseResp.ErrCode.ERR_OK) {
                WechatMessageHandoffOutcome.HANDED_TO_WECHAT
            } else {
                WechatMessageHandoffOutcome.FAILED
            }
        } finally {
            selectionBroker.clear(transaction)
            if (!selection.completion.isCompleted) {
                selectionBroker.finish(selection, false)
            }
            callbackBroker.cancel(transaction)
        }
    }

    private companion object {
        val SELECTION_TIMEOUT: Duration = Duration.ofSeconds(25)
        val CALLBACK_TIMEOUT: Duration = Duration.ofSeconds(20)
        const val AUDIO_TITLE = "AI好友原声.wav"
        const val AUDIO_DESCRIPTION = "点击可播放本次联系原声"
    }
}

/** 公开分享页只按当前精确档案执行一次联系人选择与确认。 */
@Singleton
class WechatCalibratedMessageSelectionExecutor @Inject constructor() {
    suspend fun execute(
        request: WechatCalibratedMessageSelectionRequest,
        packageName: String,
        currentWechatVersion: String,
        uiPort: WechatCalibratedCallUiPort,
    ): Boolean {
        if (packageName != WechatSemanticCallContract.WECHAT_PACKAGE ||
            currentWechatVersion != request.wechatVersion ||
            !request.profile.supportsMessage ||
            uiPort.currentFingerprint() != request.profile.key
        ) {
            return false
        }
        suspend fun tap(target: WechatCalibrationTarget, wait: Duration): Boolean {
            if (!valid(request, uiPort)) return false
            val point = request.profile.points[target]?.toPixels(
                request.profile.key.displayWidthPixels,
                request.profile.key.displayHeightPixels,
            ) ?: return false
            if (!uiPort.tap(point)) return false
            uiPort.waitForUi(wait)
            return valid(request, uiPort)
        }
        uiPort.waitForUi(INITIAL_WAIT)
        if (!tap(WechatCalibrationTarget.SHARE_SEARCH_ENTRY, PAGE_WAIT)) return false
        if (!tap(WechatCalibrationTarget.SHARE_SEARCH_INPUT, INPUT_WAIT)) return false
        if (!uiPort.setSensitiveSearchClipboard(request.targetSearchLocator)) return false
        val inputPoint = request.profile.points[WechatCalibrationTarget.SHARE_SEARCH_INPUT]
            ?.toPixels(request.profile.key.displayWidthPixels, request.profile.key.displayHeightPixels)
            ?: return false
        if (!valid(request, uiPort) || !uiPort.longPress(inputPoint)) return false
        uiPort.waitForUi(INPUT_WAIT)
        if (!tap(WechatCalibrationTarget.SHARE_SEARCH_PASTE, SEARCH_WAIT)) return false
        uiPort.clearSearchClipboard()
        if (!tap(WechatCalibrationTarget.SHARE_SEARCH_RESULT, PAGE_WAIT)) return false
        return tap(WechatCalibrationTarget.SHARE_SEND_CONFIRM, Duration.ZERO)
    }

    private fun valid(
        request: WechatCalibratedMessageSelectionRequest,
        uiPort: WechatCalibratedCallUiPort,
    ): Boolean {
        val now = uiPort.currentTime()
        return !now.isBefore(request.openedAt) && request.expiresAt.isAfter(now) &&
            uiPort.isWechatForeground() && uiPort.currentFingerprint() == request.profile.key
    }

    private companion object {
        val INITIAL_WAIT: Duration = Duration.ofMillis(700)
        val PAGE_WAIT: Duration = Duration.ofMillis(900)
        val INPUT_WAIT: Duration = Duration.ofMillis(450)
        val SEARCH_WAIT: Duration = Duration.ofMillis(1_000)
    }
}
