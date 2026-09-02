package com.aifriend.retention.application;

/**
 * 注销 P0 告警唯一接手确认和迟到升级事务端口。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface AccountClosureAlertAcknowledgementRepositoryPort {

    /**
     * 锁定 P0 告警并原子写入唯一接手事实。
     *
     * <p>迟到接手必须同时保留或补写未接手升级事实；通知回执不能调用本方法。</p>
     *
     * @param write 已验证且只含摘要的接手写入命令
     * @return 首次写入或同键同正文幂等重放的接手结果
     * @throws RuntimeException 投递不存在、未送达、责任组不符或幂等冲突时抛出
     */
    AccountClosureAlertAcknowledgement acknowledge(
            AccountClosureAlertAcknowledgementWrite write);
}
