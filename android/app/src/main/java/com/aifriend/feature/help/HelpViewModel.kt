package com.aifriend.feature.help

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aifriend.core.voice.OfflineSpeechPort
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 离线帮助页播报状态。 */
data class HelpUiState(
    val isPlaying: Boolean = false,
    val errorMessage: String? = null,
)

/** 只播放固定本机指导文字，不请求网络或生成新内容。 */
@HiltViewModel
class HelpViewModel @Inject constructor(
    private val offlineSpeechPort: OfflineSpeechPort,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(HelpUiState())
    private var playbackJob: Job? = null

    val uiState: StateFlow<HelpUiState> = mutableUiState.asStateFlow()

    fun playGuidance() {
        if (playbackJob?.isActive == true) return
        mutableUiState.value = HelpUiState(isPlaying = true)
        playbackJob = viewModelScope.launch {
            val success = runCatching {
                offlineSpeechPort.speak(GUIDANCE_TEXT)
            }.getOrDefault(false)
            mutableUiState.value = if (success) {
                HelpUiState()
            } else {
                HelpUiState(
                    errorMessage = "离线播报不可用，请阅读屏幕上的指导文字。",
                )
            }
            playbackJob = null
        }
    }

    fun dismissError() {
        mutableUiState.value = mutableUiState.value.copy(errorMessage = null)
    }

    fun leave() {
        playbackJob?.cancel()
        playbackJob = null
        mutableUiState.value = HelpUiState()
    }

    private companion object {
        const val GUIDANCE_TEXT =
            "需要权限或省电设置时，请您或家人亲自打开系统设置。" +
                "家人可以帮助绑定联系人和检查权限，但每次联系仍需要您听完复述并确认。" +
                "联系失败时不会自动补发，请返回首页重新说一次。"
    }
}
