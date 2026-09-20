package com.aifriend.knowledge.application;

import java.util.List;
import java.util.UUID;

import com.aifriend.knowledge.domain.ContactDisplay;
import com.aifriend.knowledge.domain.GraphSnapshot;

/**
 * 由contact基础设施实现的权威只读适配边界。
 * @author codex
 * @since 1.0.0
 */
public interface ContactGraphSourcePort {
    /**
     * 在新的只读REPEATABLE_READ事务获取一致来源，仅保留ID/版本。
     * @param ownerUserId 当前主体
     * @return 完整图事实，不包含显示文字、定位或模板
     */
    GraphSnapshot snapshot(UUID ownerUserId);

    /**
     * 在另一个新事务复核来源摘要、ACTIVE状态及用途同意，再解密当前显示称呼。
     * 不在初次快照的REPEATABLE_READ事务重用旧视图；异常必须失败关闭。
     * @param ownerUserId 当前主体
     * @param expectedSourceDigest 初次读取的来源摘要
     * @param contactIds 最多20个当前owner候选
     * @return 与请求ID对应的完整当前显示数据，不可跳过坏项返回部分成功
     */
    List<ContactDisplay> displayCurrent(UUID ownerUserId, String expectedSourceDigest, List<UUID> contactIds);
}
