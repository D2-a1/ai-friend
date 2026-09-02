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
     * 不满足时返回空，不得回退昵称、备注、头像、搜索顺序或坐标。投影返回本机
     * 验证时的历史微信版本；当调用方要求精确版本门禁时，实现必须在解密定位前
     * 拒绝历史版本不一致的联系人。
     *
     * @param ownerUserId JWT 派生 owner UUID
     * @param contactId 当前任务选择的联系人 UUID
     * @param expectedWechatVersion 当前任务微信版本
     * @param expectedLocatorVersion 当前任务定位规则版本
     * @param requireExactWechatVersion 是否要求历史验证微信版本与当前版本精确一致
     * @return 可签发证明的联系人快照，否则为空
     */
    Optional<WechatActionContactSnapshot> findVerifiedForUpdate(
            UUID ownerUserId,
            UUID contactId,
            String expectedWechatVersion,
            String expectedLocatorVersion,
            boolean requireExactWechatVersion);

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
