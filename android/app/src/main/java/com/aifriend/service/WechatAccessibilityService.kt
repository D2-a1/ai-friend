package com.aifriend.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.aifriend.feature.wechat.AndroidWechatCalibrationCaptureCoordinator
import com.aifriend.feature.wechat.AndroidWechatCalibratedCallUiPort
import com.aifriend.feature.wechat.AndroidWechatSemanticCallUiPortFactory
import com.aifriend.feature.wechat.AndroidWechatSampleCaptureCoordinator
import com.aifriend.feature.wechat.AndroidWechatLocalContactVerificationCoordinator
import com.aifriend.feature.wechat.WechatAccessibilityPageReader
import com.aifriend.feature.wechat.WechatCallChoiceActionBroker
import com.aifriend.feature.wechat.WechatCallChoiceActionExecutor
import com.aifriend.feature.wechat.WechatCallChoiceActionOutcome
import com.aifriend.feature.wechat.WechatCallChoiceActionStatus
import com.aifriend.feature.wechat.WechatCallStartedTransitionBroker
import com.aifriend.feature.wechat.WechatCalibrationCaptureRequest
import com.aifriend.feature.wechat.WechatCalibrationFingerprintProvider
import com.aifriend.feature.wechat.WechatCalibrationRecordResult
import com.aifriend.feature.wechat.WechatCalibrationTarget
import com.aifriend.feature.wechat.WechatCalibrationProfileRegistry
import com.aifriend.feature.wechat.WechatMessageDiagnostics
import com.aifriend.feature.wechat.WechatCalibratedCallExecutionBroker
import com.aifriend.feature.wechat.WechatCalibratedCallExecutionExecutor
import com.aifriend.feature.wechat.WechatCalibratedMessageSelectionBroker
import com.aifriend.feature.wechat.WechatCalibratedMessageSelectionExecutor
import com.aifriend.feature.wechat.WechatDirectChatTransitionBroker
import com.aifriend.feature.wechat.WechatDebugNodeProbe
import com.aifriend.feature.wechat.WechatPageObservationBroker
import com.aifriend.feature.wechat.WechatSemanticCallExecutionBroker
import com.aifriend.feature.wechat.WechatSemanticCallExecutionExecutor
import com.aifriend.feature.wechat.WechatSemanticCallExecutionOutcome
import com.aifriend.feature.wechat.WechatSemanticCallExecutionStatus
import com.aifriend.feature.wechat.WechatRuntimeVersionProvider
import com.aifriend.feature.wechat.WechatVerifiedContactProfileActionBroker
import com.aifriend.feature.wechat.WechatVerifiedContactProfileActionExecutor
import com.aifriend.feature.wechat.WechatVerifiedContactProfileActionOutcome
import com.aifriend.feature.wechat.WechatVerifiedContactProfileActionStatus
import com.aifriend.feature.wechat.calibrationDisplayName
import com.aifriend.feature.wechat.isCallAction
import com.aifriend.feature.guardian.GuardianWechatCallAudioCoordinator
import dagger.hilt.android.AndroidEntryPoint
import java.time.OffsetDateTime
import java.io.FileDescriptor
import java.io.PrintWriter
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 微信受限无障碍页面服务。
 *
 * 只有当前进程五秒观察门闩有效且事件包名精确为微信时才读取窄证据；二次准入通过后，
 * 只允许消费一次资料页动作请求，并在点击前重新验证同一资料页；点击后只用签名结构短时
 * 确认聊天页或通话选择页。通话最终点击前先释放守护麦克风；不读取聊天正文、不截图、
 * 不持久化节点、不调用手势，也不发送任何消息内容。
 *
 * @author codex
 * @since 2026-07-25
 */
@AndroidEntryPoint
class WechatAccessibilityService : AccessibilityService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var actionRequestJob: Job? = null
    private var callChoiceRequestJob: Job? = null
    private var calibrationStateJob: Job? = null
    private var calibratedCallExecutionJob: Job? = null
    private var calibratedMessageSelectionJob: Job? = null
    private var semanticCallChoiceJob: Job? = null
    private var calibrationControlView: View? = null
    private var calibrationTapView: View? = null
    private var displayedCalibrationTarget: WechatCalibrationTarget? = null

    @Inject
    lateinit var observationBroker: WechatPageObservationBroker

    @Inject
    lateinit var pageReader: WechatAccessibilityPageReader

    @Inject
    lateinit var sampleCaptureCoordinator: AndroidWechatSampleCaptureCoordinator

    @Inject
    lateinit var localContactVerificationCoordinator: AndroidWechatLocalContactVerificationCoordinator

    @Inject
    lateinit var actionBroker: WechatVerifiedContactProfileActionBroker

    @Inject
    lateinit var actionExecutor: WechatVerifiedContactProfileActionExecutor

    @Inject
    lateinit var directChatTransitionBroker: WechatDirectChatTransitionBroker

    @Inject
    lateinit var callChoiceActionBroker: WechatCallChoiceActionBroker

    @Inject
    lateinit var callChoiceActionExecutor: WechatCallChoiceActionExecutor

    @Inject
    lateinit var callStartedTransitionBroker: WechatCallStartedTransitionBroker

    @Inject
    lateinit var guardianWechatCallAudioCoordinator: GuardianWechatCallAudioCoordinator

    @Inject
    lateinit var semanticCallExecutionBroker: WechatSemanticCallExecutionBroker

    @Inject
    lateinit var semanticCallExecutionExecutor: WechatSemanticCallExecutionExecutor

    @Inject
    lateinit var semanticCallUiPortFactory: AndroidWechatSemanticCallUiPortFactory

    @Inject
    lateinit var wechatRuntimeVersionProvider: WechatRuntimeVersionProvider

    @Inject
    lateinit var calibrationCaptureCoordinator: AndroidWechatCalibrationCaptureCoordinator

    @Inject
    lateinit var calibrationFingerprintProvider: WechatCalibrationFingerprintProvider

    @Inject
    lateinit var calibrationProfileRegistry: WechatCalibrationProfileRegistry

    /** 仅系统 Service dump 通道读取；不新增导出组件、不启动任务、不写入或清空档案。 */
    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>?) {
        if (args?.contains("--wechat-message-diagnostics") != true) {
            super.dump(fd, writer, args)
            return
        }
        writer.println("AI_FRIEND_MESSAGE_DIAGNOSTICS_V1")
        WechatMessageDiagnostics.snapshot().forEach(writer::println)
        val version = runCatching { wechatRuntimeVersionProvider.readCurrentVersion() }.getOrNull()
        val key = version?.let { runCatching { calibrationFingerprintProvider.current(it) }.getOrNull() }
        val profile = key?.let { runCatching { calibrationProfileRegistry.findExact(it) }.getOrNull() }
        writer.println("currentProfile=${profile != null}")
        if (profile != null) {
            writer.println("width=${profile.key.displayWidthPixels} height=${profile.key.displayHeightPixels}")
            profile.points.forEach { (target, normalized) ->
                val point = normalized.toPixels(profile.key.displayWidthPixels, profile.key.displayHeightPixels)
                writer.println("savedPoint=${target.name} x=${point.x} y=${point.y}")
            }
            if (args.contains("--inspect-send-nodes")) {
                val point = profile.points[WechatCalibrationTarget.SHARE_SEND_CONFIRM]
                    ?.toPixels(profile.key.displayWidthPixels, profile.key.displayHeightPixels)
                // 仅显式诊断参数调用节点验证，绝不进入消息 broker、SDK 或点击执行器。
                val check = if (point != null && ::guardianWechatCallAudioCoordinator.isInitialized) {
                    runCatching {
                        AndroidWechatCalibratedCallUiPort(this, wechatRuntimeVersionProvider,
                            calibrationFingerprintProvider, guardianWechatCallAudioCoordinator)
                            .readMessageSendButtonNodes(point)?.toString() ?: "VISUAL_REQUIRED"
                    }.getOrDefault("UNAVAILABLE")
                } else "UNAVAILABLE"
                writer.println("currentSendNodeCheck=$check")
                WechatMessageDiagnostics.snapshot().takeLast(3).forEach(writer::println)
            }
        }
    }

    @Inject
    lateinit var calibratedCallExecutionBroker: WechatCalibratedCallExecutionBroker

    @Inject
    lateinit var calibratedCallExecutionExecutor: WechatCalibratedCallExecutionExecutor

    @Inject
    lateinit var calibratedMessageSelectionBroker: WechatCalibratedMessageSelectionBroker

    @Inject
    lateinit var calibratedMessageSelectionExecutor: WechatCalibratedMessageSelectionExecutor

    override fun onServiceConnected() {
        super.onServiceConnected()
        WechatDebugNodeProbe.attach(this)
        actionRequestJob?.cancel()
        actionRequestJob = serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
            actionBroker.requests.collect(::executePendingAction)
        }
        callChoiceRequestJob?.cancel()
        callChoiceRequestJob = serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
            callChoiceActionBroker.requests.collect(::executePendingCallChoice)
        }
        calibrationStateJob?.cancel()
        calibrationStateJob = serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
            calibrationCaptureCoordinator.state.collect { state ->
                if (!state.active) removeCalibrationOverlays()
            }
        }
        calibrationCaptureCoordinator.updateAccessibilityReady(true)
        localContactVerificationCoordinator.updateAccessibilityReady(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        val now = OffsetDateTime.now()
        val calibrationRequest = calibrationCaptureCoordinator.activeRequest(packageName, now)
        if (calibrationRequest != null) {
            showCalibrationControl(calibrationRequest)
            return
        }
        removeCalibrationOverlays()
        if (packageName == WECHAT_PACKAGE) {
            if (calibratedMessageSelectionBroker.isPending()) {
                if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                    executePendingCalibratedMessageSelection(packageName, event.className?.toString())
                }
                return
            }
            if (calibratedCallExecutionBroker.isPending()) {
                executePendingCalibratedCall(packageName)
                return
            }
            // 新语义链只消费微信事件；等待最终类型点击时先完成守护麦克风交接。
            if (semanticCallChoiceJob?.isActive == true) return
            if (semanticCallExecutionBroker.isCallChoicePending()) {
                executePendingSemanticCallChoice(now)
                return
            }
            val semanticOutcome = executePendingSemanticContactProfile(packageName, now)
            if (semanticOutcome != null) {
                resumeGuardianAfterSemanticFailure(semanticOutcome)
                return
            }
        }
        val localVerificationRequest = localContactVerificationCoordinator.activeRequest(
            packageName,
            now,
        )
        if (localVerificationRequest != null) {
            localContactVerificationCoordinator.markWechatEventReceived(now)
            val root = rootInActiveWindow ?: run {
                localContactVerificationCoordinator.failPageUnavailable()
                return
            }
            val sample = pageReader.readLocalVerificationSample(root, now) ?: run {
                localContactVerificationCoordinator.failPageRead()
                return
            }
            localContactVerificationCoordinator.publish(sample, now)
            return
        }
        val sampleRequest = sampleCaptureCoordinator.activeRequest(packageName, now)
        if (sampleRequest != null) {
            val root = rootInActiveWindow ?: return
            val sample = if (sampleRequest.target.requiresLocatorSource) {
                pageReader.readContactProfileCallSample(root, now)
            } else {
                pageReader.readStructureOnlySample(root, now)
            } ?: return
            sampleCaptureCoordinator.publish(sample, now)
            return
        }
        val callStartedRequest = callStartedTransitionBroker.activeRequest(packageName, now)
        if (callStartedRequest != null) {
            val root = rootInActiveWindow ?: return
            val sample = pageReader.readStructureOnlySample(root, now) ?: return
            callStartedTransitionBroker.observe(
                callStartedRequest.planId,
                packageName,
                sample,
                now,
            )
            return
        }
        val transitionRequest = directChatTransitionBroker.activeRequest(packageName, now)
        if (transitionRequest != null) {
            val root = rootInActiveWindow ?: return
            val sample = pageReader.readStructureOnlySample(root, now) ?: return
            val confirmed = directChatTransitionBroker.observe(
                transitionRequest.planId,
                packageName,
                sample,
                now,
            )
            if (confirmed && transitionRequest.action.isCallAction()) {
                directChatTransitionBroker.takeConfirmed(transitionRequest.planId)
                    ?.let { confirmedRequest ->
                        callChoiceActionBroker.arm(confirmedRequest, OffsetDateTime.now())
                    }
            }
            return
        }
        val request = observationBroker.activeRequest(packageName, now) ?: return
        val root = rootInActiveWindow ?: return
        val snapshot = pageReader.read(root, request, now) ?: return
        observationBroker.publish(request.planId, snapshot, now)
    }

    override fun onInterrupt() {
        removeCalibrationOverlays()
        calibrationCaptureCoordinator.interrupt()
        cancelSemanticCallExecution()
        localContactVerificationCoordinator.interrupt()
        sampleCaptureCoordinator.interrupt()
        observationBroker.clear()
        actionBroker.interrupt()
        directChatTransitionBroker.interrupt()
        callChoiceActionBroker.interrupt()
        callStartedTransitionBroker.interrupt()
    }

    override fun onDestroy() {
        removeCalibrationOverlays()
        calibrationCaptureCoordinator.updateAccessibilityReady(false)
        cancelSemanticCallExecution()
        WechatDebugNodeProbe.detach(this)
        localContactVerificationCoordinator.updateAccessibilityReady(false)
        sampleCaptureCoordinator.interrupt()
        observationBroker.clear()
        actionBroker.interrupt()
        directChatTransitionBroker.interrupt()
        callChoiceActionBroker.interrupt()
        callStartedTransitionBroker.interrupt()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        removeCalibrationOverlays()
        calibrationCaptureCoordinator.updateAccessibilityReady(false)
        cancelSemanticCallExecution()
        calibratedMessageSelectionJob?.cancel()
        calibratedMessageSelectionJob = null
        calibratedMessageSelectionBroker.clear()
        WechatDebugNodeProbe.detach(this)
        localContactVerificationCoordinator.updateAccessibilityReady(false)
        actionRequestJob?.cancel()
        actionRequestJob = null
        callChoiceRequestJob?.cancel()
        callChoiceRequestJob = null
        calibrationStateJob?.cancel()
        calibrationStateJob = null
        actionBroker.interrupt()
        directChatTransitionBroker.interrupt()
        callChoiceActionBroker.interrupt()
        callStartedTransitionBroker.interrupt()
        return super.onUnbind(intent)
    }

    /**
     * 校准条只占屏幕顶部一小段，用户仍可在微信内手动导航。点击“记录这个位置”后，
     * 临时透明层只拦截下一次触摸并保存坐标；该触摸不会传给微信，也不会触发通话。
     */
    private fun showCalibrationControl(request: WechatCalibrationCaptureRequest) {
        if (calibrationTapView != null) return
        if (calibrationControlView != null && displayedCalibrationTarget == request.target) return
        removeCalibrationControl()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(Color.argb(242, 255, 255, 255))
            elevation = dp(8).toFloat()
        }
        val instruction = TextView(this).apply {
            setTextColor(Color.BLACK)
            textSize = 18f
            text = "AI好友校准 ${request.target.calibrationDisplayName}\n" +
                calibrationGuidance(request.target)
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val recordButton = Button(this).apply {
            text = "记录这个位置"
            setOnClickListener { showCalibrationTapLayer(request) }
        }
        val cancelButton = Button(this).apply {
            text = "取消校准"
            setOnClickListener {
                calibrationCaptureCoordinator.cancel()
                removeCalibrationOverlays()
                Toast.makeText(
                    this@WechatAccessibilityService,
                    "本次校准已取消",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
        actions.addView(
            recordButton,
            LinearLayout.LayoutParams(0, WindowManager.LayoutParams.WRAP_CONTENT, 1f),
        )
        actions.addView(
            cancelButton,
            LinearLayout.LayoutParams(0, WindowManager.LayoutParams.WRAP_CONTENT, 1f),
        )
        container.addView(instruction)
        container.addView(actions)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = calibrationControlGravity(request.target) or Gravity.START
            title = "AI好友微信通话校准条"
        }
        val added = runCatching {
            getSystemService(WindowManager::class.java).addView(container, params)
        }.isSuccess
        if (!added) {
            calibrationCaptureCoordinator.failOverlay()
            removeCalibrationOverlays()
            return
        }
        calibrationControlView = container
        displayedCalibrationTarget = request.target
    }

    private fun showCalibrationTapLayer(request: WechatCalibrationCaptureRequest) {
        val current = calibrationCaptureCoordinator.activeRequest(
            WECHAT_PACKAGE,
            OffsetDateTime.now(),
        )
        if (current?.key != request.key || current.target != request.target) {
            removeCalibrationOverlays()
            return
        }
        removeCalibrationControl()
        var handled = false
        val layer = View(this).apply {
            isClickable = true
            setBackgroundColor(Color.argb(1, 0, 0, 0))
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_UP -> {
                        if (!handled) {
                            handled = true
                            val result = calibrationCaptureCoordinator.record(
                                request = request,
                                rawX = event.rawX.toInt(),
                                rawY = event.rawY.toInt(),
                                now = OffsetDateTime.now(),
                            )
                            removeCalibrationTapLayer()
                            handleCalibrationRecordResult(result)
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        removeCalibrationTapLayer()
                        calibrationCaptureCoordinator.activeRequest(
                            WECHAT_PACKAGE,
                            OffsetDateTime.now(),
                        )?.let(::showCalibrationControl)
                        true
                    }
                    else -> true
                }
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "AI好友微信通话校准点位记录层"
        }
        val added = runCatching {
            getSystemService(WindowManager::class.java).addView(layer, params)
        }.isSuccess
        if (!added) {
            calibrationCaptureCoordinator.failOverlay()
            removeCalibrationOverlays()
            return
        }
        calibrationTapView = layer
        Toast.makeText(
            this,
            "请点一下“${request.target.calibrationDisplayName}”；本次点击只记录，不会操作微信",
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun handleCalibrationRecordResult(result: WechatCalibrationRecordResult) {
        val message = when (result) {
            WechatCalibrationRecordResult.SAVED_NEXT ->
                "点位已记录。需要进入下一页时，请再正常点一次微信目标。"
            WechatCalibrationRecordResult.COMPLETED ->
                "当前组合校准完成，没有发起通话。"
            WechatCalibrationRecordResult.STALE_REQUEST ->
                "校准步骤已变化，请按顶部校准条继续。"
            WechatCalibrationRecordResult.FAILED ->
                "本次校准未保存，请返回AI好友查看原因。"
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        calibrationCaptureCoordinator.activeRequest(
            WECHAT_PACKAGE,
            OffsetDateTime.now(),
        )?.let(::showCalibrationControl)
    }

    private fun removeCalibrationOverlays() {
        removeCalibrationTapLayer()
        removeCalibrationControl()
    }

    private fun removeCalibrationControl() {
        calibrationControlView?.let { view ->
            runCatching { getSystemService(WindowManager::class.java).removeViewImmediate(view) }
        }
        calibrationControlView = null
        displayedCalibrationTarget = null
    }

    private fun removeCalibrationTapLayer() {
        calibrationTapView?.let { view ->
            runCatching { getSystemService(WindowManager::class.java).removeViewImmediate(view) }
        }
        calibrationTapView = null
    }

    private fun calibrationGuidance(target: WechatCalibrationTarget): String = when (target) {
        WechatCalibrationTarget.HOME_SEARCH ->
            "停在微信首页，点记录后再点一下首页搜索入口。"
        WechatCalibrationTarget.GLOBAL_SEARCH_INPUT ->
            "进入搜索页，点记录后点一下搜索输入框。"
        WechatCalibrationTarget.GLOBAL_SEARCH_PASTE ->
            "长按输入框显示粘贴菜单，点记录后点一下粘贴。"
        WechatCalibrationTarget.SEARCH_RESULT ->
            "搜索唯一微信号，记录对应结果；随后正常点一次结果进入聊天页。"
        WechatCalibrationTarget.CHAT_CONTACT_AVATAR ->
            "进入目标聊天页，记录对方头像；随后正常点一次头像进入资料页。"
        WechatCalibrationTarget.CHAT_INFO_MENU ->
            "在目标聊天页记录右上角更多选项；随后正常点击进入聊天信息页。"
        WechatCalibrationTarget.CHAT_INFO_CONTACT_AVATAR ->
            "在聊天信息页记录左上方亲友头像；随后正常点击进入资料页。"
        WechatCalibrationTarget.CONTACT_PROFILE_CALL_ENTRY ->
            "进入联系人资料页，点记录后点一下音视频通话入口。"
        WechatCalibrationTarget.CALL_CHOICE_VOICE ->
            "通话选择页保持打开，只记录语音通话位置，不会拨号。"
        WechatCalibrationTarget.CALL_CHOICE_VIDEO ->
            "继续保持选择页，只记录视频通话位置，完成后不会拨号。"
        WechatCalibrationTarget.SHARE_SEARCH_ENTRY ->
            "微信分享页已打开，记录搜索入口。"
        WechatCalibrationTarget.SHARE_SEARCH_INPUT ->
            "进入分享搜索页，记录搜索输入框。"
        WechatCalibrationTarget.SHARE_SEARCH_PASTE ->
            "长按分享搜索输入框，记录粘贴按钮。"
        WechatCalibrationTarget.SHARE_SEARCH_RESULT ->
            "输入任意现有亲友微信号，记录唯一结果。"
        WechatCalibrationTarget.SHARE_SEND_CONFIRM ->
            "分享确认页只记录发送按钮；本次点击会被拦截，校准内容不会发出。"
    }

    private fun calibrationControlGravity(target: WechatCalibrationTarget): Int = when (target) {
        WechatCalibrationTarget.HOME_SEARCH,
        WechatCalibrationTarget.GLOBAL_SEARCH_INPUT,
        WechatCalibrationTarget.GLOBAL_SEARCH_PASTE,
        WechatCalibrationTarget.SEARCH_RESULT,
        WechatCalibrationTarget.CHAT_CONTACT_AVATAR,
        WechatCalibrationTarget.CHAT_INFO_MENU,
        WechatCalibrationTarget.CHAT_INFO_CONTACT_AVATAR,
        WechatCalibrationTarget.SHARE_SEARCH_ENTRY,
        WechatCalibrationTarget.SHARE_SEARCH_INPUT,
        WechatCalibrationTarget.SHARE_SEARCH_PASTE,
        WechatCalibrationTarget.SHARE_SEARCH_RESULT,
        -> Gravity.BOTTOM
        WechatCalibrationTarget.CONTACT_PROFILE_CALL_ENTRY,
        WechatCalibrationTarget.CALL_CHOICE_VOICE,
        WechatCalibrationTarget.CALL_CHOICE_VIDEO,
        WechatCalibrationTarget.SHARE_SEND_CONFIRM,
        -> Gravity.TOP
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    private fun executePendingSemanticContactProfile(
        packageName: String,
        now: OffsetDateTime,
    ): WechatSemanticCallExecutionOutcome? {
        val readinessPort = semanticCallUiPortFactory.create(rootInActiveWindow)
        val ready = try {
            semanticCallExecutionExecutor.isCurrentStepReady(now, readinessPort)
        } finally {
            readinessPort.close()
        }
        if (!ready) return null
        val uiPort = semanticCallUiPortFactory.create(rootInActiveWindow)
        return try {
            runCatching {
                semanticCallExecutionExecutor.executeNext(
                    packageName = packageName,
                    currentWechatVersion =
                        wechatRuntimeVersionProvider.readCurrentVersion().orEmpty(),
                    now = OffsetDateTime.now(),
                    uiPort = uiPort,
                )
            }.getOrElse {
                semanticCallExecutionBroker.interrupt()
            }
        } finally {
            uiPort.close()
        }
    }

    /** 精确档案只执行一次完整顺序；任一步失败均结束计划且不自动重试。 */
    private fun executePendingCalibratedCall(packageName: String) {
        if (calibratedCallExecutionJob?.isActive == true) return
        calibratedCallExecutionJob = serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
            var handedToWechat = false
            val uiPort = AndroidWechatCalibratedCallUiPort(
                service = this@WechatAccessibilityService,
                runtimeVersionProvider = wechatRuntimeVersionProvider,
                fingerprintProvider = calibrationFingerprintProvider,
                audioCoordinator = guardianWechatCallAudioCoordinator,
            )
            try {
                // 启动微信的首个无障碍事件可能早于首页完成布局。
                delay(CALIBRATED_WECHAT_INITIAL_SETTLE_MILLIS)
                val outcome = calibratedCallExecutionExecutor.execute(
                    packageName = packageName,
                    currentWechatVersion =
                        wechatRuntimeVersionProvider.readCurrentVersion().orEmpty(),
                    now = OffsetDateTime.now(),
                    uiPort = uiPort,
                )
                handedToWechat =
                    outcome?.status in setOf(
                        WechatSemanticCallExecutionStatus.HANDED_TO_WECHAT,
                        WechatSemanticCallExecutionStatus.AWAITING_CALL_STARTED,
                    )
                Log.i(
                    CALIBRATED_CALL_TAG,
                    "Calibrated call finished status=" + (outcome?.status?.name ?: "IGNORED"),
                )
            } finally {
                uiPort.clearSearchClipboard()
                if (!handedToWechat) {
                    withContext(NonCancellable) {
                        guardianWechatCallAudioCoordinator.resumeIfIdle()
                    }
                }
            }
        }
    }

    /** 微信公开分享页一次性选择联系人；失败不重试也不继续后续文字。 */
    private fun executePendingCalibratedMessageSelection(packageName: String, windowClassName: String?) {
        if (calibratedMessageSelectionJob?.isActive == true) return
        val request = calibratedMessageSelectionBroker.take(packageName, OffsetDateTime.now(), windowClassName)
            ?: return
        calibratedMessageSelectionJob = serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val uiPort = AndroidWechatCalibratedCallUiPort(
                service = this@WechatAccessibilityService,
                runtimeVersionProvider = wechatRuntimeVersionProvider,
                fingerprintProvider = calibrationFingerprintProvider,
                audioCoordinator = guardianWechatCallAudioCoordinator,
            )
            var success = false
            try {
                success = calibratedMessageSelectionExecutor.execute(
                    request = request,
                    packageName = packageName,
                    currentWechatVersion =
                        wechatRuntimeVersionProvider.readCurrentVersion().orEmpty(),
                    uiPort = uiPort,
                )
            } finally {
                uiPort.clearSearchClipboard()
                calibratedMessageSelectionBroker.finish(request, success)
                WechatMessageDiagnostics.record(WechatMessageDiagnostics.Event.EXECUTOR_FINISHED, if (success) 1 else 0)
                Log.i("AiFriendWechatMessage", "Message selection finished success=$success")
            }
        }
    }

    /**
     * 类型选择事件先释放守护麦克风，再让一次性执行器重新读取并点击自身节点。
     * 本路径绝不武装 [callStartedTransitionBroker]，系统接受点击只形成 HANDED_TO_WECHAT。
     */
    private fun executePendingSemanticCallChoice(now: OffsetDateTime) {
        val readinessPort = semanticCallUiPortFactory.create(rootInActiveWindow)
        val ready = try {
            semanticCallExecutionExecutor.isCurrentStepReady(now, readinessPort)
        } finally {
            readinessPort.close()
        }
        if (!ready) return
        semanticCallChoiceJob = serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
            var handedToWechat = false
            try {
                if (!guardianWechatCallAudioCoordinator.releaseBeforeCall()) {
                    semanticCallExecutionBroker.failCallChoiceAudioRelease()
                    return@launch
                }
                // 释放期间若请求已过期、被中断或被清理，不得执行其他阶段。
                if (!semanticCallExecutionBroker.isCallChoicePending()) return@launch
                val uiPort = semanticCallUiPortFactory.create(rootInActiveWindow)
                val outcome = try {
                    runCatching {
                        semanticCallExecutionExecutor.executeNext(
                            packageName = WECHAT_PACKAGE,
                            currentWechatVersion =
                                wechatRuntimeVersionProvider.readCurrentVersion().orEmpty(),
                            now = OffsetDateTime.now(),
                            uiPort = uiPort,
                        )
                    }.getOrElse {
                        semanticCallExecutionBroker.interrupt()
                    }
                } finally {
                    uiPort.close()
                }
                handedToWechat =
                    outcome?.status == WechatSemanticCallExecutionStatus.HANDED_TO_WECHAT
            } finally {
                if (!handedToWechat) {
                    withContext(NonCancellable) {
                        guardianWechatCallAudioCoordinator.resumeIfIdle()
                    }
                }
            }
        }
    }

    private fun resumeGuardianAfterSemanticFailure(
        outcome: WechatSemanticCallExecutionOutcome,
    ) {
        if (outcome.status == WechatSemanticCallExecutionStatus.AWAITING_CALL_CHOICE ||
            outcome.status == WechatSemanticCallExecutionStatus.AWAITING_CALL_STARTED ||
            outcome.status == WechatSemanticCallExecutionStatus.HANDED_TO_WECHAT
        ) {
            return
        }
        serviceScope.launch {
            guardianWechatCallAudioCoordinator.resumeIfIdle()
        }
    }

    private fun cancelSemanticCallExecution() {
        calibratedCallExecutionJob?.cancel()
        calibratedCallExecutionJob = null
        calibratedCallExecutionBroker.interrupt()
        semanticCallChoiceJob?.cancel()
        semanticCallChoiceJob = null
        semanticCallExecutionBroker.interrupt()
    }

    private fun executePendingAction(planId: String) {
        val now = OffsetDateTime.now()
        val request = actionBroker.take(planId, WECHAT_PACKAGE, now) ?: return
        val outcome = runCatching {
            actionExecutor.execute(rootInActiveWindow, request, now)
        }.getOrElse {
            WechatVerifiedContactProfileActionOutcome(
                request.planId,
                WechatVerifiedContactProfileActionStatus.PAGE_REVALIDATION_FAILED,
            )
        }
        if (outcome.status == WechatVerifiedContactProfileActionStatus.CLICK_REQUEST_ACCEPTED) {
            directChatTransitionBroker.arm(request, OffsetDateTime.now())
        }
        actionBroker.publish(outcome)
    }

    private suspend fun executePendingCallChoice(planId: String) {
        val request = callChoiceActionBroker.take(
            planId,
            WECHAT_PACKAGE,
            OffsetDateTime.now(),
        ) ?: return
        val audioReleased = guardianWechatCallAudioCoordinator.releaseBeforeCall()
        val outcome = if (!audioReleased) {
            WechatCallChoiceActionOutcome(
                request.planId,
                request.action,
                WechatCallChoiceActionStatus.AUDIO_RELEASE_FAILED,
            )
        } else {
            runCatching {
                callChoiceActionExecutor.execute(
                    rootInActiveWindow,
                    request,
                    OffsetDateTime.now(),
                )
            }.getOrElse {
                WechatCallChoiceActionOutcome(
                    request.planId,
                    request.action,
                    WechatCallChoiceActionStatus.PAGE_REVALIDATION_FAILED,
                )
            }
        }
        val publishedOutcome = if (
            outcome.status == WechatCallChoiceActionStatus.CLICK_REQUEST_ACCEPTED
        ) {
            outcome.copy(
                callStartedConfirmationArmed = callStartedTransitionBroker.arm(
                    request,
                    OffsetDateTime.now(),
                ),
            )
        } else {
            guardianWechatCallAudioCoordinator.resumeIfIdle()
            outcome
        }
        callChoiceActionBroker.publish(publishedOutcome)
    }

    private companion object {
        const val WECHAT_PACKAGE = "com.tencent.mm"
        const val CALIBRATED_CALL_TAG = "AiFriendWechatCall"
        const val CALIBRATED_WECHAT_INITIAL_SETTLE_MILLIS = 750L
    }
}
