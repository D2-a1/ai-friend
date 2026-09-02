package com.aifriend.retention.application;

import java.util.List;

/**
 * 允许投递到外部告警通道的注销告警类型。
 *
 * @author Codex
 * @since 1.0.0
 */
public enum AccountClosureAlertType {
    /** 受理满 24 小时仍未完成的预警。 */
    ACCOUNT_CLOSURE_DELAY_WARNING,
    /** 受理满 48 小时仍未完成的 P0。 */
    ACCOUNT_CLOSURE_P0_OPENED,
    /** P0 超过 15 分钟仍未确认的升级事件。 */
    ACCOUNT_CLOSURE_P0_ESCALATED,
    /** 超过 72 小时在线删除硬期限的事件。 */
    ACCOUNT_CLOSURE_DEADLINE_BREACHED;

    /**
     * 返回当前告警固定的责任接收组。
     *
     * @return 不包含具体人员或联系方式的责任组
     */
    public List<AccountClosureAlertAudience> audiences() {
        if (this == ACCOUNT_CLOSURE_DELAY_WARNING) {
            return List.of(AccountClosureAlertAudience.ON_CALL);
        }
        return List.of(
                AccountClosureAlertAudience.ON_CALL,
                AccountClosureAlertAudience.PRIVACY_OFFICER);
    }
}
