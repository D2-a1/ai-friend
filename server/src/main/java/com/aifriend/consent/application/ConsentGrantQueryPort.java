package com.aifriend.consent.application;

import java.util.UUID;

import com.aifriend.consent.domain.ConsentType;

/**
 * 当前分项授权查询端口。
 *
 * <p>其他业务域只依赖此最小端口判断当前授权，不读取追加式授权历史或 JPA 实体。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface ConsentGrantQueryPort {

    /**
     * 判断用户指定授权类型的最新决定是否为已同意。
     *
     * @param userId 用户 UUID
     * @param type 授权类型
     * @return 最新决定为 GRANTED 时返回 true
     */
    boolean isGranted(UUID userId, ConsentType type);

    /**
     * 判断用户指定授权类型的最新决定是否为目标政策版本的有效同意。
     *
     * @param userId 用户 UUID
     * @param type 授权类型
     * @param policyVersion 客户端声明并须与最新记录完全一致的政策版本
     * @return 最新决定为 GRANTED 且政策版本完全一致时返回 true
     */
    boolean isGrantedForPolicy(UUID userId, ConsentType type, String policyVersion);
}
