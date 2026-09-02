package com.aifriend.feature.guardian

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/** 首页守护状态桥接器，不持有 Activity、音频或持久化开启标记。 */
@HiltViewModel
class GuardianViewModel @Inject constructor(
    private val runtimeStore: GuardianRuntimeStore,
    private val wakeWordDetector: GuardianWakeWordDetector,
) : ViewModel() {
    val status: StateFlow<GuardianStatus> = runtimeStore.status
    val wakeReadiness: GuardianWakeReadiness
        get() = wakeWordDetector.readiness()

    fun onPermissionDenied() {
        runtimeStore.dispatch(GuardianEvent.PermissionRevoked)
    }

    fun onWakeUnavailable() {
        runtimeStore.dispatch(GuardianEvent.Failed(wakeReadiness.userMessage))
    }

    fun reset() {
        runtimeStore.dispatch(GuardianEvent.DisableRequested)
    }
}
