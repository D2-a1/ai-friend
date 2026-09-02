package com.aifriend.feature.guardian

/** 抢话真机诊断场景；只描述测试条件，不进入生产守护状态机。 */
enum class GuardianBargeInScenario {
    ECHO_ONLY,
    MARKED_SPEECH,
}

/** 抢话真机诊断结论；未取得完整时序时不能判为通过。 */
enum class GuardianBargeInDecision {
    PASSED,
    FAILED,
    INCOMPLETE,
}

/**
 * 不包含原始音频、识别文字或用户身份的抢话诊断结果。
 *
 * [falseVoiceStarts] 只统计测试已知不应有人声的区间内，能量门限从静音切换为人声的次数。
 */
data class GuardianBargeInDiagnosticResult(
    val scenario: GuardianBargeInScenario,
    val decision: GuardianBargeInDecision,
    val falseVoiceStarts: Int,
    val acknowledgementStartedAtMs: Long,
    val acknowledgementFinishedAtMs: Long?,
    val externalSpeechMarkedAtMs: Long?,
    val firstVoiceAfterMarkerAtMs: Long?,
    val detectionLatencyMs: Long?,
)

/**
 * 把真机探针产生的单调时钟和布尔人声信号归并为可审计的抢话诊断结果。
 *
 * 该类不接收 PCM，也不识别文本。生产链不能根据单次结果自动启用抢话；必须完成目标设备
 * 重复测试并人工评审后，才能另行修改守护服务。
 */
class GuardianBargeInEvaluator(
    private val echoGuardAfterAcknowledgementMs: Long = ECHO_GUARD_AFTER_ACKNOWLEDGEMENT_MS,
    private val maximumDetectionLatencyMs: Long = MAXIMUM_DETECTION_LATENCY_MS,
) {
    private var session: Session? = null

    init {
        require(echoGuardAfterAcknowledgementMs >= 0) { "播报后回声保护窗口无效" }
        require(maximumDetectionLatencyMs > 0) { "抢话探测延迟边界无效" }
    }

    /** 开始一次独立诊断；旧会话会被清除，不能跨测试复用状态。 */
    @Synchronized
    fun start(scenario: GuardianBargeInScenario, acknowledgementStartedAtMs: Long) {
        require(acknowledgementStartedAtMs >= 0) { "播报开始时间无效" }
        session = Session(scenario, acknowledgementStartedAtMs)
    }

    /** 记录固定离线应答结束；结束时间不得早于开始时间。 */
    @Synchronized
    fun acknowledgementFinished(atMs: Long) {
        val current = requireSession()
        require(atMs >= current.lastEventAtMs) { "诊断事件时间必须单调递增" }
        current.acknowledgementFinishedAtMs = atMs
        current.lastEventAtMs = atMs
    }

    /**
     * 记录测试员开始说话的外部标记。
     *
     * 标记只用于诊断时序，不表示已经识别出任何语音内容。
     */
    @Synchronized
    fun markExternalSpeechStarted(atMs: Long) {
        val current = requireSession()
        require(current.scenario == GuardianBargeInScenario.MARKED_SPEECH) {
            "回声测试不能标记外部说话"
        }
        require(atMs >= current.lastEventAtMs) { "诊断事件时间必须单调递增" }
        check(current.externalSpeechMarkedAtMs == null) { "外部说话只能标记一次" }
        current.externalSpeechMarkedAtMs = atMs
        current.lastEventAtMs = atMs
    }

    /** 记录一帧只含时钟与能量门限结果的信号，不保留 PCM。 */
    @Synchronized
    fun observe(atMs: Long, voiced: Boolean) {
        val current = requireSession()
        require(atMs >= current.lastEventAtMs) { "诊断事件时间必须单调递增" }
        val voiceStarted = voiced && !current.voiced
        val marker = current.externalSpeechMarkedAtMs
        if (voiceStarted && marker != null && atMs >= marker) {
            if (current.firstVoiceAfterMarkerAtMs == null) {
                current.firstVoiceAfterMarkerAtMs = atMs
            }
        } else if (voiceStarted && shouldCountAsFalseStart(current, atMs)) {
            current.falseVoiceStarts++
        }
        current.voiced = voiced
        current.lastEventAtMs = atMs
    }

    /** 完成本次诊断并立即清除内部状态。 */
    @Synchronized
    fun finish(atMs: Long): GuardianBargeInDiagnosticResult {
        val current = requireSession()
        require(atMs >= current.lastEventAtMs) { "诊断结束时间必须单调递增" }
        val marker = current.externalSpeechMarkedAtMs
        val firstVoice = current.firstVoiceAfterMarkerAtMs
        val latency = if (marker != null && firstVoice != null) firstVoice - marker else null
        val complete = current.acknowledgementFinishedAtMs != null &&
            (current.scenario == GuardianBargeInScenario.ECHO_ONLY || marker != null)
        val passed = when (current.scenario) {
            GuardianBargeInScenario.ECHO_ONLY -> current.falseVoiceStarts == 0
            GuardianBargeInScenario.MARKED_SPEECH ->
                current.falseVoiceStarts == 0 && latency != null &&
                    latency in 0..maximumDetectionLatencyMs
        }
        val result = GuardianBargeInDiagnosticResult(
            scenario = current.scenario,
            decision = when {
                !complete -> GuardianBargeInDecision.INCOMPLETE
                passed -> GuardianBargeInDecision.PASSED
                else -> GuardianBargeInDecision.FAILED
            },
            falseVoiceStarts = current.falseVoiceStarts,
            acknowledgementStartedAtMs = current.acknowledgementStartedAtMs,
            acknowledgementFinishedAtMs = current.acknowledgementFinishedAtMs,
            externalSpeechMarkedAtMs = marker,
            firstVoiceAfterMarkerAtMs = firstVoice,
            detectionLatencyMs = latency,
        )
        session = null
        return result
    }

    /** 取消、锁屏、占麦或异常时丢弃尚未完成的诊断状态。 */
    @Synchronized
    fun clear() {
        session = null
    }

    private fun shouldCountAsFalseStart(current: Session, atMs: Long): Boolean {
        val acknowledgementFinished = current.acknowledgementFinishedAtMs
        return acknowledgementFinished == null ||
            atMs <= acknowledgementFinished + echoGuardAfterAcknowledgementMs
    }

    private fun requireSession(): Session = checkNotNull(session) { "抢话诊断尚未开始" }

    private class Session(
        val scenario: GuardianBargeInScenario,
        val acknowledgementStartedAtMs: Long,
        var acknowledgementFinishedAtMs: Long? = null,
        var externalSpeechMarkedAtMs: Long? = null,
        var firstVoiceAfterMarkerAtMs: Long? = null,
        var falseVoiceStarts: Int = 0,
        var voiced: Boolean = false,
        var lastEventAtMs: Long = acknowledgementStartedAtMs,
    )

    private companion object {
        const val ECHO_GUARD_AFTER_ACKNOWLEDGEMENT_MS = 300L
        const val MAXIMUM_DETECTION_LATENCY_MS = 500L
    }
}
