package com.aifriend.retention.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.aifriend.retention.application.AccountClosureService;
import com.aifriend.retention.application.AccountClosureView;
import com.aifriend.retention.application.TaskHistoryDeletionService;
import com.aifriend.retention.application.TaskHistoryDeletionView;
import com.aifriend.shared.api.ApiResponse;
import com.aifriend.shared.security.CurrentUser;

/**
 * 当前 owner 隐私清除接口。
 *
 * @author Codex
 * @since 1.0.0
 */
@Validated
@RestController
@RequestMapping("/me")
public class PrivacyController {
    private final TaskHistoryDeletionService service;
    private final AccountClosureService accountClosureService;

    /**
     * 创建隐私清除 Controller。
     *
     * @param service 任务历史清除应用服务
     * @param accountClosureService 账号注销可靠受理服务
     */
    public PrivacyController(
            TaskHistoryDeletionService service,
            AccountClosureService accountClosureService) {
        this.service = service;
        this.accountClosureService = accountClosureService;
    }

    /**
     * 可靠受理当前 owner 的任务录音与历史清除。
     *
     * @param jwt 已验证的当前用户令牌
     * @param idempotencyKey 当前清除请求的幂等键
     * @param request 明确确认的清除请求
     * @return 公开清除状态
     */
    @DeleteMapping("/task-history")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<TaskHistoryDeletionView> clearMyTaskHistory(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey,
            @Valid @RequestBody ConfirmedTaskHistoryDeletionReq request) {
        return ApiResponse.success(service.clear(CurrentUser.from(jwt).id(), idempotencyKey));
    }

    /**
     * 查询当前 owner 最近一次任务历史清除状态。
     *
     * @param jwt 已验证的当前用户令牌
     * @return 最近一次公开清除状态
     */
    @GetMapping("/task-history-deletion")
    public ApiResponse<TaskHistoryDeletionView> getMyTaskHistoryDeletion(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(service.get(CurrentUser.from(jwt).id()));
    }

    /**
     * 可靠受理当前 owner 的永久账号注销。
     *
     * @param jwt 已验证的当前用户令牌
     * @param idempotencyKey 当前注销请求幂等键
     * @param request 明确确认的永久注销请求
     * @return 注销受理时间与最早重新注册时间
     */
    @DeleteMapping("/account")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<AccountClosureView> closeMyAccount(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey,
            @Valid @RequestBody ConfirmedAccountClosureReq request) {
        return ApiResponse.success(accountClosureService.close(
                CurrentUser.from(jwt).id(), idempotencyKey, request.expectedVersion()));
    }
}
