package com.aifriend.feature.wechat

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.PersistableBundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.aifriend.feature.guardian.GuardianWechatCallAudioCoordinator
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 校准坐标执行的 Android 适配器。
 *
 * 每个手势只提交一次；剪贴板只在搜索步骤短时持有微信号并标记敏感，粘贴后立即清空。
 */
class AndroidWechatCalibratedCallUiPort(
    private val service: AccessibilityService,
    private val runtimeVersionProvider: WechatRuntimeVersionProvider,
    private val fingerprintProvider: WechatCalibrationFingerprintProvider,
    private val audioCoordinator: GuardianWechatCallAudioCoordinator,
    private val visualEvidenceReader: WechatVisualPageEvidenceReader =
        AndroidWechatVisualPageEvidenceReader(service),
) : WechatCalibratedCallUiPort {
    private val clipboardManager: ClipboardManager? =
        service.getSystemService(ClipboardManager::class.java)
    private val semanticPortFactory = AndroidWechatSemanticCallUiPortFactory()
    private var semanticPort: AndroidWechatSemanticCallUiPort? = null

    override fun currentTime(): OffsetDateTime = OffsetDateTime.now()

    override fun currentFingerprint(): WechatCalibrationProfileKey? {
        val version = runCatching { runtimeVersionProvider.readCurrentVersion() }.getOrNull()
            ?: return null
        return runCatching { fingerprintProvider.current(version) }.getOrNull()
    }

    override fun isWechatForeground(): Boolean =
        runCatching {
            service.rootInActiveWindow?.packageName?.toString() ==
                WechatSemanticCallContract.WECHAT_PACKAGE
        }.getOrDefault(false)

    override fun recordStage(stage: WechatCalibratedCallStage) {
        Log.i(CALL_DIAGNOSTIC_TAG, "Calibrated call stage=" + stage.name)
    }

    override fun recordMessageStage(stage: WechatMessageSelectionStage) {
        WechatMessageDiagnostics.stage(stage)
        Log.i("AiFriendWechatMessage", "Message selection stage=" + stage.name)
    }

    override suspend fun tap(point: WechatCalibrationPixelPoint): Boolean =
        dispatch(point, TAP_DURATION)

    override suspend fun longPress(point: WechatCalibrationPixelPoint): Boolean =
        dispatch(point, LONG_PRESS_DURATION)

    override fun setSearchText(value: CharArray): Boolean =
        withUniqueSearchInput { node ->
            val arguments = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    String(value),
                )
            }
            runCatching {
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            }.getOrDefault(false)
        } ?: false

    override suspend fun verifyMessageSearchResult(value: CharArray, point: WechatCalibrationPixelPoint): Boolean =
        withMessageNodes { nodes ->
            val input = nodes.filter { it.isEditable }.singleOrNull()
            if (input?.text?.toString() != value.concatToString()) return@withMessageNodes false
            var locatorCount = 0
            val results = nodes.filter { node ->
                if (node.isEditable) return@filter false
                val locator = WechatLocalVerificationTextRule.locator(node.text?.toString().orEmpty())
                    ?: return@filter false
                locatorCount += 1
                try { locator.contentEquals(value) } finally { locator.fill('\u0000') }
            }
            if (locatorCount != 1) return@withMessageNodes false
            val result = results.singleOrNull() ?: return@withMessageNodes false
            // 唯一精确微信号所在可点击行必须覆盖校准点，不得使用置顶联系人位置。
            var current: AccessibilityNodeInfo? = result
            val parents = ArrayList<AccessibilityNodeInfo>()
            try {
                repeat(6) {
                    val node = current ?: return@withMessageNodes false
                    val bounds = Rect().also(node::getBoundsInScreen)
                    if (node.isClickable && node.isVisibleToUser && node.isEnabled &&
                        bounds.contains(point.x, point.y) && bounds.height() > 0 &&
                        bounds.height() <= (currentFingerprint()?.displayHeightPixels ?: 0) / 3) {
                        return@withMessageNodes true
                    }
                    current = node.parent?.also(parents::add)
                }
                false
            } finally { recycleMessageNodes(parents) }
        } ?: visualEvidenceReader.verifyMessageSearchResult(value, point)

    override suspend fun verifyMessageSendButton(point: WechatCalibrationPixelPoint): Boolean =
        readMessageSendButtonNodes(point) ?: run {
            WechatMessageDiagnostics.record(WechatMessageDiagnostics.Event.SEND_VISUAL_FALLBACK)
            visualEvidenceReader.verifyMessageSendButton(point)
        }

    /** 只读当前节点，不截图、不点击；null 表示空壳树需要视觉验证，不代表允许发送。 */
    fun readMessageSendButtonNodes(point: WechatCalibrationPixelPoint): Boolean? =
        withMessageNodes { nodes ->
            val buttons = nodes.mapNotNull { node ->
                val label = when (node.text?.toString()?.trim()) {
                    "发送" -> WechatShareButtonNode.Label.SEND
                    "取消" -> WechatShareButtonNode.Label.CANCEL
                    else -> return@mapNotNull null
                }
                val bounds = Rect().also(node::getBoundsInScreen)
                WechatShareButtonNode(label, bounds.left, bounds.top, bounds.right, bounds.bottom, node.isClickable)
            }
            val decision = WechatShareSendNodeRule.verify(buttons, point)
            WechatMessageDiagnostics.record(WechatMessageDiagnostics.Event.SEND_NODE_CHECK, decision.ordinal)
            decision == WechatShareSendNodeRule.Decision.VERIFIED
        }

    /** 只在签名分享会话的两个检查点读取节点；超限或缺失不返回部分证据。 */
    private fun withMessageNodes(block: (List<AccessibilityNodeInfo>) -> Boolean): Boolean? {
        fun rejected(reason: Int): Boolean {
            WechatMessageDiagnostics.record(WechatMessageDiagnostics.Event.MESSAGE_TREE_REJECTED, reason)
            return false
        }
        val root = service.rootInActiveWindow ?: return null
        val owned = arrayListOf(root)
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.addLast(root to 0)
        val visible = ArrayList<AccessibilityNodeInfo>()
        try {
            if (root.packageName?.toString() != WechatSemanticCallContract.WECHAT_PACKAGE) return rejected(1)
            while (queue.isNotEmpty()) {
                if (owned.size > MAX_SEARCH_TREE_NODES) return rejected(2)
                val (node, depth) = queue.removeFirst()
                if (node.isVisibleToUser && node.isEnabled &&
                    node.packageName?.toString() == WechatSemanticCallContract.WECHAT_PACKAGE) {
                    visible += node
                }
                if (depth >= MAX_SEARCH_TREE_DEPTH && node.childCount > 0) return rejected(3)
                for (index in 0 until node.childCount) {
                    val child = node.getChild(index) ?: return rejected(4)
                    owned += child
                    queue.addLast(child to depth + 1)
                    if (owned.size > MAX_SEARCH_TREE_NODES) return rejected(2)
                }
            }
            WechatMessageDiagnostics.record(WechatMessageDiagnostics.Event.MESSAGE_NODE_COUNTS, owned.size, visible.size)
            // 空壳可能包含多层 FrameLayout。完整树中没有语义或交互内容才允许视觉回退。
            val shapes = owned.map { node ->
                WechatMessageNodeShape(
                    belongsToWechat = node.packageName?.toString() == WechatSemanticCallContract.WECHAT_PACKAGE,
                    hasText = !node.text.isNullOrBlank(),
                    hasDescription = !node.contentDescription.isNullOrBlank(),
                    editable = node.isEditable,
                    clickable = node.isClickable,
                )
            }
            WechatMessageDiagnostics.record(WechatMessageDiagnostics.Event.MESSAGE_NODE_SEMANTICS,
                shapes.count { it.hasText || it.hasDescription }, shapes.count { it.editable || it.clickable })
            if (WechatMessageNodeTreeRule.canUseVisualFallback(shapes)) {
                return null
            }
            return block(visible)
        } catch (_: Exception) {
            return rejected(5)
        } finally { recycleMessageNodes(owned) }
    }

    private fun recycleMessageNodes(nodes: List<AccessibilityNodeInfo>) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            nodes.forEach { runCatching { it.recycle() } }
        }
    }

    override fun setSensitiveSearchClipboard(value: CharArray): Boolean {
        if (value.isEmpty() || value.any { it == '\u0000' }) return false
        val manager = clipboardManager ?: return false
        return runCatching {
            val clip = ClipData.newPlainText(CLIP_LABEL, String(value))
            clip.description.extras = PersistableBundle().apply {
                putBoolean(SENSITIVE_CLIP_EXTRA, true)
            }
            manager.setPrimaryClip(clip)
            true
        }.getOrDefault(false)
    }

    override fun clearSearchClipboard() {
        runCatching { clipboardManager?.clearPrimaryClip() }
    }

    override fun readContactProfileEvidence(
        locatorSalt: String,
        now: OffsetDateTime,
    ): WechatSemanticContactProfileEvidence? {
        releasePageEvidence()
        val port = semanticPortFactory.create(service.rootInActiveWindow)
        val evidence = port.readContactProfileEvidence(locatorSalt, now)
        if (evidence == null) {
            port.close()
        } else {
            semanticPort = port
        }
        return evidence
    }

    override suspend fun readContactProfileEvidenceWithVisualFallback(
        locatorSalt: String,
        now: OffsetDateTime,
    ): WechatSemanticContactProfileEvidence? {
        val nodeEvidence = readContactProfileEvidence(locatorSalt, now)
        if (!WechatVisualFallbackPolicy.allows(nodeEvidence)) {
            return nodeEvidence
        }
        releasePageEvidence()
        return visualEvidenceReader.readContactProfileEvidence(locatorSalt)
    }

    override fun readCallChoiceEvidence(now: OffsetDateTime): WechatSemanticCallChoiceEvidence? {
        releasePageEvidence()
        val port = semanticPortFactory.create(service.rootInActiveWindow)
        val evidence = port.readCallChoiceEvidence(now)
        if (evidence == null) {
            port.close()
        } else {
            semanticPort = port
        }
        return evidence
    }

    override suspend fun readCallChoiceEvidenceWithVisualFallback(
        now: OffsetDateTime,
    ): WechatSemanticCallChoiceEvidence? {
        val nodeEvidence = readCallChoiceEvidence(now)
        if (!WechatVisualFallbackPolicy.allows(nodeEvidence)) {
            return nodeEvidence
        }
        releasePageEvidence()
        return visualEvidenceReader.readCallChoiceEvidence()
    }

    override fun releasePageEvidence() {
        semanticPort?.close()
        semanticPort = null
    }

    override suspend fun waitForUi(duration: Duration) {
        val millis = duration.toMillis()
        if (millis > 0) delay(millis)
    }

    override suspend fun releaseAudioBeforeCall(): Boolean =
        audioCoordinator.releaseBeforeCall()

    /**
     * 搜索页只接受一个可见、启用、可编辑且支持 ACTION_SET_TEXT 的微信节点。
     * 遍历只读取结构属性；不读取其他节点文字或描述，存在歧义时失败关闭。
     */
    private fun <T> withUniqueSearchInput(block: (AccessibilityNodeInfo) -> T): T? {
        val root = service.rootInActiveWindow ?: return null
        val owned = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.addLast(root to 0)
        val candidates = ArrayList<AccessibilityNodeInfo>(2)
        try {
            var visited = 0
            while (queue.isNotEmpty() && visited < MAX_SEARCH_TREE_NODES) {
                val (node, depth) = queue.removeFirst()
                visited += 1
                val supportsSetText = runCatching {
                    node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
                }.getOrDefault(false)
                val eligible = runCatching {
                    node.packageName?.toString() == WechatSemanticCallContract.WECHAT_PACKAGE &&
                        node.isVisibleToUser && node.isEnabled && node.isEditable && supportsSetText
                }.getOrDefault(false)
                if (eligible) candidates += node
                if (depth >= MAX_SEARCH_TREE_DEPTH) continue
                val childCount = runCatching { node.childCount }.getOrDefault(0)
                for (index in 0 until childCount) {
                    val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                    owned += child
                    queue.addLast(child to depth + 1)
                }
            }
            val candidate = candidates.singleOrNull() ?: return null
            return block(candidate)
        } finally {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
                owned.forEach { runCatching { it.recycle() } }
            }
        }
    }

    private suspend fun dispatch(
        point: WechatCalibrationPixelPoint,
        durationMillis: Long,
    ): Boolean = suspendCancellableCoroutine { continuation ->
        val completed = AtomicBoolean(false)
        fun finish(value: Boolean) {
            if (completed.compareAndSet(false, true) && continuation.isActive) {
                continuation.resume(value)
            }
        }
        continuation.invokeOnCancellation { completed.set(true) }
        val path = Path().apply { moveTo(point.x.toFloat(), point.y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMillis))
            .build()
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                finish(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                finish(false)
            }
        }
        val accepted = runCatching {
            service.dispatchGesture(gesture, callback, null)
        }.getOrDefault(false)
        if (!accepted) {
            finish(false)
        }
    }

    private companion object {
        const val CALL_DIAGNOSTIC_TAG = "AiFriendWechatCall"
        const val TAP_DURATION = 80L
        const val LONG_PRESS_DURATION = 650L
        const val CLIP_LABEL = "AI好友临时搜索"
        const val SENSITIVE_CLIP_EXTRA = "android.content.extra.IS_SENSITIVE"
        const val MAX_SEARCH_TREE_NODES = 128
        const val MAX_SEARCH_TREE_DEPTH = 12
    }
}
