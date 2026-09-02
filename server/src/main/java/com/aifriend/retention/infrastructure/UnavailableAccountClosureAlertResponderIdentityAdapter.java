package com.aifriend.retention.infrastructure;

import java.util.Arrays;
import java.util.UUID;

import com.aifriend.retention.application.AccountClosureAlertResponderIdentity;
import com.aifriend.retention.application.AccountClosureAlertResponderIdentityPort;
import com.aifriend.shared.error.UpstreamFailureException;

/**
 * 缺少真实独立运维身份提供方时的失败关闭适配器。
 *
 * <p>不得回落普通用户 token、固定口令、日志确认或开发态自报身份。</p>
 *
 * @author Codex
 * @since 1.0.0
 */
public class UnavailableAccountClosureAlertResponderIdentityAdapter
        implements AccountClosureAlertResponderIdentityPort {

    /**
     * 创建失败关闭的默认运维身份适配器。
     */
    public UnavailableAccountClosureAlertResponderIdentityAdapter() {
    }

    /**
     * 拒绝在未配置独立运维身份提供方时确认接手。
     *
     * @param deliveryId 目标 P0 告警投递 UUID
     * @param credentialProof 一次性运维身份凭据
     * @return 永不返回
     * @throws UpstreamFailureException 始终抛出，表示真实身份提供方不可用
     */
    @Override
    public AccountClosureAlertResponderIdentity verify(
            UUID deliveryId,
            byte[] credentialProof) {
        if (credentialProof != null) {
            Arrays.fill(credentialProof, (byte) 0);
        }
        throw new UpstreamFailureException("独立运维身份提供方尚未配置");
    }
}
