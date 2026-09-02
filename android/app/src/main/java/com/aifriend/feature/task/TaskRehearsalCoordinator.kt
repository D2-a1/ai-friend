package com.aifriend.feature.task

import com.aifriend.contract.model.AudioRange
import com.aifriend.contract.model.Intent
import com.aifriend.core.audio.AudioPlaybackPort
import com.aifriend.core.audio.CapturedAudio
import com.aifriend.core.audio.WavPcmCodec
import com.aifriend.core.voice.OfflineSpeechPort
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
            if (!offlineSpeechPort.speak(spokenSummary)) {
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
        if (!offlineSpeechPort.speak(spokenSummary)) {
            throw TaskRehearsalException("当前设备没有可用的离线中文语音，不能确认")
        }
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
