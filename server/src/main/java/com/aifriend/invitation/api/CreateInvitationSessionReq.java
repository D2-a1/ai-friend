package com.aifriend.invitation.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * URL fragment proof 兑换请求。
 *
 * @param invitationId 无权限公开邀请编号
 * @param proof fragment 中的 256 位秘密，只能在当前请求内存中短暂存在
 * @author Codex
 * @since 1.0.0
 */
public record CreateInvitationSessionReq(
        @NotBlank @Pattern(regexp = "^iv_[A-Za-z0-9]+$") String invitationId,
        @NotBlank @Size(min = 43, max = 512) String proof) {

    /**
     * 返回不含 proof 和公开编号的安全调试文本。
     *
     * @return 固定脱敏文本
     */
    @Override
    public String toString() {
        return "CreateInvitationSessionReq[redacted]";
    }
}
