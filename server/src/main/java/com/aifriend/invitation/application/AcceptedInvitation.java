package com.aifriend.invitation.application;

/**
 * 亲友明确接受后的最小结果。
 *
 * @param status 固定为 ACCEPTED_READY_FOR_ALIAS
 * @author Codex
 * @since 1.0.0
 */
public record AcceptedInvitation(String status) {

    /** 接受成功后的固定公开状态。 */
    public static final String ACCEPTED_READY_FOR_ALIAS = "ACCEPTED_READY_FOR_ALIAS";
}
