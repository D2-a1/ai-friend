package com.aifriend.invitation.api;

import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aifriend.invitation.application.CreatedInvitation;
import com.aifriend.invitation.application.InvitationService;
import com.aifriend.invitation.application.PendingInvitationSummary;
import com.aifriend.shared.api.ApiResponse;
import com.aifriend.shared.security.CurrentUser;

/**
 * 当前用户亲友邀请创建与撤销 Controller。
 *
 * @author Codex
 * @since 1.0.0
 */
@Validated
@RestController
@RequestMapping("/contact-invitations")
public class InvitationsController {

    private static final String WAITING = "WAITING";
    private static final String WAITING_CONFIRMATION = "WAITING_CONFIRMATION";

    private final InvitationService invitationService;

    /**
     * 创建邀请 Controller。
     *
     * @param invitationService 邀请用例服务
     */
    public InvitationsController(InvitationService invitationService) {
        this.invitationService = invitationService;
    }

    /**
     * 创建固定 24 小时、单次且可撤销的亲友邀请。
     *
     * @param jwt 已验证 JWT
     * @param idempotencyKey 创建幂等键，不得记录日志
     * @return HTTP 201 与含敏感 fragment proof 的邀请响应
     */
    @PostMapping
    public ResponseEntity<ApiResponse<InvitationResp>> createContactInvitation(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey) {
        CurrentUser currentUser = CurrentUser.from(jwt);
        CreatedInvitation invitation = invitationService.create(currentUser.id(), idempotencyKey);
        InvitationResp response = new InvitationResp(
                invitation.invitationId(),
                invitation.shareUrl(),
                invitation.expiresAt(),
                WAITING);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    /**
     * 查询当前用户仍未过期且可撤销的邀请。
     *
     * @param jwt 已验证 JWT
     * @return 不含 proof、分享地址和内部进度的邀请摘要
     */
    @GetMapping
    public ResponseEntity<ApiResponse<java.util.List<PendingInvitationResp>>> listContactInvitations(
            @AuthenticationPrincipal Jwt jwt) {
        CurrentUser currentUser = CurrentUser.from(jwt);
        java.util.List<PendingInvitationResp> invitations = invitationService
                .listPending(currentUser.id())
                .stream()
                .map(this::toPendingResponse)
                .toList();
        return ResponseEntity.ok(ApiResponse.success(invitations));
    }

    /**
     * 撤销当前用户拥有的未完成邀请。
     *
     * @param jwt 已验证 JWT
     * @param invitationId 无权限公开邀请编号
     * @param idempotencyKey 撤销幂等键，不得记录日志
     * @return HTTP 204 空响应
     */
    @DeleteMapping("/{invitationId}")
    public ResponseEntity<Void> revokeContactInvitation(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String invitationId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey) {
        CurrentUser currentUser = CurrentUser.from(jwt);
        invitationService.revoke(currentUser.id(), invitationId, idempotencyKey);
        return ResponseEntity.noContent().build();
    }

    private PendingInvitationResp toPendingResponse(PendingInvitationSummary invitation) {
        return new PendingInvitationResp(
                invitation.invitationId(), invitation.expiresAt(), WAITING_CONFIRMATION);
    }
}
