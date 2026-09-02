package com.aifriend.contact.infrastructure;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

import com.aifriend.contact.application.AcousticEnrollmentSample;
import com.aifriend.dialect.application.DialectAcousticCalibration;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;

/**
 * 将已经过对象内容校验的 WAV 转为固定采样率单声道 PCM。
 */
final class WavPcmNormalizer {

    private static final int BYTES_PER_SAMPLE = 2;
    private static final int MAX_DURATION_TOLERANCE_MS = 100;
    private static final int CLIPPED_ABSOLUTE_VALUE = 32_760;

    PcmAudio normalize(
            AcousticEnrollmentSample sample,
            DialectAcousticCalibration calibration) {
        if (sample == null
                || !"audio/wav".equals(sample.mediaType())
                || sample.actualDurationMs() < calibration.minimumDurationMs()
                || sample.actualDurationMs() > calibration.maximumDurationMs()) {
            throw new BusinessException(ErrorCode.AUDIO_INVALID);
        }
        AudioFormat targetFormat = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                calibration.sampleRateHz(),
                16,
                1,
                BYTES_PER_SAMPLE,
                calibration.sampleRateHz(),
                false);
        try (ByteArrayInputStream source = new ByteArrayInputStream(sample.audioContent());
                AudioInputStream decodedInput = AudioSystem.getAudioInputStream(source);
                AudioInputStream pcmInput = AudioSystem.getAudioInputStream(
                        targetFormat, decodedInput)) {
            byte[] pcmBytes = readBoundedPcm(pcmInput, calibration);
            return decodePcm(pcmBytes, sample.actualDurationMs(), calibration);
        } catch (UnsupportedAudioFileException | IOException
                | IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.AUDIO_INVALID);
        }
    }

    private byte[] readBoundedPcm(
            AudioInputStream pcmInput,
            DialectAcousticCalibration calibration) throws IOException {
        long maximumSamples = Math.multiplyExact(
                (long) calibration.maximumDurationMs() + MAX_DURATION_TOLERANCE_MS,
                calibration.sampleRateHz()) / 1_000L;
        int maximumBytes = Math.toIntExact(Math.multiplyExact(
                maximumSamples, BYTES_PER_SAMPLE));
        byte[] pcmBytes = pcmInput.readNBytes(maximumBytes + 1);
        if (pcmBytes.length == 0
                || pcmBytes.length > maximumBytes
                || pcmBytes.length % BYTES_PER_SAMPLE != 0) {
            throw new BusinessException(ErrorCode.AUDIO_INVALID);
        }
        return pcmBytes;
    }

    private PcmAudio decodePcm(
            byte[] pcmBytes,
            int inspectedDurationMs,
            DialectAcousticCalibration calibration) {
        int sampleCount = pcmBytes.length / BYTES_PER_SAMPLE;
        int decodedDurationMs = (int) Math.round(
                sampleCount * 1_000.0D / calibration.sampleRateHz());
        if (decodedDurationMs < calibration.minimumDurationMs()
                || decodedDurationMs > calibration.maximumDurationMs()
                || Math.abs(decodedDurationMs - inspectedDurationMs)
                > MAX_DURATION_TOLERANCE_MS) {
            throw new BusinessException(ErrorCode.AUDIO_INVALID);
        }
        double[] samples = new double[sampleCount];
        int clippedSamples = 0;
        double sampleSum = 0.0D;
        for (int index = 0; index < sampleCount; index++) {
            int byteOffset = index * BYTES_PER_SAMPLE;
            int lower = pcmBytes[byteOffset] & 0xFF;
            int upper = pcmBytes[byteOffset + 1];
            short pcmValue = (short) ((upper << 8) | lower);
            if (Math.abs((int) pcmValue) >= CLIPPED_ABSOLUTE_VALUE) {
                clippedSamples++;
            }
            samples[index] = pcmValue / 32_768.0D;
            sampleSum += samples[index];
        }
        double mean = sampleSum / sampleCount;
        for (int index = 0; index < samples.length; index++) {
            samples[index] -= mean;
        }
        double clippedRatio = clippedSamples / (double) sampleCount;
        if (clippedRatio > calibration.maximumClippedSampleRatio()) {
            throw new BusinessException(ErrorCode.AUDIO_INVALID);
        }
        return new PcmAudio(samples, calibration.sampleRateHz(), clippedRatio);
    }
}
