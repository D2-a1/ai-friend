package com.aifriend.invitation.api;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 亲友明确接受邀请请求。
 *
 * @param confirmed 必须明确为 true，OAuth 成功不能替代本确认
 * @param consentPolicyVersion 页面实际展示的亲友同意政策版本
 * @param wechatId 亲友从本人微信资料复制的微信号，不是昵称或 OAuth OpenID
 * @author Codex
 * @since 1.0.0
 */
public record AcceptInvitationReq(
        @AssertTrue boolean confirmed,
        @NotBlank @Size(max = 40) String consentPolicyVersion,
        @NotBlank @Size(min = 6, max = 64)
        @Pattern(regexp = "[A-Za-z][A-Za-z0-9_-]{5,63}") String wechatId) {

    /** 避免异常或调试输出携带微信号。 */
    @Override
    public String toString() {
        return "AcceptInvitationReq[confirmed=" + confirmed
                + ", consentPolicyVersion=" + consentPolicyVersion
                + ", wechatId=<redacted>]";
    }
}
