package com.aifriend.retention.application;

import java.util.Arrays;

/**
 * 独立灾备介质完成幂等写入和回读校验后返回的最小回执。
 *
 * @param storedEnvelopeHash 独立介质回读得到的完整信封 SHA-256
 * @param receiptProof 供应端有界回执证明原文，仅用于当前调用内存计算摘要
 * @author Codex
 * @since 1.0.0
 */
public record DeletionTombstoneExportReceipt(
        byte[] storedEnvelopeHash,
        byte[] receiptProof) {

    /**
     * 校验回执边界并隔离可变数组。
     */
    public DeletionTombstoneExportReceipt {
        if (storedEnvelopeHash == null || storedEnvelopeHash.length != 32) {
            throw new IllegalArgumentException("灾备回读摘要无效");
        }
        if (receiptProof == null || receiptProof.length < 16 || receiptProof.length > 1024) {
            throw new IllegalArgumentException("灾备回执证明大小无效");
        }
        storedEnvelopeHash = Arrays.copyOf(storedEnvelopeHash, storedEnvelopeHash.length);
        receiptProof = Arrays.copyOf(receiptProof, receiptProof.length);
    }

    /**
     * 返回独立介质回读摘要的防御性副本。
     *
     * @return 回读摘要副本
     */
    @Override
    public byte[] storedEnvelopeHash() {
        return Arrays.copyOf(storedEnvelopeHash, storedEnvelopeHash.length);
    }

    /**
     * 返回供应端回执证明的防御性副本。
     *
     * @return 回执证明副本
     */
    @Override
    public byte[] receiptProof() {
        return Arrays.copyOf(receiptProof, receiptProof.length);
    }
}
