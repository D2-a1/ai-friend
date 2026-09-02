package com.aifriend.template.application;

/**
 * 日常指令模板全量清除命令。
 *
 * @param confirmed 用户是否已完成第二次明确确认
 * @param expectedVersion 可选的 owner 日常模板命名空间版本
 * @author Codex
 * @since 1.0.0
 */
public record RoutineCommandDeletionCommand(
        boolean confirmed,
        Long expectedVersion) {

    /**
     * 生成不包含用户内容的幂等请求指纹输入。
     *
     * @return 版本化的确认与期望版本语义
     */
    public String fingerprintInput() {
        return "routine-command-delete-v1\u0000" + confirmed + "\u0000"
                + (expectedVersion == null ? "" : expectedVersion);
    }
}
