package com.aifriend.feature.knowledge

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import androidx.core.content.ContextCompat
import com.aifriend.core.audio.AndroidAudioCaptureAdapter
import com.aifriend.core.voice.OfflineSpeechPort
import com.aifriend.feature.guardian.GuardianQuestionAudioCoordinator
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope

/** 无构造副作用；生产实现必须返回真实资源交接，测试显式提供替身。 */
abstract class KnowledgeVoiceFactory {
    abstract fun unavailable(): KnowledgeVoiceFailure?
    internal abstract fun create(scope: CoroutineScope, isCurrent: () -> Boolean,
        submit: (String) -> KnowledgeVoiceSubmission, cancel: () -> Unit): KnowledgeVoiceFlow
}

class AndroidKnowledgeVoiceFactory @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val coordinator: GuardianQuestionAudioCoordinator,
    private val capture: AndroidAudioCaptureAdapter,
    private val speech: Provider<OfflineSpeechPort>,
    private val recognizer: QuestionSpeechRecognizer,
) : KnowledgeVoiceFactory() {
    override fun unavailable(): KnowledgeVoiceFailure? = try {
        when {
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> KnowledgeVoiceFailure.MICROPHONE_DENIED
            context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked != false -> KnowledgeVoiceFailure.AUDIO_BUSY
            context.getSystemService(AudioManager::class.java)?.mode != AudioManager.MODE_NORMAL -> KnowledgeVoiceFailure.AUDIO_BUSY
            else -> null
        }
    } catch (_: Exception) { KnowledgeVoiceFailure.AUDIO_BUSY }

    override fun create(scope: CoroutineScope, isCurrent: () -> Boolean,
        submit: (String) -> KnowledgeVoiceSubmission, cancel: () -> Unit) = KnowledgeVoiceFlow(
        scope, KnowledgeGuardianAudioGate(coordinator), capture::newOwnedCapture, speech::get,
        recognizer, { isCurrent() && unavailable() == null }, submit, cancel,
    )
}
