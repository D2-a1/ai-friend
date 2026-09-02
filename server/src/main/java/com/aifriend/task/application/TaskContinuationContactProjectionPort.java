package com.aifriend.task.application;

import java.util.UUID;

/**
 * 继续窗口使用的 owner 范围有效联系人投影端口。
 *
 * <p>本端口只回答联系人是否仍可参与任务匹配，不读取微信页面、稳定定位或主体明文。
 *
 * @author codex
 * @since 1.0.0
 */
public interface TaskContinuationContactProjectionPort {

    /**
     * 判断联系人是否仍属于当前 owner 且处于 ACTIVE。
     *
     * @param ownerUserId owner UUID
     * @param contactId 联系人 UUID
     * @return 只有 owner 与 ACTIVE 状态同时匹配时返回 true
     */
    boolean isActive(UUID ownerUserId, UUID contactId);
}
