package com.aifriend.knowledge.application;

import java.util.UUID;

/**
 * 私人图谱撤权/注销清理，不读取或信任旧投影内容。
 * @author Codex
 * @since 1.0.0
 */
public interface GraphOwnerCleanupPort {
    /**
     * 在与发布一致的账号锁内清除三表并复验，加入调用方事务。
     * 调用方必须已授权撤权或注销；此端口不是公开删除接口。
     * @param ownerUserId 待清理账号
     * @return 删除记录总数；重复清理返回零
     * @throws GraphProjectionException 当清理或清零复验失败时抛出并回滚
     */
    int purgeOwner(UUID ownerUserId);
}
