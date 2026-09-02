package com.aifriend.feature.wechat

import android.view.accessibility.AccessibilityNodeInfo
import com.aifriend.contract.model.WechatActionPlan
import com.aifriend.contract.model.WechatActionType
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.OffsetDateTime
import java.util.ArrayDeque
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** 当前计划允许开启的微信只读页面观察请求。 */
data class WechatPageObservationRequest(
    val planId: String,
    val action: WechatActionType,
    val capability: WechatCapabilitySnapshot,
    val targetLocatorProof: VerifiedWechatTargetLocatorProof,
    val locatorSalt: String,
    val openedAt: OffsetDateTime,
    val expiresAt: OffsetDateTime,
    val expectedPageType: WechatPageType? = null,
) {
    override fun toString(): String =
        "WechatPageObservationRequest(planId=<redacted>, action=${action.value}, " +
            "capability=$capability, " +
            "targetLocatorProof=$targetLocatorProof, locatorSalt=<redacted>, " +
            "openedAt=$openedAt, expiresAt=$expiresAt)"
}

/**
 * 当前进程一次性微信页面观察门闩。
 *
 * 不写 Room/DataStore，不跨进程恢复。新计划覆盖旧计划，过期、退出、服务中断或快照消费后
 * 立即清除；只有微信包事件可以取得当前请求。
 */
@Singleton
class WechatPageObservationBroker @Inject constructor() {
    private val mutablePublishedPlans = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private var request: WechatPageObservationRequest? = null
    private var snapshot: WechatPageSnapshot? = null

    val publishedPlans: SharedFlow<String> = mutablePublishedPlans.asSharedFlow()

    @Synchronized
    fun arm(
        plan: WechatActionPlan,
        capability: WechatCapabilitySnapshot,
        proof: VerifiedWechatTargetLocatorProof,
        sessionExpiresAt: OffsetDateTime,
        now: OffsetDateTime,
    ) {
        clearLocked()
        val rawProof = plan.targetLocatorProof
        val expiresAt = listOf(
            now.plus(OBSERVATION_WINDOW),
            sessionExpiresAt,
            plan.expiresAt,
            proof.expiresAt,
        ).minBy { it.toInstant() }
        if (!expiresAt.isAfter(now) || rawProof.salt.length != SALT_HEX_LENGTH) return
        request = WechatPageObservationRequest(
            planId = plan.planId,
            action = plan.action,
            capability = capability,
            targetLocatorProof = proof,
            locatorSalt = rawProof.salt,
            openedAt = now,
            expiresAt = expiresAt,
            // 每种动作都从已绑定联系人的资料页开始；后续聊天、选择和通话中页面
            // 分别由独立短时门闩确认，不能用多页面集合的 singleOrNull 推断初始页。
            expectedPageType = WechatPageType.CONTACT_PROFILE,
        )
    }

    @Synchronized
    fun activeRequest(packageName: String, now: OffsetDateTime): WechatPageObservationRequest? {
        val current = validRequest(now) ?: return null
        return current.takeIf { packageName == it.capability.packageName }
    }

    fun publish(
        planId: String,
        value: WechatPageSnapshot,
        now: OffsetDateTime,
    ) {
        val published = synchronized(this) {
            val current = validRequest(now) ?: return@synchronized false
            if (current.planId != planId || value.packageName != current.capability.packageName ||
                value.capturedAt.isBefore(current.openedAt) || value.capturedAt.isAfter(now)
            ) {
                return@synchronized false
            }
            snapshot = value
            true
        }
        if (published) mutablePublishedPlans.tryEmit(planId)
    }

    @Synchronized
    fun consume(planId: String, now: OffsetDateTime): WechatPageSnapshot? {
        val current = validRequest(now) ?: return null
        if (current.planId != planId) return null
        val value = snapshot ?: return null
        clearLocked()
        return value
    }

    @Synchronized
    fun clear() {
        clearLocked()
    }

    private fun validRequest(now: OffsetDateTime): WechatPageObservationRequest? {
        val current = request ?: return null
        if (now.isBefore(current.openedAt) || !current.expiresAt.isAfter(now)) {
            clearLocked()
            return null
        }
        return current
    }

    private fun clearLocked() {
        request = null
        snapshot = null
    }

    private companion object {
        val OBSERVATION_WINDOW: Duration = Duration.ofSeconds(5)
        const val SALT_HEX_LENGTH = 32
    }
}

/** 页面结构摘要只使用类名、资源 ID、深度、同级序号和子节点数量。 */
data class WechatStructuralNode(
    val depth: Int,
    val siblingIndex: Int,
    val childCount: Int,
    val className: String,
    val viewIdResourceName: String,
)

/** 版本化微信页面结构 SHA-256 规范化器。 */
object WechatPageStructureCanonicalizer {
    private const val FORMAT_MAGIC = "AI_FRIEND_WECHAT_PAGE_STRUCTURE_V1"

    fun sha256(nodes: List<WechatStructuralNode>): String {
        require(nodes.isNotEmpty() && nodes.size <= MAXIMUM_NODE_COUNT)
        val bytes = ByteArrayOutputStream(nodes.size * 64)
        DataOutputStream(bytes).use { output ->
            output.writeString(FORMAT_MAGIC)
            output.writeInt(nodes.size)
            nodes.forEach { node ->
                require(node.depth in 0..MAXIMUM_DEPTH)
                require(node.siblingIndex in 0 until MAXIMUM_CHILDREN_PER_NODE)
                require(node.childCount in 0..MAXIMUM_CHILDREN_PER_NODE)
                require(node.className.length <= MAXIMUM_FIELD_LENGTH)
                require(node.viewIdResourceName.length <= MAXIMUM_FIELD_LENGTH)
                output.writeInt(node.depth)
                output.writeInt(node.siblingIndex)
                output.writeInt(node.childCount)
                output.writeString(node.className)
                output.writeString(node.viewIdResourceName)
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).toHex()
    }

    private fun DataOutputStream.writeString(value: String) {
        val encoded = value.toByteArray(StandardCharsets.UTF_8)
        writeInt(encoded.size)
        write(encoded)
    }

    internal const val MAXIMUM_NODE_COUNT = 256
    internal const val MAXIMUM_DEPTH = 12
    internal const val MAXIMUM_CHILDREN_PER_NODE = 32
    internal const val MAXIMUM_FIELD_LENGTH = 200
}

/** 未来正式微信版本适配器可提供的瞬时敏感证据，使用后必须清零。 */
data class WechatSensitivePageEvidence(
    val stableLocatorCandidates: List<CharArray>,
    val actionNodeMatchCount: Int,
    val pageSignatureSha256: String? = null,
) {
    fun clear() {
        stableLocatorCandidates.forEach { it.fill('\u0000') }
    }

    companion object {
        fun unavailable(): WechatSensitivePageEvidence =
            WechatSensitivePageEvidence(emptyList(), 0)
    }
}

/** 从当前节点树提取目标定位候选和动作节点计数的版本化端口。 */
fun interface WechatSensitivePageEvidencePort {
    fun observe(
        root: AccessibilityNodeInfo,
        request: WechatPageObservationRequest,
    ): WechatSensitivePageEvidence
}

/**
 * 正式微信节点规则和真机样本缺失时的唯一生产实现。
 *
 * 不读取 text/contentDescription，不遍历节点，也不猜测资源 ID。
 */
@Singleton
class UnavailableWechatSensitivePageEvidencePort @Inject constructor() :
    WechatSensitivePageEvidencePort {
    override fun observe(
        root: AccessibilityNodeInfo,
        request: WechatPageObservationRequest,
    ): WechatSensitivePageEvidence = WechatSensitivePageEvidence.unavailable()
}

/** 仅允许三类有限动作在各自已签名的联系人资料页规则上读取固定候选证据。 */
internal object WechatContactProfileEvidencePolicy {
    fun supports(request: WechatPageObservationRequest): Boolean =
        request.capability.locatorVersion == WechatLocalVerificationSession.RULE_VERSION &&
            request.expectedPageType == WechatPageType.CONTACT_PROFILE &&
            request.action in request.capability.allowedActions &&
            WechatPageType.CONTACT_PROFILE in
            request.capability.allowedPageTypes[request.action].orEmpty() &&
            request.capability.pageSignatures(
                request.action,
                WechatPageType.CONTACT_PROFILE,
            ).isNotEmpty()
}

/**
 * 正式有限动作资料页的窄证据实现。
 *
 * 只复用已用于真实本机验证的严格“微信号/发消息”固定查询及脱敏形状摘要；不遍历整页、
 * 不读联系人名称/聊天/contentDescription，不保留节点或定位明文。签名能力未明确批准联系人
 * 资料页和同一定位版本时返回不可用。
 */
@Singleton
class VerifiedContactProfileWechatSensitivePageEvidencePort @Inject constructor() :
    WechatSensitivePageEvidencePort {
    override fun observe(
        root: AccessibilityNodeInfo,
        request: WechatPageObservationRequest,
    ): WechatSensitivePageEvidence {
        if (!WechatContactProfileEvidencePolicy.supports(request)) {
            return WechatSensitivePageEvidence.unavailable()
        }
        val collector = WechatLocalVerificationNodeCollector(request.action)
        return try {
            collector.observe(root)
            collector.buildExecutionEvidence()
                ?: WechatSensitivePageEvidence.unavailable()
        } catch (_: RuntimeException) {
            WechatSensitivePageEvidence.unavailable()
        } finally {
            collector.clear()
        }
    }
}

/** 将已脱敏结构与瞬时定位证据转换成最小页面快照。 */
object WechatReadOnlySnapshotTranslator {
    fun translate(
        request: WechatPageObservationRequest,
        structuralNodes: List<WechatStructuralNode>,
        sensitiveEvidence: WechatSensitivePageEvidence,
        capturedAt: OffsetDateTime,
    ): WechatPageSnapshot? {
        return try {
            if (capturedAt.isBefore(request.openedAt) || capturedAt.isAfter(request.expiresAt)) {
                return null
            }
            if (sensitiveEvidence.stableLocatorCandidates.size > MAXIMUM_LOCATOR_CANDIDATES ||
                sensitiveEvidence.actionNodeMatchCount !in 0..MAXIMUM_ACTION_NODE_MATCHES ||
                sensitiveEvidence.stableLocatorCandidates.any {
                    it.isEmpty() || it.size > MAXIMUM_LOCATOR_LENGTH
                }
            ) {
                return null
            }
            val signature = sensitiveEvidence.pageSignatureSha256
                ?: WechatPageStructureCanonicalizer.sha256(structuralNodes)
            if (signature.length != SHA256_HEX_LENGTH ||
                signature.any { character -> character.digitToIntOrNull(16) == null }
            ) {
                return null
            }
            val action = request.action
            val pageType = request.expectedPageType
                ?: request.capability.allowedPageTypes[action].orEmpty().singleOrNull()
                ?: return null
            if (pageType == WechatPageType.UNSUPPORTED ||
                pageType !in request.capability.allowedPageTypes[action].orEmpty() ||
                action !in request.capability.allowedActions ||
                request.targetLocatorProof.wechatVersion != request.capability.wechatVersion ||
                request.capability.pageSignatures(action, pageType).none { expected ->
                    constantTimeHexEquals(expected, signature)
                }
            ) {
                return null
            }
            val targetMatches = sensitiveEvidence.stableLocatorCandidates.count { candidate ->
                WechatTargetLocatorDigest.compute(candidate, request.locatorSalt)?.let { actual ->
                    constantTimeHexEquals(actual, request.targetLocatorProof.targetLocatorSha256)
                } == true
            }
            WechatPageSnapshot(
                packageName = request.capability.packageName,
                wechatVersion = request.capability.wechatVersion,
                ruleVersion = request.capability.ruleVersion,
                locatorVersion = request.capability.locatorVersion,
                pageType = pageType,
                pageSignatureSha256 = signature,
                targetLocatorSha256 = request.targetLocatorProof.targetLocatorSha256
                    .takeIf { targetMatches > 0 },
                targetMatchCount = targetMatches,
                actionNodeMatchCount = sensitiveEvidence.actionNodeMatchCount,
                capturedAt = capturedAt,
            )
        } catch (_: RuntimeException) {
            null
        } finally {
            sensitiveEvidence.clear()
        }
    }

    private fun constantTimeHexEquals(left: String, right: String): Boolean =
        MessageDigest.isEqual(left.encodeToByteArray(), right.encodeToByteArray())

    private const val MAXIMUM_LOCATOR_CANDIDATES = 8
    private const val MAXIMUM_LOCATOR_LENGTH = 256
    private const val MAXIMUM_ACTION_NODE_MATCHES = 8
    private const val SHA256_HEX_LENGTH = 64
}

/**
 * 对系统节点树执行有界只读遍历，并立即转换成不含页面文字的快照。
 *
 * 本类不调用 performAction/dispatchGesture，不访问 contentDescription，不保存节点树。正式观察不读取
 * text；Debug 主动采样只判断是否存在格式合规的“微信号”字段，并且编号不包含字段原文。
 */
data class WechatPageStructureSample(
    val signatureSha256: String,
    val nodeCount: Int,
    val capturedAt: OffsetDateTime,
    val locatorSourceSha256s: Set<String> = emptySet(),
)

@Singleton
class WechatAccessibilityPageReader @Inject constructor(
    private val sensitiveEvidencePort: WechatSensitivePageEvidencePort,
) {
    fun read(
        root: AccessibilityNodeInfo,
        request: WechatPageObservationRequest,
        capturedAt: OffsetDateTime,
    ): WechatPageSnapshot? {
        val startedAt = System.nanoTime()
        var evidence: WechatSensitivePageEvidence? = null
        var structures: ArrayList<WechatStructuralNode>? = null
        try {
            evidence = sensitiveEvidencePort.observe(root, request)
            if (System.nanoTime() - startedAt > MAXIMUM_READ_NANOS) {
                evidence.clear()
                return null
            }
            structures = if (evidence.pageSignatureSha256 == null) {
                readStructures(root, startedAt) ?: run {
                    evidence.clear()
                    return null
                }
            } else {
                arrayListOf()
            }
            return WechatReadOnlySnapshotTranslator.translate(
                request,
                structures,
                evidence,
                capturedAt,
            )
        } catch (_: RuntimeException) {
            evidence?.clear()
            return null
        } finally {
            structures?.clear()
        }
    }

    /**
     * Debug 联系人资料页采样严格复用正式语音/视频通话的窄证据算法。
     *
     * 只查询唯一“微信号”字段和唯一“音视频通话”动作；不遍历整页，不保存定位字符。
     */
    fun readContactProfileCallSample(
        root: AccessibilityNodeInfo,
        capturedAt: OffsetDateTime,
    ): WechatPageStructureSample? {
        val startedAt = System.nanoTime()
        val collector = WechatLocalVerificationNodeCollector(WechatActionType.START_VOICE_CALL)
        return try {
            collector.observe(root)
            if (System.nanoTime() - startedAt > MAXIMUM_LOCAL_VERIFICATION_READ_NANOS) return null
            collector.buildCallRuleSample(capturedAt)
        } catch (_: RuntimeException) {
            null
        } finally {
            collector.clear()
        }
    }

    /** 通话选择页与通话中页只读取结构摘要，不读取任何节点文字或定位来源。 */
    fun readStructureOnlySample(
        root: AccessibilityNodeInfo,
        capturedAt: OffsetDateTime,
    ): WechatPageStructureSample? {
        val startedAt = System.nanoTime()
        val structures = readStructures(root, startedAt) ?: return null
        return try {
            val signature = WechatPageStructureCanonicalizer.sha256(structures)
            if (System.nanoTime() - startedAt > MAXIMUM_READ_NANOS) return null
            WechatPageStructureSample(signature, structures.size, capturedAt)
        } catch (_: RuntimeException) {
            null
        } finally {
            structures.clear()
        }
    }

    /**
     * 用户明确发起本机验证时，只读取严格“微信号”字段和固定“发消息”标签。
     * 不读取 contentDescription，不保存节点树、页面文字或联系人名称。
     */
    fun readLocalVerificationSample(
        root: AccessibilityNodeInfo,
        capturedAt: OffsetDateTime,
    ): WechatLocalVerificationSample? {
        val startedAt = System.nanoTime()
        val collector = WechatLocalVerificationNodeCollector()
        return try {
            collector.observe(root)
            if (System.nanoTime() - startedAt > MAXIMUM_LOCAL_VERIFICATION_READ_NANOS) return null
            collector.build(capturedAt)
        } catch (_: RuntimeException) {
            null
        } finally {
            collector.clear()
        }
    }

    private fun readStructures(
        root: AccessibilityNodeInfo,
        startedAt: Long,
    ): ArrayList<WechatStructuralNode>? {
        val structures = ArrayList<WechatStructuralNode>()
        val pending = ArrayDeque<NodeFrame>()
        var completed = false
        pending.add(NodeFrame(root, 0, 0, false))
        try {
            while (pending.isNotEmpty()) {
                if (System.nanoTime() - startedAt > MAXIMUM_READ_NANOS) return null
                val frame = pending.removeFirst()
                try {
                    if (frame.depth > WechatPageStructureCanonicalizer.MAXIMUM_DEPTH ||
                        structures.size >= WechatPageStructureCanonicalizer.MAXIMUM_NODE_COUNT
                    ) {
                        return null
                    }
                    val className = frame.node.className?.toString().orEmpty()
                    val viewId = frame.node.viewIdResourceName.orEmpty()
                    if (className.length > WechatPageStructureCanonicalizer.MAXIMUM_FIELD_LENGTH ||
                        viewId.length > WechatPageStructureCanonicalizer.MAXIMUM_FIELD_LENGTH ||
                        frame.node.childCount >
                        WechatPageStructureCanonicalizer.MAXIMUM_CHILDREN_PER_NODE
                    ) {
                        return null
                    }
                    structures += WechatStructuralNode(
                        frame.depth,
                        frame.siblingIndex,
                        frame.node.childCount,
                        className,
                        viewId,
                    )
                    for (index in frame.node.childCount - 1 downTo 0) {
                        frame.node.getChild(index)?.let { child ->
                            pending.addFirst(NodeFrame(child, frame.depth + 1, index, true))
                        }
                    }
                } finally {
                    if (frame.recycleAfterRead) frame.node.recycleOwned()
                }
            }
            completed = true
            return structures
        } catch (_: RuntimeException) {
            return null
        } finally {
            while (pending.isNotEmpty()) {
                val frame = pending.removeFirst()
                if (frame.recycleAfterRead) frame.node.recycleOwned()
            }
            if (!completed) structures.clear()
        }
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

    private companion object {
        const val MAXIMUM_READ_NANOS = 50_000_000L
        const val MAXIMUM_LOCAL_VERIFICATION_READ_NANOS = 250_000_000L
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
