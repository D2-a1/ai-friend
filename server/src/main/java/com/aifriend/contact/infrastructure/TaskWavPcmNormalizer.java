package com.aifriend.contact.infrastructure;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

import com.aifriend.dialect.application.DialectAcousticCalibration;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.voice.application.ValidatedAudioObject;

/**
 * 将已校验 TASK WAV 有界解码为本地称呼定位所需 PCM。
 *
 * <p>仅支持最长六十秒、16 kHz 单声道短期内存数据；不落盘、不持久化。
 *
 * @author Codex
 * @since 1.0.0
 */
final class TaskWavPcmNormalizer {

    private static final int BYTES_PER_SAMPLE = 2;
    private static final int MAXIMUM_DURATION_MS = 60_000;
    private static final int DURATION_TOLERANCE_MS = 100;
    private static final int CLIPPED_ABSOLUTE_VALUE = 32_760;

    PcmAudio normalize(
            ValidatedAudioObject audio,
            DialectAcousticCalibration calibration) {
        if (audio == null || !"audio/wav".equals(audio.mediaType())
                || audio.actualDurationMs() < calibration.minimumDurationMs()
                || audio.actualDurationMs() > MAXIMUM_DURATION_MS) {
            throw new BusinessException(ErrorCode.AUDIO_INVALID);
        }
        byte[] sourceBytes = audio.audioContent();
        AudioFormat targetFormat = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                calibration.sampleRateHz(), 16, 1, BYTES_PER_SAMPLE,
                calibration.sampleRateHz(), false);
        try (ByteArrayInputStream source = new ByteArrayInputStream(sourceBytes);
                AudioInputStream decoded = AudioSystem.getAudioInputStream(source);
                AudioInputStream pcmInput = AudioSystem.getAudioInputStream(
                        targetFormat, decoded)) {
            int maximumBytes = Math.toIntExact(Math.multiplyExact(
                    ((long) MAXIMUM_DURATION_MS + DURATION_TOLERANCE_MS)
                            * calibration.sampleRateHz() / 1_000L,
                    BYTES_PER_SAMPLE));
            byte[] pcmBytes = pcmInput.readNBytes(maximumBytes + 1);
            try {
                return decode(pcmBytes, audio.actualDurationMs(), calibration);
            } finally {
                Arrays.fill(pcmBytes, (byte) 0);
            }
        } catch (UnsupportedAudioFileException | IOException
                | IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.AUDIO_INVALID);
        } finally {
            Arrays.fill(sourceBytes, (byte) 0);
        }
    }

    private PcmAudio decode(
            byte[] pcmBytes,
            int inspectedDurationMs,
            DialectAcousticCalibration calibration) {
        if (pcmBytes.length == 0 || pcmBytes.length % BYTES_PER_SAMPLE != 0) {
            throw new BusinessException(ErrorCode.AUDIO_INVALID);
        }
        int sampleCount = pcmBytes.length / BYTES_PER_SAMPLE;
        int decodedDurationMs = (int) Math.round(
                sampleCount * 1_000.0D / calibration.sampleRateHz());
        if (decodedDurationMs > MAXIMUM_DURATION_MS
                || Math.abs(decodedDurationMs - inspectedDurationMs)
                        > DURATION_TOLERANCE_MS) {
            throw new BusinessException(ErrorCode.AUDIO_INVALID);
        }
        double[] samples = new double[sampleCount];
        int clippedSamples = 0;
        double sum = 0.0D;
        for (int index = 0; index < sampleCount; index++) {
            int offset = index * BYTES_PER_SAMPLE;
            short pcmValue = (short) ((pcmBytes[offset + 1] << 8)
                    | (pcmBytes[offset] & 0xFF));
            if (Math.abs((int) pcmValue) >= CLIPPED_ABSOLUTE_VALUE) {
                clippedSamples++;
            }
            samples[index] = pcmValue / 32_768.0D;
            sum += samples[index];
        }
        double mean = sum / sampleCount;
        for (int index = 0; index < sampleCount; index++) {
            samples[index] -= mean;
        }
        double clippedRatio = clippedSamples / (double) sampleCount;
        if (clippedRatio > calibration.maximumClippedSampleRatio()) {
            Arrays.fill(samples, 0.0D);
            throw new BusinessException(ErrorCode.AUDIO_INVALID);
        }
        return new PcmAudio(samples, calibration.sampleRateHz(), clippedRatio);
    }
}
