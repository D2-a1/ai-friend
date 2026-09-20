package com.aifriend.task.application;

import java.util.Optional;
import java.util.UUID;

/**
 * 任务域读取已验证联系人稳定定位的受限投影端口。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface WechatActionContactProjectionPort {

    /**
     * 加锁读取当前 owner 的有效联系人定位快照。
     *
     * <p>实现必须同时复验 ACTIVE 状态、定位规则版本和密文完整性；任一条件
     * 不满足时返回空，不得回退昵称、备注、头像、搜索顺序或坐标。历史微信版本
     * 只用于诊断且允许为空；当前客户端版本与执行规则的组合由任务域能力白名单
     * 独立校验，不能拿历史字段替代当前执行环境门禁。
     *
     * @param ownerUserId JWT 派生 owner UUID
     * @param contactId 当前任务选择的联系人 UUID
     * @param expectedLocatorVersion 当前任务定位规则版本
     * @return 可签发证明的联系人快照，否则为空
     */
    Optional<WechatActionContactSnapshot> findVerifiedForUpdate(
            UUID ownerUserId,
            UUID contactId,
            String expectedLocatorVersion);

    /**
     * 判断联系人是否为当前 owner 的 Debug 合成体验联系人。
     *
     * <p>实现只能使用内部固定主体摘要判断，不得依赖可编辑备注、关系文字或客户端标记。
     *
     * @param ownerUserId JWT 派生 owner UUID
     * @param contactId 当前任务选择的联系人 UUID
     * @return 仅命中有效体验联系人时返回 true
     */
    boolean isDebugDemoContact(UUID ownerUserId, UUID contactId);
}
