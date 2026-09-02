package com.aifriend.feature.wechat

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.io.IOException
import java.lang.ref.WeakReference
import java.nio.charset.StandardCharsets

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
