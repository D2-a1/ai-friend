package com.aifriend.task.application;

/**
 * 候选联系人选择命令。
 *
 * @param candidateId 当前会话候选编号
 * @param expectedVersion 客户端看到的会话版本
 * @author Codex
 * @since 1.0.0
 */
public record SelectTaskCandidateCommand(String candidateId, long expectedVersion) {
}
