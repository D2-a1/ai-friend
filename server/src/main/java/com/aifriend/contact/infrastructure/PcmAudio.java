package com.aifriend.contact.infrastructure;

import java.util.Arrays;

/**
 * 标准化单声道 PCM 及其削波比例。
 *
 * @param samples 归一化到负一至一的采样值
 * @param sampleRateHz 采样率
 * @param clippedSampleRatio 削波采样比例
 */
record PcmAudio(
        double[] samples,
        int sampleRateHz,
        double clippedSampleRatio) {

    PcmAudio {
        samples = samples.clone();
    }

    @Override
    public double[] samples() {
        return samples.clone();
    }

    /** 覆盖当前短期 PCM 缓冲。 */
    void clear() {
        Arrays.fill(samples, 0.0D);
    }
}
