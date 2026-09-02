package com.aifriend.feature.wechat

import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 为语义通话执行器提供微信固定文字查询。
 *
 * 只查询“微信号”、“音视频通话”、“语音通话”和“视频通话”；不遍历整页、
 * 不读取 contentDescription、不猜父节点，也不使用坐标或手势。查询返回的节点只在同一次
 * [WechatSemanticCallExecutionExecutor.executeNext] 调用内持有并在结束时释放。
 */
class AndroidWechatSemanticCallUiPort internal constructor(
    private val root: WechatSemanticAccessibilityNode?,
) : WechatSemanticCallUiPort, AutoCloseable {
    private val retainedNodes = LinkedHashMap<Int, RetainedActionNode>()
    private var nextHandle = 0
    private var profileRead = false
    private var choiceRead = false
    private var closed = false

    override fun readContactProfileEvidence(
        locatorSalt: String,
        now: OffsetDateTime,
    ): WechatSemanticContactProfileEvidence? {
        if (closed || profileRead || choiceRead) return null
        profileRead = true
        val rootNode = root ?: return null
        val locatorNodes = rootNode.findByText(LOCATOR_QUERY) ?: return null
        if (locatorNodes.size > MAXIMUM_FIXED_QUERY_RESULTS) {
            locatorNodes.releaseAll()
            return null
        }
        val locatorEvidence = try {
            locatorNodes.mapNotNull { node ->
                if (node.packageName != WechatSemanticCallContract.WECHAT_PACKAGE) {
                    return@mapNotNull null
                }
                val locator = WechatLocalVerificationTextRule.locator(node.text)
                    ?: return@mapNotNull null
                try {
                    WechatTargetLocatorDigest.compute(locator, locatorSalt)?.let { digest ->
                        WechatSemanticLocatorEvidence(
                            targetLocatorSha256 = digest,
                            visibleToUser = node.visibleToUser,
                        )
                    }
                } finally {
                    locator.fill('\u0000')
                }
            }
        } finally {
            locatorNodes.releaseAll()
        }

        val callEntryNodes = queryExact(CALL_ENTRY_TEXT) ?: return null
        val callEntryEvidence = retainAsEvidence(callEntryNodes, CALL_ENTRY_TEXT)
        return WechatSemanticContactProfileEvidence(
            locatorCandidates = locatorEvidence,
            callEntryCandidates = callEntryEvidence,
            capturedAt = currentTime(),
        )
    }

    override fun clickContactProfileCallEntry(handle: Int): Boolean = clickOnce(handle)

    override fun readCallChoiceEvidence(now: OffsetDateTime): WechatSemanticCallChoiceEvidence? {
        if (closed || profileRead || choiceRead) return null
        choiceRead = true
        val voiceNodes = queryExact(VOICE_CALL_TEXT) ?: return null
        val videoNodes = queryExact(VIDEO_CALL_TEXT) ?: run {
            voiceNodes.releaseAll()
            return null
        }
        return WechatSemanticCallChoiceEvidence(
            actionCandidates =
                retainAsEvidence(voiceNodes, VOICE_CALL_TEXT) +
                    retainAsEvidence(videoNodes, VIDEO_CALL_TEXT),
            capturedAt = currentTime(),
        )
    }

    override fun clickCallChoice(handle: Int): Boolean = clickOnce(handle)

    override fun currentTime(): OffsetDateTime = OffsetDateTime.now()

    override fun close() {
        if (closed) return
        closed = true
        retainedNodes.values.forEach { retained -> retained.node.release() }
        retainedNodes.clear()
    }

    private fun queryExact(expected: String): List<WechatSemanticAccessibilityNode>? {
        val nodes = root?.findByText(expected) ?: return null
        if (nodes.size > MAXIMUM_FIXED_QUERY_RESULTS) {
            nodes.releaseAll()
            return null
        }
        val exact = ArrayList<WechatSemanticAccessibilityNode>(nodes.size)
        nodes.forEach { node ->
            if (node.packageName == WechatSemanticCallContract.WECHAT_PACKAGE &&
                node.text?.toString() == expected
            ) {
                exact += node
            } else {
                node.release()
            }
        }
        return exact
    }

    private fun retainAsEvidence(
        nodes: List<WechatSemanticAccessibilityNode>,
        fixedText: String,
    ): List<WechatSemanticActionNodeEvidence> = nodes.map { node ->
        val handle = nextHandle++
        retainedNodes[handle] = RetainedActionNode(node, fixedText)
        WechatSemanticActionNodeEvidence(
            handle = handle,
            text = fixedText,
            visibleToUser = node.visibleToUser,
            enabled = node.enabled,
            selfClickable = node.selfClickable,
        )
    }

    /** 先从一次性句柄表消费节点，再仅在节点自身执行一次 ACTION_CLICK。 */
    private fun clickOnce(handle: Int): Boolean {
        if (closed) return false
        val retained = retainedNodes.remove(handle) ?: return false
        val node = retained.node
        return try {
            if (node.packageName != WechatSemanticCallContract.WECHAT_PACKAGE ||
                node.text?.toString() != retained.fixedText ||
                !node.visibleToUser || !node.enabled || !node.selfClickable
            ) {
                false
            } else {
                node.clickSelf()
            }
        } finally {
            node.release()
            retainedNodes.values.forEach { remaining -> remaining.node.release() }
            retainedNodes.clear()
        }
    }

    private data class RetainedActionNode(
        val node: WechatSemanticAccessibilityNode,
        val fixedText: String,
    )

    private fun List<WechatSemanticAccessibilityNode>.releaseAll() {
        forEach(WechatSemanticAccessibilityNode::release)
    }

    private companion object {
        const val LOCATOR_QUERY = "微信号"
        const val CALL_ENTRY_TEXT = "音视频通话"
        const val VOICE_CALL_TEXT = "语音通话"
        const val VIDEO_CALL_TEXT = "视频通话"
        const val MAXIMUM_FIXED_QUERY_RESULTS = 8
    }
}

/** 每个微信事件创建一个短生命周期端口，不共享或持久化页面节点。 */
@Singleton
class AndroidWechatSemanticCallUiPortFactory @Inject constructor() {
    fun create(root: AccessibilityNodeInfo?): AndroidWechatSemanticCallUiPort =
        AndroidWechatSemanticCallUiPort(root?.let(::AndroidWechatSemanticAccessibilityNode))
}

/**
 * 测试可替换的最小节点表面；故意不暴露 contentDescription、父节点或子节点遍历。
 */
internal interface WechatSemanticAccessibilityNode {
    val text: CharSequence?
    val packageName: String?
    val visibleToUser: Boolean
    val enabled: Boolean
    val selfClickable: Boolean

    fun findByText(fixedText: String): List<WechatSemanticAccessibilityNode>?

    fun clickSelf(): Boolean

    fun release()
}

private class AndroidWechatSemanticAccessibilityNode(
    private val node: AccessibilityNodeInfo,
) : WechatSemanticAccessibilityNode {
    override val text: CharSequence?
        get() = runCatching { node.text }.getOrNull()
    override val packageName: String?
        get() = runCatching { node.packageName?.toString() }.getOrNull()
    override val visibleToUser: Boolean
        get() = runCatching { node.isVisibleToUser }.getOrDefault(false)
    override val enabled: Boolean
        get() = runCatching { node.isEnabled }.getOrDefault(false)
    override val selfClickable: Boolean
        get() = runCatching { node.isClickable }.getOrDefault(false)

    override fun findByText(fixedText: String): List<WechatSemanticAccessibilityNode>? =
        runCatching { node.findAccessibilityNodeInfosByText(fixedText) }
            .getOrNull()
            ?.map(::AndroidWechatSemanticAccessibilityNode)

    override fun clickSelf(): Boolean = runCatching {
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    override fun release() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) node.recycle()
    }
}
