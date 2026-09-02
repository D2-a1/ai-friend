package com.aifriend.template.application;

import java.util.Arrays;

/**
 * 从单个明确动作范围提取的短期 MFCC 模板候选。
 *
 * @author Codex
 * @since 1.0.0
 */
public final class RoutineCommandAcousticCandidate implements AutoCloseable {

    private final byte[] material;

    /**
     * 创建带防御性副本的模板候选。
     *
     * @param material 版本化单段 MFCC 模板
     */
    public RoutineCommandAcousticCandidate(byte[] material) {
        this.material = material.clone();
    }

    /**
     * 返回模板材料防御性副本。
     *
     * @return 短期模板字节
     */
    public byte[] material() {
        return material.clone();
    }

    /** 覆盖当前工作器持有的模板材料。 */
    @Override
    public void close() {
        Arrays.fill(material, (byte) 0);
    }
}
