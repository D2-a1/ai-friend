package com.aifriend.retention.infrastructure;

import com.aifriend.retention.application.AccountClosureAlertDelivery;
import com.aifriend.retention.application.AccountClosureAlertDeliveryPort;
import com.aifriend.retention.application.AccountClosureAlertDeliveryVerification;
import com.aifriend.retention.application.AccountClosureAlertSubmission;
import com.aifriend.shared.error.UpstreamFailureException;

/**
 * 未配置真实内部通知通道时的失败关闭适配器。
 *
 * <p>不得回落日志、普通邮件、本地文件或未验证 Webhook，也不得生成虚假受理流水号
 * 或投递回执。</p>
 *
 * @author Codex
 * @since 1.0.0
 */
public class UnavailableAccountClosureAlertDeliveryAdapter
        implements AccountClosureAlertDeliveryPort {

    /**
     * 创建失败关闭的默认通知适配器。
     */
    public UnavailableAccountClosureAlertDeliveryAdapter() {
    }

    /** {@inheritDoc} */
    @Override
    public AccountClosureAlertSubmission submit(AccountClosureAlertDelivery delivery) {
        throw new UpstreamFailureException("真实内部告警通知通道未配置");
    }

    /** {@inheritDoc} */
    @Override
    public AccountClosureAlertDeliveryVerification verify(
            AccountClosureAlertDelivery delivery) {
        throw new UpstreamFailureException("真实内部告警通知通道未配置");
    }
}