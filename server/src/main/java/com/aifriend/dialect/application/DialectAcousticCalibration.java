package com.aifriend.dialect.application;

/**
 * 经授权语料标定并由方言包清单固定的声学参数。
 *
 * <p>所有距离均为归一化 DTW 距离，数值越小表示发音内容越相似。本对象不包含
 * 说话人身份阈值，也不得用于声纹识别或认证。
 *
 * @param acousticEngine 声学引擎标识
 * @param sampleRateHz 标准化采样率
 * @param frameLengthMs 分帧长度毫秒数
 * @param frameShiftMs 分帧步长毫秒数
 * @param melFilterCount Mel 滤波器数量
 * @param coefficientCount 每帧 MFCC 系数数量
 * @param minimumDurationMs 最短注册录音时长
 * @param maximumDurationMs 最长注册录音时长
 * @param minimumPeakDbfs 允许进入模板提取的最小峰值帧能量
 * @param vadRelativeFloorDb 相对峰值能量的有效语音下限
 * @param minimumActiveFrameRatio 最小有效语音帧比例
 * @param maximumClippedSampleRatio 最大削波采样比例
 * @param dtwWindowRatio DTW Sakoe-Chiba 窗口占较长序列比例
 * @param enrollmentConsistencyMaxDistance 两遍注册录音允许的最大距离
 * @param uniquenessConflictMaxDistance 判定与既有称呼冲突的最大距离
 * @param uniquenessDistinctMinDistance 判定可可靠区分的最小距离
 * @param taskAliasUniqueMaxDistance 任务称呼唯一候选允许的最大距离
 * @param taskAliasCandidateMaxDistance 任务称呼候选列表允许的最大距离
 * @param taskAliasMinimumMargin Top-1 与 Top-2 判定唯一所需的最小距离差
 * @author Codex
 * @since 1.0.0
 */
public record DialectAcousticCalibration(
        String acousticEngine,
        int sampleRateHz,
        int frameLengthMs,
        int frameShiftMs,
        int melFilterCount,
        int coefficientCount,
        int minimumDurationMs,
        int maximumDurationMs,
        double minimumPeakDbfs,
        double vadRelativeFloorDb,
        double minimumActiveFrameRatio,
        double maximumClippedSampleRatio,
        double dtwWindowRatio,
        double enrollmentConsistencyMaxDistance,
        double uniquenessConflictMaxDistance,
        double uniquenessDistinctMinDistance,
        double taskAliasUniqueMaxDistance,
        double taskAliasCandidateMaxDistance,
        double taskAliasMinimumMargin) {
}
