package com.aifriend.template.application;

import java.util.Arrays;
import java.util.UUID;

/**
 * 当前工作器短期解密的既有日常指令模板。
 *
 * @author Codex
 * @since 1.0.0
 */
public final class RoutineCommandPlainTemplate implements AutoCloseable {

    private final UUID templateId;
    private final long version;
    private final byte[] material;

    /**
     * 创建带防御性材料副本的既有模板。
     *
     * @param templateId 模板 UUID
     * @param version 模板并发版本
     * @param material 已复验摘要的短期明文模板
     */
    public RoutineCommandPlainTemplate(
            UUID templateId,
            long version,
            byte[] material) {
        this.templateId = templateId;
        this.version = version;
        this.material = material.clone();
    }

    /**
     * 获取模板标识。
     *
     * @return 模板 UUID
     */
    public UUID templateId() {
        return templateId;
    }

    /**
     * 获取模板并发版本。
     *
     * @return 模板并发版本
     */
    public long version() {
        return version;
    }

    /**
     * 获取短期模板材料。
     *
     * @return 短期模板材料防御性副本
     */
    public byte[] material() {
        return material.clone();
    }

    /** 覆盖短期明文模板。 */
    @Override
    public void close() {
        Arrays.fill(material, (byte) 0);
    }
}
