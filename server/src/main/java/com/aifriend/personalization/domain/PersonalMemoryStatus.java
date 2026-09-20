package com.aifriend.personalization.domain;

/**
 * 长期个人偏好的生命周期状态。
 *
 * @author Codex
 * @since 1.0.0
 */
public enum PersonalMemoryStatus {
    /** 当前存在可查看的偏好。 */
    ACTIVE,
    /** 偏好内容已清除，只保留最小幂等墓碑。 */
    DELETED
}
