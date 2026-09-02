package com.aifriend.template.application;

import java.util.Arrays;
import java.util.UUID;

import com.aifriend.task.domain.TaskIntent;

/**
 * 当前运行时匹配调用短期持有的日常指令明文模板。
 *
 * @author Codex
 * @since 1.0.0
 */
public final class RoutineCommandRuntimeTemplate implements AutoCloseable {

    private final UUID templateId;
    private final TaskIntent intent;
    private final byte[] material;

    /**
     * 创建带防御性副本的运行时模板。
     *
     * @param templateId 模板 UUID
     * @param intent 有限通信意图
     * @param material 已复验摘要的短期 MFCC 材料
     */
    public RoutineCommandRuntimeTemplate(
            UUID templateId,
            TaskIntent intent,
            byte[] material) {
        this.templateId = templateId;
        this.intent = intent;
        this.material = material.clone();
    }

    /**
     * 获取模板 UUID。
     *
     * @return 模板 UUID
     */
    public UUID templateId() {
        return templateId;
    }

    /**
     * 获取有限通信意图。
     *
     * @return 模板意图
     */
    public TaskIntent intent() {
        return intent;
    }

    /**
     * 获取模板材料防御性副本。
     *
     * @return 短期模板字节
     */
    public byte[] material() {
        return material.clone();
    }

    /** 覆盖当前调用持有的明文模板材料。 */
    @Override
    public void close() {
        Arrays.fill(material, (byte) 0);
    }
}
