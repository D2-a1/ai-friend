package com.aifriend.core.voice

import com.aifriend.core.audio.WavPcmCodec
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 只从两遍短语音生成发音内容模板的本机端口。
 *
 * 本端口不输出说话人身份、不做声纹认证；声学参数不可用时必须失败关闭。
 *
 * @author codex
 * @since 2026-08-13
 */
interface LocalVoiceTemplateEngine {
    fun enroll(firstWav: ByteArray, secondWav: ByteArray): LocalVoiceTemplateCandidate

    /**
     * 使用与服务端相同的双录模板距离，确认新类别与已录类别达到可靠区分边界。
     */
    fun isMutuallyDistinct(
        candidate: LocalVoiceTemplateCandidate,
        existing: Collection<LocalVoiceTemplateCandidate>,
    ): Boolean = false

    /**
     * 在当前受控声学参数边界内将一段当前录音与多个个人内容模板分类。
     *
     * 返回空表示没有唯一可靠命中；不得将空结果降级为关键词确认。
     */
    fun classify(
        sampleWav: ByteArray,
        templates: Map<String, LocalVoiceTemplateCandidate>,
    ): String?
}

/** 仅在当前注册调用内存中短暂存在的未加密内容模板。 */
class LocalVoiceTemplateCandidate(
    val dialectCode: String,
    val dialectPackageVersion: String,
    val modelVersion: String,
    val thresholdVersion: String,
    val material: ByteArray,
) {
    fun clear() = material.fill(0)
}

/** 方言包或音频不能安全生成内容模板。 */
class LocalVoiceTemplateException(message: String) : IllegalStateException(message)

/** 与后端 MFCC_DTW_V1 采用相同模板格式和双录一致性边界的本机实现。 */
@Singleton
class MfccDtwLocalVoiceTemplateEngine @Inject constructor(
    private val dialectPackageRegistry: DialectPackageRegistry,
) : LocalVoiceTemplateEngine {

    override fun enroll(firstWav: ByteArray, secondWav: ByteArray): LocalVoiceTemplateCandidate {
        val dialectPackage = dialectPackageRegistry.activePackage()
            ?: throw LocalVoiceTemplateException("当前语音模板参数不可用，请稍后重新录制")
        val calibration = dialectPackage.calibration
        val first = extract(firstWav, calibration)
        val second = extract(secondWav, calibration)
        if (DynamicTimeWarping.distance(first, second, calibration.dtwWindowRatio) >
            calibration.enrollmentConsistencyMaxDistance
        ) {
            throw LocalVoiceTemplateException("两遍发音不一致，请重新录制")
        }
        val manifest = dialectPackage.manifest
        return LocalVoiceTemplateCandidate(
            dialectCode = manifest.dialectCode,
            dialectPackageVersion = manifest.packageVersion,
            modelVersion = manifest.acousticModelVersion,
            thresholdVersion = manifest.thresholdVersion,
            material = AcousticTemplateCodec.encode(first, second),
        )
    }

    override fun isMutuallyDistinct(
        candidate: LocalVoiceTemplateCandidate,
        existing: Collection<LocalVoiceTemplateCandidate>,
    ): Boolean {
        val dialectPackage = dialectPackageRegistry.activePackage()
            ?: throw LocalVoiceTemplateException("当前语音模板参数不可用，请稍后重新录制")
        val manifest = dialectPackage.manifest
        val templates = listOf(candidate) + existing
        if (templates.any { template ->
                template.dialectCode != manifest.dialectCode ||
                    template.dialectPackageVersion != manifest.packageVersion ||
                    template.modelVersion != manifest.acousticModelVersion ||
                    template.thresholdVersion != manifest.thresholdVersion
            }
        ) {
            throw LocalVoiceTemplateException("本机安全指令模板版本不兼容，请重新录制")
        }
        if (existing.isEmpty()) return true
        val candidateSequences = decodeTemplate(candidate)
        val candidateBaseline = internalPairDistance(
            candidateSequences,
            dialectPackage.calibration.dtwWindowRatio,
        )
        return existing.all { other ->
            val otherSequences = decodeTemplate(other)
            val otherBaseline = internalPairDistance(
                otherSequences,
                dialectPackage.calibration.dtwWindowRatio,
            )
            val crossDistance = minimumPairDistance(
                candidateSequences,
                otherSequences,
                dialectPackage.calibration.dtwWindowRatio,
            )
            crossDistance - maxOf(candidateBaseline, otherBaseline) >=
                dialectPackage.calibration.taskAliasMinimumMargin
        }
    }

    override fun classify(
        sampleWav: ByteArray,
        templates: Map<String, LocalVoiceTemplateCandidate>,
    ): String? {
        if (templates.isEmpty()) return null
        val dialectPackage = dialectPackageRegistry.activePackage()
            ?: throw LocalVoiceTemplateException("当前语音模板参数不可用，请重新录制安全指令")
        val manifest = dialectPackage.manifest
        if (templates.values.any { candidate ->
                candidate.dialectCode != manifest.dialectCode ||
                    candidate.dialectPackageVersion != manifest.packageVersion ||
                    candidate.modelVersion != manifest.acousticModelVersion ||
                    candidate.thresholdVersion != manifest.thresholdVersion
            }
        ) {
            throw LocalVoiceTemplateException("本机安全指令模板版本不兼容，请重新录制")
        }
        val sample = extract(sampleWav, dialectPackage.calibration)
        val distances = templates.map { (templateId, candidate) ->
            val enrolled = AcousticTemplateCodec.decode(candidate.material)
            val distance = enrolled.minOf { sequence ->
                DynamicTimeWarping.distance(
                    sample,
                    sequence,
                    dialectPackage.calibration.dtwWindowRatio,
                )
            }
            templateId to distance
        }.sortedBy { it.second }
        val best = distances.first()
        if (best.second > dialectPackage.calibration.enrollmentConsistencyMaxDistance) return null
        val next = distances.getOrNull(1)
        if (next != null &&
            next.second - best.second < dialectPackage.calibration.taskAliasMinimumMargin
        ) {
            return null
        }
        return best.first
    }

    private fun decodeTemplate(
        candidate: LocalVoiceTemplateCandidate,
    ): List<Array<FloatArray>> = runCatching {
        AcousticTemplateCodec.decode(candidate.material)
    }.getOrElse {
        throw LocalVoiceTemplateException("本机安全指令模板无效，请重新录制")
    }

    private fun minimumPairDistance(
        left: List<Array<FloatArray>>,
        right: List<Array<FloatArray>>,
        windowRatio: Double,
    ): Double = left.minOf { leftSequence ->
        right.minOf { rightSequence ->
            DynamicTimeWarping.distance(leftSequence, rightSequence, windowRatio)
        }
    }

    private fun internalPairDistance(
        template: List<Array<FloatArray>>,
        windowRatio: Double,
    ): Double {
        if (template.size != 2) {
            throw LocalVoiceTemplateException("本机安全指令模板无效，请重新录制")
        }
        return DynamicTimeWarping.distance(template[0], template[1], windowRatio)
    }

    private fun extract(
        wavBytes: ByteArray,
        calibration: DialectAcousticCalibration,
    ): Array<FloatArray> {
        val decoded = runCatching { WavPcmCodec.decodeMono16(wavBytes) }
            .getOrElse { throw LocalVoiceTemplateException("录音格式无效，请重新录制") }
        if (decoded.durationMs !in calibration.minimumDurationMs..calibration.maximumDurationMs ||
            decoded.sampleRate != calibration.sampleRateHz
        ) {
            decoded.samples.fill(0)
            throw LocalVoiceTemplateException("录音时长无效，请重新录制")
        }
        var clipped = 0
        var sum = 0.0
        val normalized = DoubleArray(decoded.samples.size) { index ->
            val value = decoded.samples[index].toInt()
            if (abs(value) >= 32_760) clipped++
            (value / 32_768.0).also { sum += it }
        }
        decoded.samples.fill(0)
        val mean = sum / normalized.size
        normalized.indices.forEach { normalized[it] -= mean }
        if (clipped / normalized.size.toDouble() > calibration.maximumClippedSampleRatio) {
            normalized.fill(0.0)
            throw LocalVoiceTemplateException("录音削波过多，请重新录制")
        }
        return try {
            MfccFeatureExtractor.extract(normalized, calibration)
        } catch (exception: IllegalArgumentException) {
            throw LocalVoiceTemplateException("录音有效语音不足，请重新录制")
        } finally {
            normalized.fill(0.0)
        }
    }
}

private object AcousticTemplateCodec {
    private const val MAGIC = 0x41494654
    private const val SCHEMA_VERSION: Short = 1
    private const val MAX_TEMPLATE_BYTES = 262_144

    fun encode(first: Array<FloatArray>, second: Array<FloatArray>): ByteArray {
        val dimensions = validate(first, -1)
        validate(second, dimensions)
        val byteCount = 4 + 2 + 2 + 1 +
            4 + first.size * dimensions * 4 +
            4 + second.size * dimensions * 4
        require(byteCount <= MAX_TEMPLATE_BYTES) { "TEMPLATE_SIZE_INVALID" }
        return ByteBuffer.allocate(byteCount).apply {
            putInt(MAGIC)
            putShort(SCHEMA_VERSION)
            putShort(dimensions.toShort())
            put(2)
            listOf(first, second).forEach { sequence ->
                putInt(sequence.size)
                sequence.forEach { frame -> frame.forEach(::putFloat) }
            }
        }.array()
    }

    fun decode(material: ByteArray): List<Array<FloatArray>> {
        require(material.size in 17..MAX_TEMPLATE_BYTES) { "TEMPLATE_SIZE_INVALID" }
        val buffer = ByteBuffer.wrap(material)
        require(buffer.int == MAGIC && buffer.short == SCHEMA_VERSION) { "TEMPLATE_HEADER_INVALID" }
        val dimensions = buffer.short.toInt()
        val sequenceCount = buffer.get().toInt()
        require(dimensions in 1..20 && sequenceCount == 2) { "TEMPLATE_SHAPE_INVALID" }
        val sequences = List(sequenceCount) {
            val frameCount = buffer.int
            require(frameCount in 1..1_200) { "TEMPLATE_SHAPE_INVALID" }
            Array(frameCount) {
                FloatArray(dimensions) {
                    buffer.float.also { value -> require(value.isFinite()) }
                }
            }
        }
        require(!buffer.hasRemaining()) { "TEMPLATE_TRAILING_BYTES" }
        return sequences
    }

    private fun validate(sequence: Array<FloatArray>, expectedDimensions: Int): Int {
        require(sequence.isNotEmpty() && sequence.size <= 1_200) { "TEMPLATE_SHAPE_INVALID" }
        val dimensions = sequence.first().size
        require(dimensions in 1..20 && (expectedDimensions < 0 || dimensions == expectedDimensions)) {
            "TEMPLATE_SHAPE_INVALID"
        }
        require(sequence.all { frame ->
            frame.size == dimensions && frame.all(Float::isFinite)
        }) { "TEMPLATE_VALUE_INVALID" }
        return dimensions
    }
}

private object DynamicTimeWarping {
    fun distance(left: Array<FloatArray>, right: Array<FloatArray>, windowRatio: Double): Double {
        require(left.isNotEmpty() && right.isNotEmpty() && left[0].size == right[0].size)
        val window = max(abs(left.size - right.size), ceil(max(left.size, right.size) * windowRatio).toInt())
        var previousCosts = DoubleArray(right.size + 1) { Double.POSITIVE_INFINITY }
        var currentCosts = DoubleArray(right.size + 1)
        var previousSteps = IntArray(right.size + 1)
        var currentSteps = IntArray(right.size + 1)
        previousCosts[0] = 0.0
        for (leftIndex in 1..left.size) {
            currentCosts.fill(Double.POSITIVE_INFINITY)
            currentSteps.fill(0)
            for (rightIndex in max(1, leftIndex - window)..min(right.size, leftIndex + window)) {
                val predecessors = listOf(
                    previousCosts[rightIndex] to previousSteps[rightIndex],
                    currentCosts[rightIndex - 1] to currentSteps[rightIndex - 1],
                    previousCosts[rightIndex - 1] to previousSteps[rightIndex - 1],
                )
                val predecessor = predecessors.minBy { it.first }
                if (predecessor.first.isFinite()) {
                    currentCosts[rightIndex] = predecessor.first + frameDistance(
                        left[leftIndex - 1],
                        right[rightIndex - 1],
                    )
                    currentSteps[rightIndex] = predecessor.second + 1
                }
            }
            previousCosts = currentCosts.also { currentCosts = previousCosts }
            previousSteps = currentSteps.also { currentSteps = previousSteps }
        }
        require(previousCosts[right.size].isFinite() && previousSteps[right.size] > 0)
        return previousCosts[right.size] / previousSteps[right.size]
    }

    private fun frameDistance(left: FloatArray, right: FloatArray): Double {
        var squared = 0.0
        left.indices.forEach { index ->
            val difference = left[index] - right[index]
            squared += difference * difference
        }
        return sqrt(squared / left.size)
    }
}

private object MfccFeatureExtractor {
    private const val PRE_EMPHASIS = 0.97
    private const val MINIMUM_POWER = 1.0E-12

    fun extract(
        samples: DoubleArray,
        calibration: DialectAcousticCalibration,
    ): Array<FloatArray> {
        val frameLength = calibration.sampleRateHz * calibration.frameLengthMs / 1_000
        val frameShift = calibration.sampleRateHz * calibration.frameShiftMs / 1_000
        require(frameLength >= 2 && frameShift >= 1 && samples.size >= frameLength)
        val frameCount = 1 + (samples.size - frameLength) / frameShift
        val energies = DoubleArray(frameCount) { frame ->
            var energy = 0.0
            repeat(frameLength) { offset ->
                val value = samples[frame * frameShift + offset]
                energy += value * value
            }
            10.0 * log10(max(energy / frameLength, MINIMUM_POWER))
        }
        val peak = energies.maxOrNull() ?: -120.0
        require(peak.isFinite() && peak >= calibration.minimumPeakDbfs)
        val activeFloor = peak - calibration.vadRelativeFloorDb
        val active = energies.indices.filter { energies[it] >= activeFloor }
        require(active.isNotEmpty() && active.size / frameCount.toDouble() >= calibration.minimumActiveFrameRatio)
        val firstActive = active.first()
        val lastActive = active.last()
        val fftSize = nextPowerOfTwo(frameLength)
        val melWeights = melWeights(calibration.sampleRateHz, fftSize, calibration.melFilterCount)
        val window = DoubleArray(frameLength) { index ->
            0.54 - 0.46 * cos(2.0 * PI * index / (frameLength - 1.0))
        }
        val features = Array(lastActive - firstActive + 1) { activeOffset ->
            frame(
                samples,
                (firstActive + activeOffset) * frameShift,
                frameLength,
                fftSize,
                window,
                melWeights,
                calibration.coefficientCount,
            )
        }
        normalize(features)
        return features
    }

    private fun frame(
        samples: DoubleArray,
        sampleOffset: Int,
        frameLength: Int,
        fftSize: Int,
        window: DoubleArray,
        melWeights: Array<DoubleArray>,
        coefficientCount: Int,
    ): FloatArray {
        val real = DoubleArray(fftSize)
        val imaginary = DoubleArray(fftSize)
        repeat(frameLength) { index ->
            val current = samples[sampleOffset + index]
            val previous = if (index == 0) samples[max(0, sampleOffset - 1)] else samples[sampleOffset + index - 1]
            real[index] = (current - PRE_EMPHASIS * previous) * window[index]
        }
        fft(real, imaginary)
        val power = DoubleArray(fftSize / 2 + 1) { index ->
            (real[index] * real[index] + imaginary[index] * imaginary[index]) / fftSize
        }
        val logMel = DoubleArray(melWeights.size) { filter ->
            var energy = 0.0
            power.indices.forEach { bin -> energy += power[bin] * melWeights[filter][bin] }
            ln(max(energy, MINIMUM_POWER))
        }
        return FloatArray(coefficientCount) { coefficient ->
            var value = 0.0
            logMel.indices.forEach { filter ->
                value += logMel[filter] * cos(PI * (coefficient + 1) * (filter + 0.5) / logMel.size)
            }
            value.toFloat()
        }
    }

    private fun melWeights(sampleRate: Int, fftSize: Int, filterCount: Int): Array<DoubleArray> {
        val spectrumSize = fftSize / 2 + 1
        val minimumMel = hzToMel(50.0)
        val maximumMel = hzToMel(sampleRate / 2.0)
        val bins = IntArray(filterCount + 2) { index ->
            val mel = minimumMel + (maximumMel - minimumMel) * index / (filterCount + 1.0)
            floor((fftSize + 1.0) * melToHz(mel) / sampleRate).toInt().coerceIn(0, spectrumSize - 1)
        }
        return Array(filterCount) { filter ->
            val left = bins[filter]
            val center = max(left + 1, bins[filter + 1]).coerceAtMost(spectrumSize - 1)
            val right = max(center + 1, bins[filter + 2]).coerceAtMost(spectrumSize - 1)
            DoubleArray(spectrumSize).also { weights ->
                for (bin in left until center) weights[bin] = (bin - left) / max(1.0, (center - left).toDouble())
                for (bin in center..right) weights[bin] = (right - bin) / max(1.0, (right - center).toDouble())
            }
        }
    }

    private fun normalize(features: Array<FloatArray>) {
        for (dimension in features.first().indices) {
            val mean = features.sumOf { it[dimension].toDouble() } / features.size
            val variance = features.sumOf { frame ->
                val centered = frame[dimension] - mean
                centered * centered
            } / max(1, features.size - 1)
            val standardDeviation = sqrt(variance)
            features.forEach { frame ->
                frame[dimension] = if (standardDeviation < 1.0E-6) 0F else ((frame[dimension] - mean) / standardDeviation).toFloat()
            }
        }
    }

    private fun nextPowerOfTwo(value: Int): Int {
        var power = 1
        while (power < value) power = power shl 1
        require(power <= 2_048)
        return power
    }

    private fun fft(real: DoubleArray, imaginary: DoubleArray) {
        var target = 0
        for (source in 1 until real.size) {
            var bit = real.size shr 1
            while (target and bit != 0) {
                target = target xor bit
                bit = bit shr 1
            }
            target = target xor bit
            if (source < target) {
                real[source] = real[target].also { real[target] = real[source] }
                imaginary[source] = imaginary[target].also { imaginary[target] = imaginary[source] }
            }
        }
        var blockLength = 2
        while (blockLength <= real.size) {
            val angle = -2.0 * PI / blockLength
            val rootReal = cos(angle)
            val rootImaginary = kotlin.math.sin(angle)
            for (blockStart in real.indices step blockLength) {
                var factorReal = 1.0
                var factorImaginary = 0.0
                for (offset in 0 until blockLength / 2) {
                    val even = blockStart + offset
                    val odd = even + blockLength / 2
                    val oddReal = real[odd] * factorReal - imaginary[odd] * factorImaginary
                    val oddImaginary = real[odd] * factorImaginary + imaginary[odd] * factorReal
                    real[odd] = real[even] - oddReal
                    imaginary[odd] = imaginary[even] - oddImaginary
                    real[even] += oddReal
                    imaginary[even] += oddImaginary
                    val nextFactorReal = factorReal * rootReal - factorImaginary * rootImaginary
                    factorImaginary = factorReal * rootImaginary + factorImaginary * rootReal
                    factorReal = nextFactorReal
                }
            }
            blockLength = blockLength shl 1
        }
    }

    private fun hzToMel(frequency: Double) = 2_595.0 * log10(1.0 + frequency / 700.0)
    private fun melToHz(mel: Double) = 700.0 * (10.0.pow(mel / 2_595.0) - 1.0)
}
