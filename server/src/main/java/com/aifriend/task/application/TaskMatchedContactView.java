package com.aifriend.task.application;

/**
 * 任务响应中的联系人最小展示快照。
 *
 * @param id ct_ 前缀联系人编号
 * @param displayName 最小展示名称
 * @param alias 命中的方言称呼
 * @author Codex
 * @since 1.0.0
 */
public record TaskMatchedContactView(String id, String displayName, String alias) {
}
