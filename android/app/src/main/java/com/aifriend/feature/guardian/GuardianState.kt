package com.aifriend.feature.guardian

/** 小友守护在当前进程内的运行状态。 */
enum class GuardianMode {
    GUARDIAN_OFF,
    STARTING,
    SLEEPING,
    AWAKE_LISTENING,
    PROCESSING,
    WECHAT_BUSY,
    ERROR,
}

/** 首页和持续通知使用的最小守护状态，不包含任何音频或任务正文。 */
data class GuardianStatus(
    val mode: GuardianMode = GuardianMode.GUARDIAN_OFF,
    val message: String = "小友守护尚未开启",
    val permissionRecoveryRequired: Boolean = false,
) {
    val active: Boolean
        get() = mode in setOf(
            GuardianMode.STARTING,
            GuardianMode.SLEEPING,
            GuardianMode.AWAKE_LISTENING,
            GuardianMode.PROCESSING,
            GuardianMode.WECHAT_BUSY,
        )
}

/** 守护状态机可接受的受控事件。 */
sealed interface GuardianEvent {
    data object EnableRequested : GuardianEvent
    data object CaptureStarted : GuardianEvent
    data class WakeWordDetected(val elapsedRealtimeMs: Long) : GuardianEvent
    data object ListeningTimedOut : GuardianEvent
    data class TaskCaptureStopped(val message: String) : GuardianEvent
    data object ProcessingStarted : GuardianEvent
    data object ProcessingFinished : GuardianEvent
    data object AudioBecameBusy : GuardianEvent
    data object AudioBecameAvailable : GuardianEvent
    data object DisableRequested : GuardianEvent
    data object PermissionRevoked : GuardianEvent
    data class Failed(val message: String) : GuardianEvent
}

/**
 * 双唤醒与抢占事件的纯 Kotlin 状态机。
 *
 * 单次唤醒只记录候选；只有五秒内第二次命中才能进入任务监听。关闭、权限撤回、
 * 音频占用和错误均优先于唤醒，不允许迟到命中复活已关闭状态。
 *
 * @author codex
 * @since 2026-08-14
 */
class GuardianStateMachine(
    private val doubleWakeWindowMs: Long = DOUBLE_WAKE_WINDOW_MS,
) {
    var status: GuardianStatus = GuardianStatus()
        private set
    private var firstWakeAtMs: Long? = null
    private var resumeAfterBusy = false

    init {
        require(doubleWakeWindowMs > 0) { "双唤醒窗口必须大于零" }
    }

    /** 根据单个事件返回新的安全状态。 */
    fun reduce(event: GuardianEvent): GuardianStatus {
        status = when (event) {
            GuardianEvent.EnableRequested -> {
                firstWakeAtMs = null
                GuardianStatus(GuardianMode.STARTING, "正在开启小友守护")
            }
            GuardianEvent.CaptureStarted -> if (status.mode == GuardianMode.STARTING) {
                GuardianStatus(GuardianMode.SLEEPING, "正在等待您说两次小友")
            } else {
                status
            }
            is GuardianEvent.WakeWordDetected -> onWakeWord(event.elapsedRealtimeMs)
            GuardianEvent.ListeningTimedOut -> if (status.mode == GuardianMode.AWAKE_LISTENING) {
                firstWakeAtMs = null
                GuardianStatus(GuardianMode.SLEEPING, "没有听到完整需求，已继续等待唤醒")
            } else {
                status
            }
            is GuardianEvent.TaskCaptureStopped -> if (
                status.mode == GuardianMode.AWAKE_LISTENING || status.mode == GuardianMode.PROCESSING
            ) {
                firstWakeAtMs = null
                GuardianStatus(GuardianMode.SLEEPING, event.message)
            } else {
                status
            }
            GuardianEvent.ProcessingStarted -> if (status.mode == GuardianMode.AWAKE_LISTENING) {
                GuardianStatus(GuardianMode.PROCESSING, "正在理解这句话")
            } else {
                status
            }
            GuardianEvent.ProcessingFinished -> if (status.mode == GuardianMode.PROCESSING) {
                GuardianStatus(GuardianMode.SLEEPING, "正在等待您说两次小友")
            } else {
                status
            }
            GuardianEvent.AudioBecameBusy -> onAudioBusy()
            GuardianEvent.AudioBecameAvailable -> onAudioAvailable()
            GuardianEvent.DisableRequested,
            GuardianEvent.PermissionRevoked,
            -> {
                firstWakeAtMs = null
                resumeAfterBusy = false
                GuardianStatus(
                    GuardianMode.GUARDIAN_OFF,
                    if (event == GuardianEvent.PermissionRevoked) {
                        "麦克风或通知权限已关闭，小友守护已停止"
                    } else {
                        "小友守护尚未开启"
                    },
                    permissionRecoveryRequired = event == GuardianEvent.PermissionRevoked,
                )
            }
            is GuardianEvent.Failed -> {
                firstWakeAtMs = null
                resumeAfterBusy = false
                GuardianStatus(GuardianMode.ERROR, event.message)
            }
        }
        return status
    }

    private fun onWakeWord(nowMs: Long): GuardianStatus {
        if (status.mode != GuardianMode.SLEEPING) return status
        val first = firstWakeAtMs
        if (first == null || nowMs < first || nowMs - first > doubleWakeWindowMs) {
            firstWakeAtMs = nowMs
            return status
        }
        firstWakeAtMs = null
        return GuardianStatus(GuardianMode.AWAKE_LISTENING, "主人，我在，请说您的需求")
    }

    private fun onAudioBusy(): GuardianStatus {
        if (!status.active || status.mode == GuardianMode.WECHAT_BUSY) return status
        resumeAfterBusy = true
        firstWakeAtMs = null
        return GuardianStatus(GuardianMode.WECHAT_BUSY, "通话或其他应用正在使用麦克风，小友未监听")
    }

    private fun onAudioAvailable(): GuardianStatus {
        if (status.mode != GuardianMode.WECHAT_BUSY || !resumeAfterBusy) return status
        resumeAfterBusy = false
        return GuardianStatus(GuardianMode.SLEEPING, "麦克风已空闲，正在等待您说两次小友")
    }

    private companion object {
        const val DOUBLE_WAKE_WINDOW_MS = 5_000L
    }
}
