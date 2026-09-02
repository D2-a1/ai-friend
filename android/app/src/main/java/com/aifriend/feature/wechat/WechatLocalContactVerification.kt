package com.aifriend.feature.wechat

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import com.aifriend.contract.model.WechatActionType
import com.aifriend.feature.contact.LocalWechatPageType
import com.aifriend.feature.contact.LocalWechatVerificationEvidence
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 本机联系人验证的非敏感界面状态。 */
data class WechatLocalVerificationCaptureState(
    val accessibilityReady: Boolean = false,
    val active: Boolean = false,
    val completedObservations: Int = 0,
    val awaiting: Boolean = false,
    val ready: Boolean = false,
    val wechatVersion: String? = null,
    val message: String = "",
)

/** 联系人页使用的显式本机验证端口。 */
interface WechatLocalContactVerificationCoordinator {
    val state: StateFlow<WechatLocalVerificationCaptureState>

    fun start()

    fun beginObservation(): Boolean

    fun refresh()

    fun failToOpenWechat()

    fun buildEvidence(expectedContactVersion: Long): LocalWechatVerificationEvidence?

    fun cancel()
}

/** 单次由用户明确打开的微信联系人资料页观察窗口。 */
data class WechatLocalVerificationRequest(
    val wechatVersion: String,
    val openedAt: OffsetDateTime,
    val expiresAt: OffsetDateTime,
)

/**
 * 只读节点遍历产生的瞬时样本。
 *
 * 定位字符的所有权交给状态机；无论样本是否有效，状态机都会立即清零输入数组。
 */
data class WechatLocalVerificationSample(
    val stableLocatorCandidates: List<CharArray>,
    val friendActionMatchCount: Int,
    val pageSignatureSha256: String,
    val nodeCount: Int,
    val capturedAt: OffsetDateTime,
) {
    fun clear() {
        stableLocatorCandidates.forEach { it.fill('\u0000') }
    }

    override fun toString(): String =
        "WechatLocalVerificationSample(stableLocatorCandidates=<redacted>, " +
            "friendActionMatchCount=$friendActionMatchCount, " +
            "pageSignatureSha256=$pageSignatureSha256, nodeCount=$nodeCount, " +
            "capturedAt=$capturedAt)"
}

/** 仅接受微信联系人资料页中的严格微信号字段和固定好友动作标签。 */
internal object WechatLocalVerificationTextRule {
    private val LOCATOR_LABELS = listOf("微信号：", "微信号:")

    fun locator(text: CharSequence?): CharArray? {
        if (text == null || text.length > MAXIMUM_TEXT_LENGTH) return null
        var start = 0
        while (start < text.length && text[start].isWhitespace()) start++
        val label = LOCATOR_LABELS.firstOrNull { text.startsWith(it, start) } ?: return null
        start += label.length
        while (start < text.length && text[start].isWhitespace()) start++
        var end = text.length
        while (end > start && text[end - 1].isWhitespace()) end--
        val length = end - start
        if (length !in MINIMUM_LOCATOR_LENGTH..MAXIMUM_LOCATOR_LENGTH ||
            !text[start].isAsciiLetter()
        ) {
            return null
        }
        val result = CharArray(length)
        for (index in 0 until length) {
            val character = text[start + index]
            if (!character.isAsciiLetterOrDigit() && character != '_' && character != '-') {
                result.fill('\u0000')
                return null
            }
            result[index] = character
        }
        return result
    }

    fun isFriendAction(text: CharSequence?): Boolean {
        return isExactAction(text, FRIEND_ACTION)
    }

    fun isContactProfileAction(
        text: CharSequence?,
        action: WechatActionType,
    ): Boolean = isExactAction(text, contactProfileActionQuery(action))

    fun contactProfileActionQuery(action: WechatActionType): String = when (action) {
        WechatActionType.SEND_AUDIO_AND_TEXT -> FRIEND_ACTION
        WechatActionType.START_VOICE_CALL,
        WechatActionType.START_VIDEO_CALL,
        -> CALL_ACTION
    }

    private fun isExactAction(text: CharSequence?, expected: String): Boolean {
        if (text == null || text.length > MAXIMUM_ACTION_TEXT_LENGTH) return false
        var start = 0
        while (start < text.length && text[start].isWhitespace()) start++
        var end = text.length
        while (end > start && text[end - 1].isWhitespace()) end--
        if (end - start != expected.length) return false
        return expected.indices.all { index -> text[start + index] == expected[index] }
    }

    private fun Char.isAsciiLetter(): Boolean = this in 'A'..'Z' || this in 'a'..'z'

    private fun Char.isAsciiLetterOrDigit(): Boolean = isAsciiLetter() || this in '0'..'9'

    private const val FRIEND_ACTION = "发消息"
    private const val CALL_ACTION = "音视频通话"
    private const val MINIMUM_LOCATOR_LENGTH = 6
    private const val MAXIMUM_LOCATOR_LENGTH = 64
    private const val MAXIMUM_TEXT_LENGTH = 96
    private const val MAXIMUM_ACTION_TEXT_LENGTH = 16
}

/** 本机验证只保留固定候选节点的非敏感结构，不包含微信号或页面文字。 */
internal data class WechatLocalVerificationNodeShape(
    val role: Role,
    val className: String,
    val viewIdResourceName: String,
    val childCount: Int,
) {
    enum class Role {
        LOCATOR,
        FRIEND_ACTION,
        CALL_ACTION,
    }
}

/** 对严格命中的微信号和发消息节点生成版本化脱敏摘要。 */
internal object WechatLocalVerificationShapeCanonicalizer {
    private const val FORMAT_MAGIC = "AI_FRIEND_WECHAT_LOCAL_VERIFICATION_SHAPES_V1"

    fun sha256(nodes: List<WechatLocalVerificationNodeShape>): String {
        require(nodes.size <= MAXIMUM_SHAPES)
        val bytes = ByteArrayOutputStream(256)
        DataOutputStream(bytes).use { output ->
            output.writeString(FORMAT_MAGIC)
            output.writeInt(nodes.size)
            nodes.forEach { node ->
                require(node.className.length <= MAXIMUM_FIELD_LENGTH)
                require(node.viewIdResourceName.length <= MAXIMUM_FIELD_LENGTH)
                require(node.childCount in 0..MAXIMUM_CHILDREN)
                output.writeInt(node.role.ordinal)
                output.writeString(node.className)
                output.writeString(node.viewIdResourceName)
                output.writeInt(node.childCount)
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun DataOutputStream.writeString(value: String) {
        val encoded = value.toByteArray(StandardCharsets.UTF_8)
        writeInt(encoded.size)
        write(encoded)
    }

    internal const val MAXIMUM_SHAPES = 16
    private const val MAXIMUM_FIELD_LENGTH = 200
    private const val MAXIMUM_CHILDREN = 256
}

/** 把正式通话资料页使用的同一窄形状转成 Debug 规则采样结果。 */
internal object WechatContactProfileCallRuleSampleFactory {
    fun build(
        shapes: List<WechatLocalVerificationNodeShape>,
        locatorCandidateCount: Int,
        actionNodeMatchCount: Int,
        capturedAt: OffsetDateTime,
    ): WechatPageStructureSample? {
        val locatorShapes = shapes.filter {
            it.role == WechatLocalVerificationNodeShape.Role.LOCATOR
        }
        val callActionShapes = shapes.filter {
            it.role == WechatLocalVerificationNodeShape.Role.CALL_ACTION
        }
        if (locatorCandidateCount != 1 || actionNodeMatchCount != 1 ||
            shapes.size != 2 || locatorShapes.size != 1 || callActionShapes.size != 1
        ) {
            return null
        }
        return WechatPageStructureSample(
            signatureSha256 = WechatLocalVerificationShapeCanonicalizer.sha256(shapes),
            nodeCount = shapes.size,
            capturedAt = capturedAt,
            locatorSourceSha256s = setOf(
                WechatLocalVerificationShapeCanonicalizer.sha256(locatorShapes),
            ),
        )
    }
}

/** 单次有界遍历期间使用，结束后不会保留节点或页面文字。 */
internal class WechatLocalVerificationNodeCollector(
    private val executionAction: WechatActionType = WechatActionType.SEND_AUDIO_AND_TEXT,
) {
    private val locatorCandidates = ArrayList<CharArray>()
    private val matchedNodeShapes = ArrayList<WechatLocalVerificationNodeShape>()
    private var friendActionMatchCount = 0
    private var invalid = false

    fun observe(root: AccessibilityNodeInfo) {
        collectMatchingNodes(root, LOCATOR_QUERY) { node, text ->
            WechatLocalVerificationTextRule.locator(text)?.let { locator ->
                if (addLocator(locator)) {
                    addShape(WechatLocalVerificationNodeShape.Role.LOCATOR, node)
                }
            }
        }
        val actionQuery = WechatLocalVerificationTextRule.contactProfileActionQuery(executionAction)
        collectMatchingNodes(root, actionQuery) { node, text ->
            if (WechatLocalVerificationTextRule.isContactProfileAction(text, executionAction)) {
                friendActionMatchCount++
                if (friendActionMatchCount > MAXIMUM_MATCHES) {
                    invalid = true
                } else {
                    addShape(
                        if (executionAction == WechatActionType.SEND_AUDIO_AND_TEXT) {
                            WechatLocalVerificationNodeShape.Role.FRIEND_ACTION
                        } else {
                            WechatLocalVerificationNodeShape.Role.CALL_ACTION
                        },
                        node,
                    )
                }
            }
        }
    }

    fun build(capturedAt: OffsetDateTime): WechatLocalVerificationSample? {
        if (invalid) return null
        val pageSignature = WechatLocalVerificationShapeCanonicalizer.sha256(
            matchedNodeShapes,
        )
        val transferred = locatorCandidates.toList()
        locatorCandidates.clear()
        return WechatLocalVerificationSample(
            stableLocatorCandidates = transferred,
            friendActionMatchCount = friendActionMatchCount,
            pageSignatureSha256 = pageSignature,
            nodeCount = matchedNodeShapes.size,
            capturedAt = capturedAt,
        )
    }

    /** 将同一批严格匹配结果移交给一次性正式消息资料页观察；调用方负责最终清零。 */
    fun buildExecutionEvidence(): WechatSensitivePageEvidence? {
        if (invalid) return null
        val pageSignature = WechatLocalVerificationShapeCanonicalizer.sha256(
            matchedNodeShapes,
        )
        val transferred = locatorCandidates.toList()
        locatorCandidates.clear()
        return WechatSensitivePageEvidence(
            stableLocatorCandidates = transferred,
            actionNodeMatchCount = friendActionMatchCount,
            pageSignatureSha256 = pageSignature,
        )
    }

    /** Debug 规则采样只复制脱敏形状摘要；定位字符仍由 [clear] 立即清零。 */
    fun buildCallRuleSample(capturedAt: OffsetDateTime): WechatPageStructureSample? {
        if (invalid) return null
        return WechatContactProfileCallRuleSampleFactory.build(
            shapes = matchedNodeShapes,
            locatorCandidateCount = locatorCandidates.size,
            actionNodeMatchCount = friendActionMatchCount,
            capturedAt = capturedAt,
        )
    }

    fun clear() {
        locatorCandidates.forEach { it.fill('\u0000') }
        locatorCandidates.clear()
        matchedNodeShapes.clear()
        friendActionMatchCount = 0
        invalid = false
    }

    private fun addLocator(locator: CharArray): Boolean {
        if (locatorCandidates.size >= MAXIMUM_MATCHES) {
            locator.fill('\u0000')
            invalid = true
            return false
        } else {
            locatorCandidates += locator
            return true
        }
    }

    private fun addShape(
        role: WechatLocalVerificationNodeShape.Role,
        node: AccessibilityNodeInfo,
    ) {
        if (matchedNodeShapes.size >= WechatLocalVerificationShapeCanonicalizer.MAXIMUM_SHAPES) {
            invalid = true
            return
        }
        val className = node.className?.toString().orEmpty()
        val viewId = node.viewIdResourceName.orEmpty()
        if (className.length > MAXIMUM_STRUCTURE_FIELD_LENGTH ||
            viewId.length > MAXIMUM_STRUCTURE_FIELD_LENGTH ||
            node.childCount !in 0..MAXIMUM_STRUCTURE_CHILDREN
        ) {
            invalid = true
            return
        }
        matchedNodeShapes += WechatLocalVerificationNodeShape(
            role = role,
            className = className,
            viewIdResourceName = viewId,
            childCount = node.childCount,
        )
    }

    private fun collectMatchingNodes(
        root: AccessibilityNodeInfo,
        query: String,
        consume: (AccessibilityNodeInfo, CharSequence?) -> Unit,
    ) {
        val nodes = runCatching { root.findAccessibilityNodeInfosByText(query) }
            .getOrNull()
            ?: return
        if (nodes.size > MAXIMUM_MATCHES) invalid = true
        nodes.forEach { node ->
            try {
                consume(node, node.text)
            } finally {
                node.recycleOwned()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun AccessibilityNodeInfo.recycleOwned() {
        recycle()
    }

    private companion object {
        const val MAXIMUM_MATCHES = 8
        const val MAXIMUM_STRUCTURE_FIELD_LENGTH = 200
        const val MAXIMUM_STRUCTURE_CHILDREN = 256
        const val LOCATOR_QUERY = "微信号"
    }
}

/** 一次明确打开微信资料页的联系人确认状态机。 */
class WechatLocalVerificationSession {
    private val locators = ArrayList<CharArray>(REQUIRED_OBSERVATIONS)
    private val pageSignatures = ArrayList<String>(REQUIRED_OBSERVATIONS)
    private var expectedWechatVersion: String? = null
    private var lastCapturedAt: OffsetDateTime? = null
    private var request: WechatLocalVerificationRequest? = null
    private var accessibilityReady = false
    private val mutableState = MutableStateFlow(WechatLocalVerificationCaptureState())

    val state: StateFlow<WechatLocalVerificationCaptureState> = mutableState.asStateFlow()

    @Synchronized
    fun start() {
        clearEvidenceLocked()
        mutableState.value = WechatLocalVerificationCaptureState(
            accessibilityReady = accessibilityReady,
            active = true,
            message = if (accessibilityReady) {
                "请打开一次微信，进入这位亲友的资料页完成确认。"
            } else {
                ACCESSIBILITY_NOT_READY_MESSAGE
            },
        )
    }

    @Synchronized
    fun updateAccessibilityReady(ready: Boolean) {
        if (accessibilityReady == ready) return
        accessibilityReady = ready
        if (!ready) request = null
        val current = state.value
        mutableState.value = current.copy(
            accessibilityReady = ready,
            awaiting = if (ready) current.awaiting else false,
            message = when {
                !current.active -> current.message
                !ready -> ACCESSIBILITY_NOT_READY_MESSAGE
                !current.awaiting && !current.ready ->
                    "微信辅助服务已连接，可以开始第 ${locators.size + 1} 次验证。"
                else -> current.message
            },
        )
    }

    @Synchronized
    fun begin(wechatVersion: String, now: OffsetDateTime): Boolean {
        expireLocked(now)
        if (!state.value.active || request != null || state.value.ready) return false
        if (!accessibilityReady) {
            failLocked(ACCESSIBILITY_NOT_READY_MESSAGE)
            return false
        }
        if (!wechatVersion.matches(VERSION_TOKEN)) {
            failLocked("无法读取当前微信版本，请确认微信已经安装。")
            return false
        }
        val expected = expectedWechatVersion
        if (expected != null && expected != wechatVersion) {
            clearEvidenceLocked()
            mutableState.value = WechatLocalVerificationCaptureState(
                accessibilityReady = accessibilityReady,
                active = true,
                message = "微信版本发生变化，请重新完成本次确认。",
            )
            return false
        }
        expectedWechatVersion = wechatVersion
        request = WechatLocalVerificationRequest(
            wechatVersion = wechatVersion,
            openedAt = now,
            expiresAt = now.plus(OBSERVATION_WINDOW),
        )
        mutableState.value = WechatLocalVerificationCaptureState(
            accessibilityReady = accessibilityReady,
            active = true,
            completedObservations = locators.size,
            awaiting = true,
            wechatVersion = wechatVersion,
            message = "请在微信中进入这位亲友的资料页，并停留到本次验证完成。",
        )
        return true
    }

    @Synchronized
    fun activeRequest(packageName: String, now: OffsetDateTime): WechatLocalVerificationRequest? {
        expireLocked(now)
        return request?.takeIf { packageName == WECHAT_PACKAGE }
    }

    @Synchronized
    fun markWechatEventReceived(now: OffsetDateTime) {
        expireLocked(now)
        val current = request ?: return
        mutableState.value = state.value.copy(
            accessibilityReady = accessibilityReady,
            awaiting = true,
            wechatVersion = current.wechatVersion,
            message = "已收到微信页面变化，正在核对联系人资料页。",
        )
    }

    @Synchronized
    fun failCurrentRead(message: String) {
        if (request == null) return
        request = null
        failLocked(message)
    }

    @Synchronized
    fun publish(
        sample: WechatLocalVerificationSample,
        wechatVersion: String,
        now: OffsetDateTime,
    ) {
        try {
            expireLocked(now)
            val current = request ?: return
            if (wechatVersion != current.wechatVersion ||
                sample.capturedAt.isBefore(current.openedAt) ||
                sample.capturedAt.isAfter(current.expiresAt) ||
                !sample.pageSignatureSha256.matches(SHA256_HEX) ||
                sample.nodeCount !in 0..MAXIMUM_MATCHED_NODE_COUNT
            ) {
                return
            }
            val locatorCount = sample.stableLocatorCandidates.size
            val actionCount = sample.friendActionMatchCount
            if (locatorCount != 1 || actionCount != 1 || sample.nodeCount != 2) {
                mutableState.value = WechatLocalVerificationCaptureState(
                    accessibilityReady = accessibilityReady,
                    active = true,
                    completedObservations = locators.size,
                    awaiting = true,
                    wechatVersion = current.wechatVersion,
                    message = when {
                        locatorCount != 1 ->
                            "已进入微信，但没有识别到唯一的“微信号”字段。请打开这位亲友的资料页并停留。"
                        actionCount != 1 ->
                            "已识别微信号，但没有识别到唯一的“发消息”按钮。请确认对方已是微信好友。"
                        else ->
                            "微信资料页结构无法确认，请停留在同一位亲友的资料页后重试。"
                    },
                )
                return
            }
            request = null
            val locator = sample.stableLocatorCandidates.single()
            val expectedLocator = locators.firstOrNull()
            val expectedSignature = pageSignatures.firstOrNull()
            if ((expectedLocator != null && !expectedLocator.contentEquals(locator)) ||
                (expectedSignature != null && expectedSignature != sample.pageSignatureSha256)
            ) {
                clearEvidenceLocked()
                mutableState.value = WechatLocalVerificationCaptureState(
                    accessibilityReady = accessibilityReady,
                    active = true,
                    message = "联系人资料页与本次确认不一致，请重新确认。",
                )
                return
            }
            locators += locator.copyOf()
            pageSignatures += sample.pageSignatureSha256
            lastCapturedAt = sample.capturedAt
            val complete = locators.size == REQUIRED_OBSERVATIONS
            mutableState.value = WechatLocalVerificationCaptureState(
                accessibilityReady = accessibilityReady,
                active = true,
                completedObservations = locators.size,
                ready = complete,
                wechatVersion = current.wechatVersion,
                message = if (complete) {
                    "联系人资料页已确认。请返回小友并明确完成本机确认。"
                } else {
                    "已完成第 ${locators.size} 次，请返回小友后继续下一次。"
                },
            )
        } finally {
            sample.clear()
        }
    }

    @Synchronized
    fun refresh(now: OffsetDateTime) {
        expireLocked(now)
    }

    @Synchronized
    fun failToOpenWechat() {
        request = null
        failLocked("无法打开微信，请确认微信已经安装并可以正常启动。")
    }

    @Synchronized
    fun buildEvidence(expectedContactVersion: Long): LocalWechatVerificationEvidence? {
        if (!state.value.ready || expectedContactVersion < 1 || locators.size != REQUIRED_OBSERVATIONS) {
            return null
        }
        val locator = locators.firstOrNull() ?: return null
        val wechatVersion = expectedWechatVersion ?: return null
        val verifiedAt = lastCapturedAt ?: return null
        return LocalWechatVerificationEvidence(
            stableLocator = locator.concatToString(),
            currentRemark = null,
            pageType = LocalWechatPageType.CONTACT_PROFILE,
            friendConfirmed = true,
            locatorObservationCount = REQUIRED_OBSERVATIONS,
            locatorUnique = true,
            wechatVersion = wechatVersion,
            ruleVersion = RULE_VERSION,
            verifiedAt = verifiedAt,
            expectedContactVersion = expectedContactVersion,
        )
    }

    @Synchronized
    fun cancel() {
        clearEvidenceLocked()
        mutableState.value = WechatLocalVerificationCaptureState(
            accessibilityReady = accessibilityReady,
        )
    }

    @Synchronized
    fun interrupt() {
        if (request == null) return
        request = null
        failLocked("本次只读验证已中断，请重新打开微信完成这一遍。")
    }

    private fun expireLocked(now: OffsetDateTime) {
        val current = request ?: return
        if (now.isBefore(current.openedAt) || !current.expiresAt.isAfter(now)) {
            request = null
            failLocked("一分钟内没有识别到联系人资料页，请检查辅助服务后重试。")
        }
    }

    private fun failLocked(message: String) {
        mutableState.value = WechatLocalVerificationCaptureState(
            accessibilityReady = accessibilityReady,
            active = state.value.active,
            completedObservations = locators.size,
            wechatVersion = expectedWechatVersion,
            message = message,
        )
    }

    private fun clearEvidenceLocked() {
        request = null
        expectedWechatVersion = null
        lastCapturedAt = null
        locators.forEach { it.fill('\u0000') }
        locators.clear()
        pageSignatures.clear()
    }

    companion object {
        const val RULE_VERSION = "wechat-contact-profile-v1"
        const val REQUIRED_OBSERVATIONS = 1
        private val OBSERVATION_WINDOW: Duration = Duration.ofSeconds(60)
        private val VERSION_TOKEN = Regex("[^\\s:]{1,100}")
        private val SHA256_HEX = Regex("[0-9a-f]{64}")
        private const val MAXIMUM_MATCHED_NODE_COUNT = 16
        private const val WECHAT_PACKAGE = "com.tencent.mm"
        private const val ACCESSIBILITY_NOT_READY_MESSAGE =
            "微信辅助服务未连接。请先点击“检查微信辅助服务”，开启“小友”，返回后再重试。"
    }
}

/** Android 当前进程本机验证协调器。 */
@Singleton
class AndroidWechatLocalContactVerificationCoordinator @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : WechatLocalContactVerificationCoordinator {
    private val session = WechatLocalVerificationSession()

    override val state: StateFlow<WechatLocalVerificationCaptureState> = session.state

    override fun start() = session.start()

    override fun beginObservation(): Boolean {
        val version = readWechatVersion() ?: run {
            session.failToOpenWechat()
            return false
        }
        return session.begin(version, OffsetDateTime.now())
    }

    override fun refresh() = session.refresh(OffsetDateTime.now())

    override fun failToOpenWechat() = session.failToOpenWechat()

    override fun buildEvidence(expectedContactVersion: Long): LocalWechatVerificationEvidence? =
        session.buildEvidence(expectedContactVersion)

    override fun cancel() = session.cancel()

    fun activeRequest(
        packageName: String,
        now: OffsetDateTime,
    ): WechatLocalVerificationRequest? = session.activeRequest(packageName, now)

    fun publish(
        sample: WechatLocalVerificationSample,
        now: OffsetDateTime,
    ) {
        val version = readWechatVersion() ?: run {
            sample.clear()
            session.interrupt()
            return
        }
        session.publish(sample, version, now)
    }

    fun interrupt() = session.interrupt()

    fun updateAccessibilityReady(ready: Boolean) = session.updateAccessibilityReady(ready)

    fun markWechatEventReceived(now: OffsetDateTime) = session.markWechatEventReceived(now)

    fun failPageUnavailable() = session.failCurrentRead(
        "已收到微信页面变化，但系统没有提供页面内容。请返回小友检查辅助服务后重试。",
    )

    fun failPageRead() = session.failCurrentRead(
        "已收到微信页面变化，但没有完成页面读取。请返回小友后重新打开微信重试。",
    )

    @Suppress("DEPRECATION")
    private fun readWechatVersion(): String? = runCatching {
        context.packageManager.getPackageInfo(WECHAT_PACKAGE, 0).versionName
    }.getOrNull()

    private companion object {
        const val WECHAT_PACKAGE = "com.tencent.mm"
    }
}
