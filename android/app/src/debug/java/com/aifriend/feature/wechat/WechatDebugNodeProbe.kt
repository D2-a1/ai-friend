package com.aifriend.feature.wechat

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.aifriend.contract.model.WechatActionType
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.io.IOException
import java.lang.ref.WeakReference
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime

/**
 * 仅存在于 Debug 包、只能由受 DUMP 权限保护的显式广播触发的微信节点预检。
 *
 * 报告不读取或输出节点 text/contentDescription、坐标、微信号、联系人或聊天内容；只保留
 * 类名、资源 ID、树位置和无障碍状态。固定语义角色通过系统的按文字候选查询取得，但报告
 * 只写角色枚举，不写查询文字。每次捕获覆盖同一个缓存文件，失败和 CLEAR 都删除旧报告。
 */
object WechatDebugNodeProbe {
    private const val WECHAT_PACKAGE = "com.tencent.mm"
    private const val REPORT_FILE_NAME = "wechat-node-probe-v1.txt"
    private const val MAXIMUM_NODE_COUNT = 256
    private const val MAXIMUM_DEPTH = 12
    private const val MAXIMUM_CHILDREN_PER_NODE = 32
    private const val MAXIMUM_ROLE_CANDIDATES = 16

    private val allowedStages = setOf(
        "HOME",
        "GLOBAL_SEARCH",
        "SEARCH_RESULTS",
        "CONTACT_PROFILE",
        "CALL_CHOICE",
    )
    private val semanticQueries = linkedMapOf(
        "SEARCH" to "搜索",
        "WECHAT_ID_LABEL" to "微信号",
        "AUDIO_VIDEO_CALL" to "音视频通话",
        "VOICE_CALL" to "语音通话",
        "VIDEO_CALL" to "视频通话",
    )

    @Volatile
    private var serviceReference = WeakReference<AccessibilityService>(null)

    fun attach(service: AccessibilityService) {
        serviceReference = WeakReference(service)
    }

    fun detach(service: AccessibilityService) {
        if (serviceReference.get() === service) {
            serviceReference.clear()
        }
    }

    /**
     * 仅供 DUMP 权限保护的 Debug 广播在当前微信前台页生成一次正式同算法样本。
     *
     * 不切换页面、不执行节点动作；联系人资料页只复用正式窄证据读取，其余页面只读结构。
     */
    fun captureRuleSample(
        reader: WechatAccessibilityPageReader,
        target: WechatSampleCaptureTarget,
        capturedAt: OffsetDateTime,
    ): WechatPageStructureSample? {
        val service = serviceReference.get() ?: return null
        val root = service.rootInActiveWindow ?: return null
        if (root.packageName?.toString() != WECHAT_PACKAGE) return null
        return if (target.requiresLocatorSource) {
            reader.readContactProfileCallSample(root, capturedAt)
        } else {
            reader.readStructureOnlySample(root, capturedAt)
        }
    }

    /**
     * 只返回联系人资料页严格文本规则的分类计数，不返回节点文字、定位值或摘要。
     * 该诊断用于区分内联微信号、独立标签和相邻值节点等设备兼容形态。
     */
    fun diagnoseContactProfile(): String {
        val service = serviceReference.get() ?: return "DIAG:SERVICE_UNAVAILABLE"
        val root = service.rootInActiveWindow ?: return "DIAG:ROOT_UNAVAILABLE"
        if (root.packageName?.toString() != WECHAT_PACKAGE) return "DIAG:WRONG_PACKAGE"

        var locatorNodes = 0
        var inlineLocators = 0
        var labelOnlyNodes = 0
        var labelPrefixNodes = 0
        var standaloneLocatorSiblings = 0
        var locatorTextNodes = 0
        var locatorDescriptionNodes = 0
        var locatorChildNodes = 0
        var locatorDescendantQueryNodes = 0
        var locatorDescendantInlineNodes = 0
        var locatorDescendantLabelNodes = 0
        var locatorDescendantStandaloneNodes = 0
        var callNodes = 0
        var exactCallNodes = 0
        var callTextNodes = 0
        var callDescriptionNodes = 0
        var callChildNodes = 0
        var callDescendantQueryNodes = 0
        var callDescendantExactNodes = 0
        inspectTextMatches(root, "微信号") { node, text ->
            locatorNodes++
            if (!text.isNullOrEmpty()) locatorTextNodes++
            if (!node.contentDescription.isNullOrEmpty()) locatorDescriptionNodes++
            locatorChildNodes += node.childCount
            val parsed = WechatLocalVerificationTextRule.locator(text)
            if (parsed != null) {
                inlineLocators++
                parsed.fill(0.toChar())
            }
            val normalized = text?.toString()?.trimUnicodeSpacing().orEmpty()
            if (normalized == "微信号" || normalized == "微信号：" || normalized == "微信号:") {
                labelOnlyNodes++
            }
            if (normalized.startsWith("微信号")) labelPrefixNodes++
            standaloneLocatorSiblings += node.countStrictStandaloneLocatorSiblings()
            node.inspectBoundedDescendants("微信号") { descendant ->
                val values = listOf(descendant.text, descendant.contentDescription)
                    .filterNotNull()
                    .distinctBy { it.toString() }
                if (values.any { it.toString().contains("微信号") }) {
                    locatorDescendantQueryNodes++
                }
                values.forEach { value ->
                    WechatLocalVerificationTextRule.locator(value)?.let { candidate ->
                        locatorDescendantInlineNodes++
                        candidate.fill(0.toChar())
                    }
                    if (WechatLocalVerificationTextRule.isLocatorLabel(value)) {
                        locatorDescendantLabelNodes++
                    }
                    WechatLocalVerificationTextRule.standaloneLocator(value)?.let { candidate ->
                        locatorDescendantStandaloneNodes++
                        candidate.fill(0.toChar())
                    }
                }
            }
        }
        inspectTextMatches(root, "音视频通话") { node, text ->
            callNodes++
            if (!text.isNullOrEmpty()) callTextNodes++
            if (!node.contentDescription.isNullOrEmpty()) callDescriptionNodes++
            callChildNodes += node.childCount
            if (WechatLocalVerificationTextRule.isContactProfileAction(
                    text,
                    WechatActionType.START_VOICE_CALL,
                )
            ) {
                exactCallNodes++
            }
            node.inspectBoundedDescendants("音视频通话") { descendant ->
                val values = listOf(descendant.text, descendant.contentDescription)
                    .filterNotNull()
                    .distinctBy { it.toString() }
                if (values.any { it.toString().contains("音视频通话") }) {
                    callDescendantQueryNodes++
                }
                if (values.any {
                        WechatLocalVerificationTextRule.isContactProfileAction(
                            it,
                            WechatActionType.START_VOICE_CALL,
                        )
                    }
                ) {
                    callDescendantExactNodes++
                }
            }
        }
        return "DIAG:locatorNodes=$locatorNodes:inline=$inlineLocators:" +
            "labelOnly=$labelOnlyNodes:labelPrefix=$labelPrefixNodes:" +
            "standaloneSibling=$standaloneLocatorSiblings:" +
            "locatorText=$locatorTextNodes:locatorDesc=$locatorDescriptionNodes:" +
            "locatorChildren=$locatorChildNodes:locatorDescQuery=$locatorDescendantQueryNodes:" +
            "locatorDescInline=$locatorDescendantInlineNodes:" +
            "locatorDescLabel=$locatorDescendantLabelNodes:" +
            "locatorDescStandalone=$locatorDescendantStandaloneNodes:" +
            "callNodes=$callNodes:callExact=$exactCallNodes:callText=$callTextNodes:" +
            "callDesc=$callDescriptionNodes:callChildren=$callChildNodes:" +
            "callDescQuery=$callDescendantQueryNodes:callDescExact=$callDescendantExactNodes"
    }

    /**
     * 仅在 Debug 预检包中截取当前屏幕并以内置中文 OCR 验证微信自绘资料页。
     *
     * 截图和 OCR 文本只存在于内存；回调只返回固定标签的命中计数，不返回联系人、微信号、
     * 坐标、截图或任意原始文字。该方法不执行点击、输入或其他页面操作。
     */
    fun diagnoseVisualContactProfile(consume: (String) -> Unit) {
        val service = serviceReference.get()
            ?: return consume("OCR:SERVICE_UNAVAILABLE")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            consume("OCR:UNSUPPORTED_ANDROID")
            return
        }
        val root = service.rootInActiveWindow
            ?: return consume("OCR:ROOT_UNAVAILABLE")
        if (root.packageName?.toString() != WECHAT_PACKAGE) {
            consume("OCR:WRONG_PACKAGE")
            return
        }
        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                service.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        readVisualContactProfile(screenshot, consume)
                    }

                    override fun onFailure(errorCode: Int) {
                        consume("OCR:SCREENSHOT_FAILED:$errorCode")
                    }
                },
            )
        } catch (_: SecurityException) {
            consume("OCR:SCREENSHOT_PERMISSION_DENIED")
        } catch (_: IllegalStateException) {
            consume("OCR:SCREENSHOT_STATE_INVALID")
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun readVisualContactProfile(
        screenshot: AccessibilityService.ScreenshotResult,
        consume: (String) -> Unit,
    ) {
        val hardwareBuffer = screenshot.hardwareBuffer
        val hardwareBitmap = runCatching {
            Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
        }.getOrNull()
        val bitmap = runCatching {
            hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
        }.getOrNull()
        hardwareBitmap?.recycle()
        hardwareBuffer.close()
        if (bitmap == null) {
            consume("OCR:BITMAP_UNAVAILABLE")
            return
        }

        val recognizer = TextRecognition.getClient(
            ChineseTextRecognizerOptions.Builder().build(),
        )
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { text ->
                consume(renderVisualDiagnostic(text))
            }
            .addOnFailureListener {
                consume("OCR:RECOGNITION_FAILED")
            }
            .addOnCompleteListener {
                recognizer.close()
                bitmap.recycle()
            }
    }

    private fun renderVisualDiagnostic(text: Text): String {
        val blocks = text.textBlocks
        val lines = blocks.flatMap { block -> block.lines }
        var locatorLabels = 0
        var inlineLocators = 0
        var standaloneLocators = 0
        var callEntries = 0
        var normalizedCallEntries = 0
        var callPrefixFragments = 0
        var callSuffixFragments = 0
        var fullCallPhraseFragments = 0
        var fullCallPhraseWithExtraCjk = 0
        var splitCallFragmentPairs = 0
        var sameRowSplitCallFragmentPairs = 0
        var maximumKnownCallCharacters = 0
        val compactLines = ArrayList<Pair<String, android.graphics.Rect>>()
        lines.forEach { line ->
            val value = line.text
            val compactValue = value.filterNot { character ->
                character.isWhitespace() || Character.isSpaceChar(character) ||
                    Character.getType(character) == Character.FORMAT.toInt()
            }
            if (value.contains("微信号")) locatorLabels++
            WechatLocalVerificationTextRule.locator(value)?.let { candidate ->
                inlineLocators++
                candidate.fill(0.toChar())
            }
            WechatLocalVerificationTextRule.standaloneLocator(value)?.let { candidate ->
                standaloneLocators++
                candidate.fill(0.toChar())
            }
            if (WechatLocalVerificationTextRule.isContactProfileAction(
                    value,
                    WechatActionType.START_VOICE_CALL,
                )
            ) {
                callEntries++
            }
            if (compactValue == "音视频通话") normalizedCallEntries++
            if (compactValue.contains("音视频")) callPrefixFragments++
            if (compactValue.contains("通话")) callSuffixFragments++
            if (compactValue.contains("音视频通话")) {
                fullCallPhraseFragments++
                val extras = compactValue.replace("音视频通话", "")
                if (extras.any { character -> character.code in 0x3400..0x9FFF }) {
                    fullCallPhraseWithExtraCjk++
                }
            }
            line.boundingBox?.let { bounds -> compactLines += compactValue to bounds }
            maximumKnownCallCharacters = maxOf(
                maximumKnownCallCharacters,
                "音视频通话".count { expected -> compactValue.contains(expected) },
            )
        }
        compactLines.forEach { (leftText, leftBounds) ->
            if (leftText != "音视频") return@forEach
            compactLines.forEach { (rightText, rightBounds) ->
                if (rightText != "通话") return@forEach
                splitCallFragmentPairs++
                val overlap = minOf(leftBounds.bottom, rightBounds.bottom) -
                    maxOf(leftBounds.top, rightBounds.top)
                val minimumHeight = minOf(leftBounds.height(), rightBounds.height())
                if (minimumHeight > 0 && overlap * 2 >= minimumHeight) {
                    sameRowSplitCallFragmentPairs++
                }
            }
        }
        val boundedLines = lines.mapNotNull { line ->
            line.boundingBox?.takeIf { bounds -> bounds.width() > 0 && bounds.height() > 0 }
                ?.let { bounds ->
                    WechatVisualTextLine(
                        line.text,
                        bounds.left,
                        bounds.top,
                        bounds.right,
                        bounds.bottom,
                    )
                }
        }.toMutableList()
        val profile = WechatVisualTextEvidenceRule.contactProfile(boundedLines)
        val choice = WechatVisualTextEvidenceRule.callChoice(boundedLines)
        return try {
            "OCR:SUCCESS:blocks=${blocks.size}:lines=${lines.size}:" +
                "locatorLabels=$locatorLabels:inlineLocators=$inlineLocators:" +
                "standaloneLocators=$standaloneLocators:callEntries=$callEntries:" +
                "normalizedCallEntries=$normalizedCallEntries:" +
                "callPrefixFragments=$callPrefixFragments:" +
                "callSuffixFragments=$callSuffixFragments:" +
                "fullCallPhraseFragments=$fullCallPhraseFragments:" +
                "fullCallPhraseWithExtraCjk=$fullCallPhraseWithExtraCjk:" +
                "splitCallFragmentPairs=$splitCallFragmentPairs:" +
                "sameRowSplitCallFragmentPairs=$sameRowSplitCallFragmentPairs:" +
                "maximumKnownCallCharacters=$maximumKnownCallCharacters:" +
                "strictProfileLocators=${profile.locatorCandidates.size}:" +
                "strictProfileCallEntries=${profile.callEntryMatchCount}:" +
                "strictVoiceEntries=${choice.voiceMatchCount}:" +
                "strictVideoEntries=${choice.videoMatchCount}"
        } finally {
            profile.clear()
            boundedLines.clear()
        }
    }

    private fun AccessibilityNodeInfo.inspectBoundedDescendants(
        query: String,
        consume: (AccessibilityNodeInfo) -> Unit,
    ) {
        val pending = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        for (index in 0 until childCount.coerceAtMost(MAXIMUM_CHILDREN_PER_NODE)) {
            getChild(index)?.let { pending.add(it to 1) }
        }
        var visited = 0
        try {
            while (pending.isNotEmpty() && visited < MAXIMUM_ROLE_CANDIDATES) {
                val (node, depth) = pending.removeFirst()
                try {
                    visited++
                    consume(node)
                    if (depth < 3) {
                        for (index in 0 until node.childCount.coerceAtMost(MAXIMUM_CHILDREN_PER_NODE)) {
                            node.getChild(index)?.let { pending.add(it to depth + 1) }
                        }
                    }
                } finally {
                    node.recycleOwned()
                }
            }
        } finally {
            while (pending.isNotEmpty()) pending.removeFirst().first.recycleOwned()
        }
    }

    private fun inspectTextMatches(
        root: AccessibilityNodeInfo,
        query: String,
        consume: (AccessibilityNodeInfo, CharSequence?) -> Unit,
    ) {
        val nodes = runCatching { root.findAccessibilityNodeInfosByText(query) }
            .getOrNull()
            ?: return
        try {
            nodes.take(MAXIMUM_ROLE_CANDIDATES).forEach { node -> consume(node, node.text) }
        } finally {
            nodes.forEach { node -> node.recycleOwned() }
        }
    }

    private fun AccessibilityNodeInfo.countStrictStandaloneLocatorSiblings(): Int {
        val parentNode = parent ?: return 0
        return try {
            if (parentNode.childCount !in 1..MAXIMUM_CHILDREN_PER_NODE) return 0
            var matches = 0
            for (index in 0 until parentNode.childCount) {
                val child = parentNode.getChild(index) ?: continue
                try {
                    if (child !== this && child.text.isStrictStandaloneLocator()) matches++
                } finally {
                    child.recycleOwned()
                }
            }
            matches
        } finally {
            parentNode.recycleOwned()
        }
    }

    private fun CharSequence?.isStrictStandaloneLocator(): Boolean {
        if (this == null) return false
        val normalized = toString().trimUnicodeSpacing()
        if (normalized.length !in 6..64 || normalized.firstOrNull()?.isAsciiLetter() != true) return false
        return normalized.all { it.isAsciiLetterOrDigit() || it == '_' || it == '-' }
    }

    private fun String.trimUnicodeSpacing(): String = trim { character ->
        character.isWhitespace() || Character.isSpaceChar(character) ||
            Character.getType(character) == Character.FORMAT.toInt()
    }

    private fun Char.isAsciiLetter(): Boolean = this in 'A'..'Z' || this in 'a'..'z'

    private fun Char.isAsciiLetterOrDigit(): Boolean = isAsciiLetter() || this in '0'..'9'

    @Synchronized
    fun capture(context: Context, stage: String): WechatDebugNodeProbeResult {
        val reportFile = context.cacheDir.resolve(REPORT_FILE_NAME)
        if (reportFile.exists() && !reportFile.delete()) {
            return WechatDebugNodeProbeResult.WRITE_FAILED
        }
        if (stage !in allowedStages) return WechatDebugNodeProbeResult.INVALID_STAGE
        val service = serviceReference.get()
            ?: return WechatDebugNodeProbeResult.SERVICE_UNAVAILABLE
        val root = service.rootInActiveWindow
            ?: return WechatDebugNodeProbeResult.ROOT_UNAVAILABLE
        if (root.packageName?.toString() != WECHAT_PACKAGE) {
            return WechatDebugNodeProbeResult.WRONG_PACKAGE
        }
        val displayMetrics = context.resources.displayMetrics
        val screenBounds = Rect(0, 0, displayMetrics.widthPixels, displayMetrics.heightPixels)
        val report = runCatching { buildReport(root, stage, screenBounds) }.getOrNull()
            ?: return WechatDebugNodeProbeResult.READ_FAILED
        return try {
            reportFile.outputStream().buffered().use { output ->
                output.write(report.toByteArray(StandardCharsets.UTF_8))
            }
            WechatDebugNodeProbeResult.CAPTURED
        } catch (_: IOException) {
            reportFile.delete()
            WechatDebugNodeProbeResult.WRITE_FAILED
        } catch (_: SecurityException) {
            reportFile.delete()
            WechatDebugNodeProbeResult.WRITE_FAILED
        }
    }

    @Synchronized
    fun clear(context: Context): WechatDebugNodeProbeResult {
        val reportFile = context.cacheDir.resolve(REPORT_FILE_NAME)
        return if (!reportFile.exists() || reportFile.delete()) {
            WechatDebugNodeProbeResult.CLEARED
        } else {
            WechatDebugNodeProbeResult.WRITE_FAILED
        }
    }

    private fun buildReport(
        root: AccessibilityNodeInfo,
        stage: String,
        screenBounds: Rect,
    ): String {
        val nodes = readStructuralNodes(root, screenBounds)
        val roles = readSemanticRoleCandidates(root, screenBounds)
        return WechatDebugNodeReport.render(stage, nodes, roles)
    }

    private fun readStructuralNodes(
        root: AccessibilityNodeInfo,
        screenBounds: Rect,
    ): List<WechatDebugNodeRecord> {
        val records = ArrayList<WechatDebugNodeRecord>()
        val pending = ArrayDeque<NodeFrame>()
        pending.add(NodeFrame(root, 0, 0, false))
        try {
            while (pending.isNotEmpty()) {
                val frame = pending.removeFirst()
                try {
                    require(frame.depth <= MAXIMUM_DEPTH)
                    require(records.size < MAXIMUM_NODE_COUNT)
                    require(frame.node.childCount <= MAXIMUM_CHILDREN_PER_NODE)
                    records += frame.node.toRecord(frame.depth, frame.siblingIndex, screenBounds)
                    for (index in frame.node.childCount - 1 downTo 0) {
                        frame.node.getChild(index)?.let { child ->
                            pending.addFirst(NodeFrame(child, frame.depth + 1, index, true))
                        }
                    }
                } finally {
                    if (frame.recycleAfterRead) frame.node.recycleOwned()
                }
            }
            return records
        } finally {
            while (pending.isNotEmpty()) {
                val frame = pending.removeFirst()
                if (frame.recycleAfterRead) frame.node.recycleOwned()
            }
        }
    }

    private fun readSemanticRoleCandidates(
        root: AccessibilityNodeInfo,
        screenBounds: Rect,
    ): Map<String, List<WechatDebugRoleCandidate>> = buildMap {
        semanticQueries.forEach { (role, query) ->
            val candidates = root.findAccessibilityNodeInfosByText(query).orEmpty()
            try {
                require(candidates.size <= MAXIMUM_ROLE_CANDIDATES)
                put(
                    role,
                    candidates.map { candidate ->
                        WechatDebugRoleCandidate(
                            role,
                            candidate.toRecord(null, null, screenBounds),
                        )
                    },
                )
            } finally {
                candidates.forEach { candidate -> candidate.recycleOwned() }
            }
        }
        val focusedInput = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        try {
            put(
                "FOCUSED_INPUT",
                focusedInput?.let { node ->
                    listOf(
                        WechatDebugRoleCandidate(
                            "FOCUSED_INPUT",
                            node.toRecord(null, null, screenBounds),
                        ),
                    )
                }.orEmpty(),
            )
        } finally {
            focusedInput?.recycleOwned()
        }
    }

    private fun AccessibilityNodeInfo.toRecord(
        depth: Int?,
        siblingIndex: Int?,
        screenBounds: Rect,
    ): WechatDebugNodeRecord {
        val nodeBounds = Rect()
        getBoundsInScreen(nodeBounds)
        val hasPositiveBounds = nodeBounds.width() > 0 && nodeBounds.height() > 0
        val actionIds = actionList.asSequence().map { action -> action.id }.toSet()
        return WechatDebugNodeRecord(
            depth = depth,
            siblingIndex = siblingIndex,
            childCount = childCount,
            className = className?.toString().orEmpty(),
            viewIdResourceName = viewIdResourceName.orEmpty(),
            clickable = isClickable,
            editable = isEditable,
            enabled = isEnabled,
            visibleToUser = isVisibleToUser,
            hasPositiveBounds = hasPositiveBounds,
            centerOnScreen = hasPositiveBounds && screenBounds.contains(
                nodeBounds.centerX(),
                nodeBounds.centerY(),
            ),
            supportsClickAction = AccessibilityNodeInfo.ACTION_CLICK in actionIds,
            supportsSetTextAction = AccessibilityNodeInfo.ACTION_SET_TEXT in actionIds,
            inputFocused = isFocused,
        )
    }

    private data class NodeFrame(
        val node: AccessibilityNodeInfo,
        val depth: Int,
        val siblingIndex: Int,
        val recycleAfterRead: Boolean,
    )

    @Suppress("DEPRECATION")
    private fun AccessibilityNodeInfo.recycleOwned() {
        recycle()
    }
}

enum class WechatDebugNodeProbeResult {
    CAPTURED,
    CLEARED,
    INVALID_STAGE,
    SERVICE_UNAVAILABLE,
    ROOT_UNAVAILABLE,
    WRONG_PACKAGE,
    READ_FAILED,
    WRITE_FAILED,
}

internal data class WechatDebugNodeRecord(
    val depth: Int?,
    val siblingIndex: Int?,
    val childCount: Int,
    val className: String,
    val viewIdResourceName: String,
    val clickable: Boolean,
    val editable: Boolean,
    val enabled: Boolean,
    val visibleToUser: Boolean,
    val hasPositiveBounds: Boolean,
    val centerOnScreen: Boolean,
    val supportsClickAction: Boolean,
    val supportsSetTextAction: Boolean,
    val inputFocused: Boolean,
)

internal data class WechatDebugRoleCandidate(
    val role: String,
    val node: WechatDebugNodeRecord,
)

/** 纯结构报告编码器；没有接收页面文字或坐标的字段。 */
internal object WechatDebugNodeReport {
    private const val EMPTY = "<empty>"
    private const val INVALID = "<invalid>"
    private val safeField = Regex("[A-Za-z0-9_.$:/-]{1,200}")

    fun render(
        stage: String,
        nodes: List<WechatDebugNodeRecord>,
        roles: Map<String, List<WechatDebugRoleCandidate>>,
    ): String = buildString {
        appendLine("AI_FRIEND_WECHAT_NODE_PROBE_V1")
        appendLine("stage=${stage.safe()}")
        appendLine("nodeCount=${nodes.size}")
        nodes.forEach { node ->
            append("NODE|")
            append(node.depth ?: -1)
            append('|')
            append(node.siblingIndex ?: -1)
            append('|')
            append(node.childCount)
            append('|')
            append(node.className.safe())
            append('|')
            append(node.viewIdResourceName.safe())
            append('|')
            append(node.clickable)
            append('|')
            append(node.editable)
            append('|')
            append(node.enabled)
            append('|')
            append(node.visibleToUser)
            append('|')
            append(node.hasPositiveBounds)
            append('|')
            append(node.centerOnScreen)
            append('|')
            append(node.supportsClickAction)
            append('|')
            append(node.supportsSetTextAction)
            append('|')
            appendLine(node.inputFocused)
        }
        roles.toSortedMap().forEach { (role, candidates) ->
            appendLine("ROLE_COUNT|${role.safe()}|${candidates.size}")
            candidates.forEach { candidate ->
                val node = candidate.node
                append("ROLE|")
                append(candidate.role.safe())
                append('|')
                append(node.className.safe())
                append('|')
                append(node.viewIdResourceName.safe())
                append('|')
                append(node.childCount)
                append('|')
                append(node.clickable)
                append('|')
                append(node.editable)
                append('|')
                append(node.enabled)
                append('|')
                append(node.visibleToUser)
                append('|')
                append(node.hasPositiveBounds)
                append('|')
                append(node.centerOnScreen)
                append('|')
                append(node.supportsClickAction)
                append('|')
                append(node.supportsSetTextAction)
                append('|')
                appendLine(node.inputFocused)
            }
        }
    }

    private fun String.safe(): String = when {
        isEmpty() -> EMPTY
        matches(safeField) -> this
        else -> INVALID
    }
}
