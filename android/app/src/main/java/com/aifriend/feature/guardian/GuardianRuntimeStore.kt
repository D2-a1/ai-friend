package com.aifriend.feature.guardian

import com.aifriend.core.feedback.HapticFeedbackPort
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 仅保存当前进程守护状态的内存仓库。
 *
 * 不接入 Room 或 DataStore，因此进程重建后的默认状态始终为关闭。
 *
 * @author codex
 * @since 2026-08-14
 */
@Singleton
class GuardianRuntimeStore @Inject constructor(
    private val hapticFeedbackPort: HapticFeedbackPort,
) {
    private val machine = GuardianStateMachine()
    private val mutableStatus = MutableStateFlow(machine.status)
    val status: StateFlow<GuardianStatus> = mutableStatus.asStateFlow()

    /** 顺序处理事件并发布不含敏感内容的状态。 */
    @Synchronized
    fun dispatch(event: GuardianEvent): GuardianStatus {
        val previous = mutableStatus.value
        return machine.reduce(event).also { next ->
            mutableStatus.value = next
            publishHapticTransition(previous, next)
        }
    }

    /** 服务被系统销毁时清理为关闭；错误状态保留给当前进程页面展示。 */
    @Synchronized
    fun onServiceDestroyed() {
        if (mutableStatus.value.mode != GuardianMode.ERROR) {
            val previous = mutableStatus.value
            val next = machine.reduce(GuardianEvent.DisableRequested)
            mutableStatus.value = next
            publishHapticTransition(previous, next)
        }
    }

    private fun publishHapticTransition(previous: GuardianStatus, next: GuardianStatus) {
        if (previous.mode != next.mode) {
            hapticFeedbackPort.emit(next.feedbackPresentation().hapticCue)
        }
    }
}
