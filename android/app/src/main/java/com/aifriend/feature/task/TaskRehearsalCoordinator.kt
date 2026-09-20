package com.aifriend.feature.task

import com.aifriend.contract.model.AudioRange
import com.aifriend.contract.model.Intent
import com.aifriend.core.audio.AudioPlaybackPort
import com.aifriend.core.audio.CapturedAudio
import com.aifriend.core.audio.WavPcmCodec
import com.aifriend.core.voice.OfflineSpeechPort
import com.aifriend.feature.personalization.DialogueStyleChoice
import com.aifriend.feature.personalization.DisabledPersonalMemoryRepository
import com.aifriend.feature.personalization.PersonalMemoryRepository
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/** 按服务端已证明的单个连续毫秒范围切出项目内部标准 WAV。 */
class TaskEffectiveAudioClipper @Inject constructor() {

    fun clip(source: CapturedAudio, range: AudioRange): ByteArray {
        val decoded = runCatching { WavPcmCodec.decodeMono16(source.wavBytes) }
            .getOrElse { exception ->
                throw TaskRehearsalException("任务原声无法安全解码", exception)
            }
        try {
            val valid = decoded.durationMs == source.durationMs &&
                range.startMs >= 0 && range.endMs > range.startMs &&
                range.endMs <= decoded.durationMs
            if (!valid) throw TaskRehearsalException("任务原声范围无效，不能确认")
            val startSample = range.startMs.toLong() * decoded.sampleRate / 1_000L
            val endSample = range.endMs.toLong() * decoded.sampleRate / 1_000L
            if (startSample < 0L || endSample <= startSample || endSample > decoded.samples.size) {
                throw TaskRehearsalException("任务原声范围无效，不能确认")
            }
            val pcmBytes = ByteArray((endSample - startSample).toInt() * Short.SIZE_BYTES)
            return try {
                val buffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
                for (index in startSample.toInt() until endSample.toInt()) {
                    buffer.putShort(decoded.samples[index])
                }
                WavPcmCodec.encodeMono16(pcmBytes, decoded.sampleRate)
            } finally {
                pcmBytes.fill(0)
            }
        } finally {
            decoded.samples.fill(0)
        }
    }
}

/**
 * 最终有效原声与离线普通话摘要的顺序复述器。
 *
 * 消息只接受一个连续原声范围；通话不得夹带消息原声。任一步失败都不产生确认资格。
 */
@Singleton
class TaskRehearsalCoordinator @Inject constructor(
    private val audioPlaybackPort: AudioPlaybackPort,
    private val offlineSpeechPort: OfflineSpeechPort,
    private val clipper: TaskEffectiveAudioClipper,
    private val personalMemoryRepository: PersonalMemoryRepository =
        DisabledPersonalMemoryRepository,
) {

    suspend fun rehearse(
        source: CapturedAudio?,
        intent: Intent,
        ranges: List<AudioRange>,
        spokenSummary: String,
    ): CapturedAudio? {
        if (spokenSummary.isBlank() || spokenSummary.length > MAXIMUM_SUMMARY_LENGTH) {
            throw TaskRehearsalException("完整复述摘要无效，不能确认")
        }
        if (!offlineSpeechPort.prepare()) {
            throw TaskRehearsalException("当前设备没有可用的离线中文语音，不能确认")
        }
        var clip: ByteArray? = null
        var retainedMessageAudio: CapturedAudio? = null
        try {
            when (intent) {
                Intent.SEND_MESSAGE -> {
                    if (source == null || ranges.size != 1) {
                        throw TaskRehearsalException("消息原声范围不唯一，不能确认")
                    }
                    clip = clipper.clip(source, ranges.single())
                    audioPlaybackPort.play(clip)
                }
                Intent.VOICE_CALL, Intent.VIDEO_CALL -> {
                    if (ranges.isNotEmpty()) {
                        throw TaskRehearsalException("通话任务夹带了消息原声，不能确认")
                    }
                }
                else -> throw TaskRehearsalException("当前任务动作不支持完整复述")
            }
            val dialogueStyle = personalMemoryRepository.currentPromptChoices().dialogueStyle
            if (!offlineSpeechPort.speak(confirmationPrompt(spokenSummary, dialogueStyle))) {
                throw TaskRehearsalException("当前设备没有可用的离线中文语音，不能确认")
            }
            if (intent == Intent.SEND_MESSAGE) {
                val retainedBytes = checkNotNull(clip)
                retainedMessageAudio = CapturedAudio(
                    retainedBytes,
                    ranges.single().endMs - ranges.single().startMs,
                )
                clip = null
            }
            return retainedMessageAudio
        } finally {
            clip?.fill(0)
        }
    }

    /**
     * 使用已保留的最终消息原声和同一摘要重新播放，不重新裁剪或改变待执行内容。
     *
     * @param retainedMessageAudio 首次完整复述后保留的唯一消息原声；通话时必须为空
     * @param intent 当前已冻结任务意图
     * @param spokenSummary 当前已冻结完整复述
     */
    suspend fun replay(
        retainedMessageAudio: CapturedAudio?,
        intent: Intent,
        spokenSummary: String,
    ) {
        if (spokenSummary.isBlank() || spokenSummary.length > MAXIMUM_SUMMARY_LENGTH) {
            throw TaskRehearsalException("完整复述摘要无效，不能确认")
        }
        if (!offlineSpeechPort.prepare()) {
            throw TaskRehearsalException("当前设备没有可用的离线中文语音，不能确认")
        }
        when (intent) {
            Intent.SEND_MESSAGE -> {
                val messageAudio = retainedMessageAudio
                    ?: throw TaskRehearsalException("已保留的消息原声不可用，不能确认")
                audioPlaybackPort.play(messageAudio.wavBytes)
            }
            Intent.VOICE_CALL, Intent.VIDEO_CALL -> {
                if (retainedMessageAudio != null) {
                    throw TaskRehearsalException("通话任务夹带了消息原声，不能确认")
                }
            }
            else -> throw TaskRehearsalException("当前任务动作不支持完整复述")
        }
        val dialogueStyle = personalMemoryRepository.currentPromptChoices().dialogueStyle
        if (!offlineSpeechPort.speak(confirmationPrompt(spokenSummary, dialogueStyle))) {
            throw TaskRehearsalException("当前设备没有可用的离线中文语音，不能确认")
        }
    }

    /** 播报有限联系人候选；播报结束后调用方立即开启麦克风监听。 */
    suspend fun promptCandidateSelection(labels: List<String>, retry: Boolean = false): Boolean {
        val safeLabels = labels.map(String::trim).filter(String::isNotBlank).take(3)
        if (safeLabels.isEmpty() || !offlineSpeechPort.prepare()) return false
        val choices = safeLabels.mapIndexed { index, label ->
            "第" + (index + 1) + "个，" + label
        }.joinToString("；")
        val style = personalMemoryRepository.currentPromptChoices().dialogueStyle
        val prefix = when {
            retry -> "刚才没有听清。"
            style == DialogueStyleChoice.BRIEF -> "可能的亲友有："
            else -> "我找到多个可能的亲友。"
        }
        val ending = if (style == DialogueStyleChoice.BRIEF) {
            "。请说称呼或第几个；也可说重听或取消"
        } else {
            "。请直接说称呼或第几个；也可以说再听一遍或取消"
        }
        return offlineSpeechPort.speak(prefix + choices + ending)
    }

    /** 在同一唤醒会话内提示完整重说或定向补充，播报后由调用方立即监听。 */
    suspend fun promptTaskRevision(
        contentOnly: Boolean,
        correction: Boolean,
        allowAmbiguousCallHint: Boolean = false,
    ): Boolean {
        if (!offlineSpeechPort.prepare()) return false
        val choices = personalMemoryRepository.currentPromptChoices()
        val callHint = if (allowAmbiguousCallHint) {
            "如果要通话，请说打电话或视频通话。"
        } else {
            ""
        }
        val prompt = when {
            contentOnly && choices.dialogueStyle == DialogueStyleChoice.BRIEF ->
                "联系人和动作已保留。请只说消息内容"
            contentOnly -> "联系人和动作已经保留。请只重新说要发送的消息内容"
            correction && choices.dialogueStyle == DialogueStyleChoice.BRIEF ->
                "请直接说哪里不对，例如，不对，改成视频通话"
            correction ->
                "我会保留刚才的任务。请直接说哪里不对，例如，不对，是视频通话，或者，联系人改成二女儿"
            choices.dialogueStyle == DialogueStyleChoice.BRIEF ->
                "这句话还不能确定完整需求。${callHint}请重新说联系谁、做什么，不用再唤醒"
            else ->
                "这句话还不能确定完整需求。${callHint}请重新说要联系谁和要做什么，不需要再次呼唤小友"
        }
        return offlineSpeechPort.speak(prompt)
    }
    /** 识别不明确时先停止输出，再提示并重新进入监听。 */
    suspend fun promptConfirmationRetry(): Boolean {
        if (!offlineSpeechPort.prepare()) return false
        val style = personalMemoryRepository.currentPromptChoices().dialogueStyle
        val prompt = if (style == DialogueStyleChoice.BRIEF) {
            "没听清。请说确认、否认，或直接纠正"
        } else {
            "没有听清。请说确认、否认，或者直接说哪里不对"
        }
        return offlineSpeechPort.speak(prompt)
    }

    private fun confirmationPrompt(
        spokenSummary: String,
        style: DialogueStyleChoice,
    ): String = if (style == DialogueStyleChoice.BRIEF) {
        "$spokenSummary。请说确认、否认；不对请直接纠正"
    } else {
        "$spokenSummary。请说确认、否认；如果不对，请直接说要改的内容"
    }
    suspend fun stop() {
        runCatching { audioPlaybackPort.stop() }
        offlineSpeechPort.close()
    }

    fun close() {
        audioPlaybackPort.stopImmediately()
        offlineSpeechPort.close()
    }

    private companion object {
        const val MAXIMUM_SUMMARY_LENGTH = 500
    }
}

/** 完整复述无法安全完成。 */
class TaskRehearsalException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
