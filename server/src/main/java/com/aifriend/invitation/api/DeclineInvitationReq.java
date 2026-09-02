package com.aifriend.invitation.api;

import jakarta.validation.constraints.AssertTrue;

/**
 * 亲友明确拒绝邀请请求。
 *
 * @param confirmed 必须明确为 true，关闭页面或返回不能视为拒绝
 * @author Codex
 * @since 1.0.0
 */
public record DeclineInvitationReq(@AssertTrue boolean confirmed) {
}
