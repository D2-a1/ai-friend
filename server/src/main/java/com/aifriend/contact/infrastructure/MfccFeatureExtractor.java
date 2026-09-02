package com.aifriend.contact.infrastructure;

import java.util.Arrays;

import com.aifriend.dialect.application.DialectAcousticCalibration;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;

/**
 * 提取用于发音内容时序比对的 MFCC 特征。
 *
 * <p>特征执行话语内均值方差归一化以降低音量和通道影响，但不产生说话人身份分数。
 */
final class MfccFeatureExtractor {

    private static final double PRE_EMPHASIS = 0.97D;
    private static final double MINIMUM_POWER = 1.0E-12D;

    float[][] extract(PcmAudio audio, DialectAcousticCalibration calibration) {
        double[] samples = audio.samples();
        try {
            return extractFeatures(audio, calibration, samples);
        } finally {
            Arrays.fill(samples, 0.0D);
        }
    }

    private float[][] extractFeatures(
            PcmAudio audio,
            DialectAcousticCalibration calibration,
            double[] samples) {
        int frameLength = calibration.sampleRateHz()
                * calibration.frameLengthMs() / 1_000;
        int frameShift = calibration.sampleRateHz()
                * calibration.frameShiftMs() / 1_000;
        if (audio.sampleRateHz() != calibration.sampleRateHz()
                || frameLength < 2
                || frameShift < 1
                || samples.length < frameLength) {
            throw new BusinessException(ErrorCode.AUDIO_INVALID);
        }
        int frameCount = 1 + (samples.length - frameLength) / frameShift;
        double[] frameEnergyDb = computeFrameEnergyDb(
                samples, frameLength, frameShift, frameCount);
        double peakEnergyDb = Arrays.stream(frameEnergyDb).max().orElse(-120.0D);
        if (!Double.isFinite(peakEnergyDb)
                || peakEnergyDb < calibration.minimumPeakDbfs()) {
            throw new BusinessException(ErrorCode.AUDIO_INVALID);
        }
        int firstActive = -1;
        int lastActive = -1;
        int activeCount = 0;
        double activeFloor = peakEnergyDb - calibration.vadRelativeFloorDb();
        for (int frameIndex = 0; frameIndex < frameCount; frameIndex++) {
            if (frameEnergyDb[frameIndex] >= activeFloor) {
                activeCount++;
                if (firstActive < 0) {
                    firstActive = frameIndex;
                }
                lastActive = frameIndex;
            }
        }
        if (firstActive < 0
                || activeCount / (double) frameCount
                < calibration.minimumActiveFrameRatio()) {
            throw new BusinessException(ErrorCode.AUDIO_INVALID);
        }

        int fftSize = nextPowerOfTwo(frameLength);
        double[][] melWeights = buildMelWeights(
                calibration.sampleRateHz(), fftSize, calibration.melFilterCount());
        double[] hammingWindow = buildHammingWindow(frameLength);
        float[][] features = new float[lastActive - firstActive + 1]
                [calibration.coefficientCount()];
        for (int frameIndex = firstActive; frameIndex <= lastActive; frameIndex++) {
            int sampleOffset = frameIndex * frameShift;
            features[frameIndex - firstActive] = extractFrame(
                    samples, sampleOffset, frameLength, fftSize,
                    hammingWindow, melWeights, calibration.coefficientCount());
        }
        normalizeFeatures(features);
        return features;
    }

    private double[] computeFrameEnergyDb(
            double[] samples,
            int frameLength,
            int frameShift,
            int frameCount) {
        double[] energies = new double[frameCount];
        for (int frameIndex = 0; frameIndex < frameCount; frameIndex++) {
            int offset = frameIndex * frameShift;
            double energy = 0.0D;
            for (int sampleIndex = 0; sampleIndex < frameLength; sampleIndex++) {
                double value = samples[offset + sampleIndex];
                energy += value * value;
            }
            energies[frameIndex] = 10.0D * Math.log10(
                    Math.max(energy / frameLength, MINIMUM_POWER));
        }
        return energies;
    }

    private float[] extractFrame(
            double[] samples,
            int sampleOffset,
            int frameLength,
            int fftSize,
            double[] hammingWindow,
            double[][] melWeights,
            int coefficientCount) {
        double[] real = new double[fftSize];
        double[] imaginary = new double[fftSize];
        for (int index = 0; index < frameLength; index++) {
            double current = samples[sampleOffset + index];
            double previous = index == 0 ? samples[Math.max(0, sampleOffset - 1)]
                    : samples[sampleOffset + index - 1];
            real[index] = (current - PRE_EMPHASIS * previous) * hammingWindow[index];
        }
        fft(real, imaginary);
        double[] powerSpectrum = new double[fftSize / 2 + 1];
        for (int index = 0; index < powerSpectrum.length; index++) {
            powerSpectrum[index] = (real[index] * real[index]
                    + imaginary[index] * imaginary[index]) / fftSize;
        }
        double[] logMelEnergy = new double[melWeights.length];
        for (int filterIndex = 0; filterIndex < melWeights.length; filterIndex++) {
            double filterEnergy = 0.0D;
            for (int bin = 0; bin < powerSpectrum.length; bin++) {
                filterEnergy += powerSpectrum[bin] * melWeights[filterIndex][bin];
            }
            logMelEnergy[filterIndex] = Math.log(Math.max(filterEnergy, MINIMUM_POWER));
        }
        float[] coefficients = new float[coefficientCount];
        for (int coefficientIndex = 0;
                coefficientIndex < coefficientCount;
                coefficientIndex++) {
            double coefficient = 0.0D;
            int dctIndex = coefficientIndex + 1;
            for (int filterIndex = 0;
                    filterIndex < logMelEnergy.length;
                    filterIndex++) {
                coefficient += logMelEnergy[filterIndex] * Math.cos(
                        Math.PI * dctIndex * (filterIndex + 0.5D)
                                / logMelEnergy.length);
            }
            coefficients[coefficientIndex] = (float) coefficient;
        }
        return coefficients;
    }

    private double[][] buildMelWeights(int sampleRateHz, int fftSize, int filterCount) {
        int spectrumSize = fftSize / 2 + 1;
        double minimumMel = hzToMel(50.0D);
        double maximumMel = hzToMel(sampleRateHz / 2.0D);
        int[] bins = new int[filterCount + 2];
        for (int index = 0; index < bins.length; index++) {
            double mel = minimumMel + (maximumMel - minimumMel)
                    * index / (bins.length - 1.0D);
            int bin = (int) Math.floor((fftSize + 1.0D)
                    * melToHz(mel) / sampleRateHz);
            bins[index] = Math.max(0, Math.min(spectrumSize - 1, bin));
        }
        double[][] weights = new double[filterCount][spectrumSize];
        for (int filterIndex = 0; filterIndex < filterCount; filterIndex++) {
            int left = bins[filterIndex];
            int center = Math.max(left + 1, bins[filterIndex + 1]);
            int right = Math.max(center + 1, bins[filterIndex + 2]);
            center = Math.min(center, spectrumSize - 1);
            right = Math.min(right, spectrumSize - 1);
            for (int bin = left; bin < center; bin++) {
                weights[filterIndex][bin] = (bin - left)
                        / (double) Math.max(1, center - left);
            }
            for (int bin = center; bin <= right && bin < spectrumSize; bin++) {
                weights[filterIndex][bin] = (right - bin)
                        / (double) Math.max(1, right - center);
            }
        }
        return weights;
    }

    private double[] buildHammingWindow(int frameLength) {
        double[] window = new double[frameLength];
        for (int index = 0; index < frameLength; index++) {
            window[index] = 0.54D - 0.46D * Math.cos(
                    2.0D * Math.PI * index / (frameLength - 1.0D));
        }
        return window;
    }

    private void normalizeFeatures(float[][] features) {
        int dimensions = features[0].length;
        for (int dimension = 0; dimension < dimensions; dimension++) {
            double mean = 0.0D;
            for (float[] frame : features) {
                mean += frame[dimension];
            }
            mean /= features.length;
            double variance = 0.0D;
            for (float[] frame : features) {
                double centered = frame[dimension] - mean;
                variance += centered * centered;
            }
            double standardDeviation = Math.sqrt(
                    variance / Math.max(1, features.length - 1));
            for (float[] frame : features) {
                frame[dimension] = standardDeviation < 1.0E-6D
                        ? 0.0F
                        : (float) ((frame[dimension] - mean) / standardDeviation);
            }
        }
    }

    private int nextPowerOfTwo(int value) {
        int power = 1;
        while (power < value) {
            power <<= 1;
        }
        if (power > 2_048) {
            throw new BusinessException(ErrorCode.AUDIO_INVALID);
        }
        return power;
    }

    /**
     * 原地 radix-2 Cooley-Tukey FFT，避免引入本地或大型数值依赖。
     */
    private void fft(double[] real, double[] imaginary) {
        int length = real.length;
        for (int sourceIndex = 1, targetIndex = 0;
                sourceIndex < length;
                sourceIndex++) {
            int bit = length >> 1;
            while ((targetIndex & bit) != 0) {
                targetIndex ^= bit;
                bit >>= 1;
            }
            targetIndex ^= bit;
            if (sourceIndex < targetIndex) {
                double realValue = real[sourceIndex];
                real[sourceIndex] = real[targetIndex];
                real[targetIndex] = realValue;
                double imaginaryValue = imaginary[sourceIndex];
                imaginary[sourceIndex] = imaginary[targetIndex];
                imaginary[targetIndex] = imaginaryValue;
            }
        }
        for (int blockLength = 2;
                blockLength <= length;
                blockLength <<= 1) {
            double angle = -2.0D * Math.PI / blockLength;
            double rootReal = Math.cos(angle);
            double rootImaginary = Math.sin(angle);
            for (int blockStart = 0;
                    blockStart < length;
                    blockStart += blockLength) {
                double factorReal = 1.0D;
                double factorImaginary = 0.0D;
                for (int offset = 0; offset < blockLength / 2; offset++) {
                    int evenIndex = blockStart + offset;
                    int oddIndex = evenIndex + blockLength / 2;
                    double oddReal = real[oddIndex] * factorReal
                            - imaginary[oddIndex] * factorImaginary;
                    double oddImaginary = real[oddIndex] * factorImaginary
                            + imaginary[oddIndex] * factorReal;
                    real[oddIndex] = real[evenIndex] - oddReal;
                    imaginary[oddIndex] = imaginary[evenIndex] - oddImaginary;
                    real[evenIndex] += oddReal;
                    imaginary[evenIndex] += oddImaginary;
                    double nextFactorReal = factorReal * rootReal
                            - factorImaginary * rootImaginary;
                    factorImaginary = factorReal * rootImaginary
                            + factorImaginary * rootReal;
                    factorReal = nextFactorReal;
                }
            }
        }
    }

    private double hzToMel(double frequencyHz) {
        return 2_595.0D * Math.log10(1.0D + frequencyHz / 700.0D);
    }

    private double melToHz(double mel) {
        return 700.0D * (Math.pow(10.0D, mel / 2_595.0D) - 1.0D);
    }
}
