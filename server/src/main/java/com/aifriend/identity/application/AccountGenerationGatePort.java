package com.aifriend.identity.application;

import java.time.Instant;

import com.aifriend.shared.error.BusinessException;

/**
 * 基于删除墓碑判断账号创建代次的端口。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface AccountGenerationGatePort {

    /**
     * 锁定当前主体的最新删除墓碑并计算下一账号代次。
     *
     * @param subjectHash 微信主体不可逆查询键
     * @param now 当前 UTC 时间
     * @return 首次注册返回 1，重新注册返回上一代次加 1
     * @throws BusinessException 注销未完成或 72 小时下限未到时抛出
     */
    long nextGeneration(byte[] subjectHash, Instant now);
}
