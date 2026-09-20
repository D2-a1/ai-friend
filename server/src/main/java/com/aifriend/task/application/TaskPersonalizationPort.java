package com.aifriend.task.application;

import java.util.UUID;

/** 将长期偏好隔离为任务草稿层可读的低权限端口。 */
public interface TaskPersonalizationPort {

    /**
     * 读取当前 owner 的有限偏好；任何不确定都必须返回安全默认值。
     *
     * @param ownerUserId 当前 JWT 派生 owner
     * @return 不含用户标识和自由文本的三项枚举
     */
    TaskConversationPreferences currentPreferences(UUID ownerUserId);
}