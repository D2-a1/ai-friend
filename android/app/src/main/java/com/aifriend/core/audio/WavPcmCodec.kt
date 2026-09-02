package com.aifriend.core.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 仅处理项目内部标准 PCM WAV 的编解码器。
 *
 * @author codex
 * @since 2026-08-12
 */
object WavPcmCodec {
    const val SAMPLE_RATE = 16_000
    const val CHANNEL_COUNT = 1
    const val BITS_PER_SAMPLE = 16
    const val HEADER_SIZE = 44

    /**
     * 将小端序 16 位单声道 PCM 封装为 WAV。
     *
     * @param pcmBytes PCM 字节，长度必须为偶数
     * @param sampleRate 采样率
     * @return 标准 WAV 字节
     */
    fun encodeMono16(
        pcmBytes: ByteArray,
        sampleRate: Int = SAMPLE_RATE,
    ): ByteArray {
        require(pcmBytes.isNotEmpty() && pcmBytes.size % 2 == 0) { "PCM 数据无效" }
        require(sampleRate in 8_000..48_000) { "PCM 采样率无效" }
        val byteRate = sampleRate * CHANNEL_COUNT * BITS_PER_SAMPLE / 8
        val output = ByteBuffer.allocate(HEADER_SIZE + pcmBytes.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        output.put("RIFF".toByteArray(Charsets.US_ASCII))
        output.putInt(36 + pcmBytes.size)
        output.put("WAVE".toByteArray(Charsets.US_ASCII))
        output.put("fmt ".toByteArray(Charsets.US_ASCII))
        output.putInt(16)
        output.putShort(1)
        output.putShort(CHANNEL_COUNT.toShort())
        output.putInt(sampleRate)
        output.putInt(byteRate)
        output.putShort((CHANNEL_COUNT * BITS_PER_SAMPLE / 8).toShort())
        output.putShort(BITS_PER_SAMPLE.toShort())
        output.put("data".toByteArray(Charsets.US_ASCII))
        output.putInt(pcmBytes.size)
        output.put(pcmBytes)
        return output.array()
    }

    /**
     * 解码项目内部产生的固定格式 WAV。异常文件失败关闭，不尝试容错拼接。
     *
     * @param wavBytes WAV 字节
     * @return PCM 解码结果
     */
    fun decodeMono16(wavBytes: ByteArray): Pcm16Audio {
        require(wavBytes.size >= HEADER_SIZE) { "WAV 文件过短" }
        require(wavBytes.asAscii(0, 4) == "RIFF") { "WAV RIFF 头无效" }
        require(wavBytes.asAscii(8, 4) == "WAVE") { "WAV 类型无效" }
        require(wavBytes.asAscii(12, 4) == "fmt ") { "WAV fmt 块无效" }
        require(wavBytes.asAscii(36, 4) == "data") { "WAV data 块无效" }
        val header = ByteBuffer.wrap(wavBytes).order(ByteOrder.LITTLE_ENDIAN)
        require(header.getInt(16) == 16) { "WAV fmt 长度不受支持" }
        require(header.getShort(20).toInt() == 1) { "WAV 必须为 PCM" }
        require(header.getShort(22).toInt() == CHANNEL_COUNT) { "WAV 必须为单声道" }
        val sampleRate = header.getInt(24)
        require(sampleRate == SAMPLE_RATE) { "WAV 采样率不受支持" }
        require(header.getShort(34).toInt() == BITS_PER_SAMPLE) { "WAV 必须为 16 位" }
        val declaredDataSize = header.getInt(40)
        require(declaredDataSize > 0 && declaredDataSize % 2 == 0) { "WAV PCM 长度无效" }
        require(HEADER_SIZE + declaredDataSize == wavBytes.size) { "WAV 声明长度与文件不一致" }
        val pcmBytes = wavBytes.copyOfRange(HEADER_SIZE, wavBytes.size)
        val samples = ShortArray(pcmBytes.size / 2)
        ByteBuffer.wrap(pcmBytes)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(samples)
        return Pcm16Audio(
            samples = samples,
            sampleRate = sampleRate,
        )
    }

    private fun ByteArray.asAscii(offset: Int, length: Int): String =
        String(this, offset, length, Charsets.US_ASCII)
}

/**
 * 解码后的 16 位单声道 PCM。
 *
 * @property samples 音频样本
 * @property sampleRate 采样率
 */
data class Pcm16Audio(
    val samples: ShortArray,
    val sampleRate: Int,
) {
    val durationMs: Int
        get() = (samples.size.toLong() * 1_000L / sampleRate).toInt()
}
