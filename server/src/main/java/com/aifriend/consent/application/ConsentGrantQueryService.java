package com.aifriend.consent.application;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aifriend.consent.domain.ConsentDecision;
import com.aifriend.consent.domain.ConsentType;

/**
 * 当前分项授权查询服务。
 *
 * <p>只读取指定类型的最新追加记录；未授权、未作决定或最新决定为撤回时均失败关闭。
 *
 * @author Codex
 * @since 1.0.0
 */
@Service
public class ConsentGrantQueryService implements ConsentGrantQueryPort {

    private final ConsentRecordPort consentRecordPort;

    /**
     * 创建当前授权查询服务。
     *
     * @param consentRecordPort 追加式授权记录端口
     */
    public ConsentGrantQueryService(ConsentRecordPort consentRecordPort) {
        this.consentRecordPort = consentRecordPort;
    }

    /**
     * 判断用户指定授权类型是否当前有效。
     *
     * @param userId 用户 UUID
     * @param type 授权类型
     * @return 最新授权决定为 GRANTED 时返回 true
     */
    @Override
    @Transactional(readOnly = true)
    public boolean isGranted(UUID userId, ConsentType type) {
        return consentRecordPort.findLatest(userId, type)
                .map(record -> record.decision() == ConsentDecision.GRANTED)
                .orElse(false);
    }

    /**
     * 判断用户指定授权类型是否以目标政策版本有效同意。
     *
     * @param userId 用户 UUID
     * @param type 授权类型
     * @param policyVersion 客户端声明的政策版本
     * @return 最新记录为 GRANTED 且政策版本完全一致时返回 true
     */
    @Override
    @Transactional(readOnly = true)
    public boolean isGrantedForPolicy(
            UUID userId,
            ConsentType type,
            String policyVersion) {
        return consentRecordPort.findLatest(userId, type)
                .map(record -> record.decision() == ConsentDecision.GRANTED
                        && record.policyVersion().equals(policyVersion))
                .orElse(false);
    }
}
