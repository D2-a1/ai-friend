package com.aifriend.contact.api;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.http.HttpStatus;

import com.aifriend.contact.application.ContactAliasDeleteService;
import com.aifriend.contact.application.ContactAliasEnrollmentService;
import com.aifriend.contact.application.ContactAliasSummary;
import com.aifriend.contact.application.ContactQueryService;
import com.aifriend.contact.application.ContactSummary;
import com.aifriend.contact.application.ContactSummaryPage;
import com.aifriend.contact.application.ContactUnbindCommand;
import com.aifriend.contact.application.ContactUnbindService;
import com.aifriend.contact.application.ContactVerificationService;
import com.aifriend.contact.application.CreateContactAliasCommand;
import com.aifriend.contact.application.DeleteContactAliasCommand;
import com.aifriend.contact.application.LocalVerificationCommand;
import com.aifriend.contact.domain.ContactStatus;
import com.aifriend.shared.api.ApiResponse;
import com.aifriend.shared.security.CurrentUser;

/**
 * 当前用户联系人查询与本机验证 Controller。
 *
 * @author Codex
 * @since 1.0.0
 */
@Validated
@RestController
@RequestMapping("/contacts")
public class ContactsController {

    private final ContactQueryService contactQueryService;
    private final ContactVerificationService contactVerificationService;
    private final ContactUnbindService contactUnbindService;
    private final ContactAliasEnrollmentService contactAliasEnrollmentService;
    private final ContactAliasDeleteService contactAliasDeleteService;

    /**
     * 创建联系人 Controller。
     *
     * @param contactQueryService 联系人查询服务
     * @param contactVerificationService 本机联系人验证服务
     * @param contactUnbindService 联系人解绑服务
     * @param contactAliasEnrollmentService 联系人称呼注册服务
     * @param contactAliasDeleteService 联系人称呼删除服务
     */
    public ContactsController(
            ContactQueryService contactQueryService,
            ContactVerificationService contactVerificationService,
            ContactUnbindService contactUnbindService,
            ContactAliasEnrollmentService contactAliasEnrollmentService,
            ContactAliasDeleteService contactAliasDeleteService) {
        this.contactQueryService = contactQueryService;
        this.contactVerificationService = contactVerificationService;
        this.contactUnbindService = contactUnbindService;
        this.contactAliasEnrollmentService = contactAliasEnrollmentService;
        this.contactAliasDeleteService = contactAliasDeleteService;
    }

    /**
     * 分页查询当前已认证 owner 的联系人。
     *
     * @param jwt 已验证 JWT
     * @param page 页码，从 0 开始
     * @param size 每页数量，1—20
     * @param status 可选状态过滤
     * @return 当前 owner 的联系人分页结果
     */
    @GetMapping
    public ApiResponse<ContactPageResp> listContacts(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(20) int size,
            @RequestParam(required = false) ContactStatus status) {
        CurrentUser currentUser = CurrentUser.from(jwt);
        ContactSummaryPage result = contactQueryService.list(
                currentUser.id(), status, page, size);
        List<ContactResp> items = result.items().stream().map(this::toResponse).toList();
        return ApiResponse.success(new ContactPageResp(
                items,
                new PageMetadataResp(
                        result.page(),
                        result.size(),
                        result.totalElements(),
                        result.totalPages())));
    }

    /**
     * 完成当前已认证 owner 手机上的微信联系人本机验证。
     *
     * @param jwt 已验证 JWT
     * @param id ct_ 前缀联系人编号
     * @param idempotencyKey 本次验证幂等键
     * @param request 最小页面验证证据
     * @return 验证后进入 ACTIVE_NO_ALIAS 的联系人
     */
    @PostMapping("/{id}/local-verifications")
    public ApiResponse<ContactResp> verifyLocalWechatContact(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String id,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey,
            @Valid @RequestBody LocalVerificationReq request) {
        CurrentUser currentUser = CurrentUser.from(jwt);
        ContactSummary summary = contactVerificationService.verify(
                currentUser.id(), id, idempotencyKey,
                new LocalVerificationCommand(
                        request.stableLocator(), request.currentRemark(), request.pageType(),
                        request.friendConfirmed(), request.locatorObservationCount(),
                        request.locatorUnique(), request.wechatVersion(), request.ruleVersion(),
                        request.verifiedAt(), request.expectedContactVersion()));
        return ApiResponse.success(toResponse(summary));
    }

    /**
     * 使用两遍已校验录音创建当前 owner 的联系人方言称呼。
     *
     * @param jwt 已验证 JWT
     * @param id ct_ 前缀联系人编号
     * @param idempotencyKey 创建幂等键
     * @param request 两遍音频、展示文字和联系人版本
     * @return 创建后的称呼最小结果
     */
    @PostMapping("/{id}/aliases")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ContactAliasResp> createContactAlias(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String id,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey,
            @Valid @RequestBody CreateContactAliasReq request) {
        CurrentUser currentUser = CurrentUser.from(jwt);
        ContactAliasSummary summary = contactAliasEnrollmentService.create(
                currentUser.id(), id, idempotencyKey,
                new CreateContactAliasCommand(
                        request.displayText(), request.phoneticHint(),
                        request.firstAudioObjectId(), request.secondAudioObjectId(),
                        request.expectedContactVersion(), request.confirmed()));
        return ApiResponse.success(toAliasResponse(summary));
    }

    /**
     * 删除当前 owner 指定联系人的一个方言称呼。
     *
     * @param jwt 已验证 JWT
     * @param id ct_ 前缀联系人编号
     * @param aliasId al_ 前缀称呼编号
     * @param idempotencyKey 删除幂等键
     * @param request 二次确认与联系人版本
     * @return 删除后的联系人及剩余称呼
     */
    @DeleteMapping("/{id}/aliases/{aliasId}")
    public ApiResponse<ContactResp> deleteContactAlias(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String id,
            @PathVariable String aliasId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey,
            @Valid @RequestBody DeleteContactAliasReq request) {
        CurrentUser currentUser = CurrentUser.from(jwt);
        ContactSummary summary = contactAliasDeleteService.delete(
                currentUser.id(), id, aliasId, idempotencyKey,
                new DeleteContactAliasCommand(
                        request.confirmed(), request.expectedContactVersion()));
        return ApiResponse.success(toResponse(summary));
    }

    /**
     * 解除当前已认证 owner 的联系人绑定。
     *
     * @param jwt 已验证 JWT
     * @param id ct_ 前缀联系人编号
     * @param idempotencyKey 本次解绑幂等键
     * @param request 二次确认与联系人版本
     * @return 状态为 REVOKED 的联系人最小结果
     */
    @DeleteMapping("/{id}")
    public ApiResponse<ContactResp> unbindContact(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String id,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey,
            @Valid @RequestBody ContactUnbindReq request) {
        CurrentUser currentUser = CurrentUser.from(jwt);
        ContactSummary summary = contactUnbindService.unbind(
                currentUser.id(), id, idempotencyKey,
                new ContactUnbindCommand(
                        request.confirmed(), request.expectedContactVersion()));
        return ApiResponse.success(toResponse(summary));
    }

    private ContactResp toResponse(ContactSummary summary) {
        return new ContactResp(
                summary.id(),
                summary.displayName(),
                summary.remark(),
                summary.avatarUrl(),
                summary.relationship(),
                summary.status(),
                summary.aliasCount(),
                summary.aliases().stream().map(this::toAliasResponse).toList(),
                summary.localVerificationVersion(),
                summary.verifiedAt(),
                summary.version(),
                summary.createdAt(),
                summary.updatedAt());
    }

    private ContactAliasResp toAliasResponse(ContactAliasSummary summary) {
        return new ContactAliasResp(
                summary.id(), summary.displayText(), summary.dialectCode(),
                summary.dialectPackageVersion(), summary.modelVersion(),
                summary.thresholdVersion(), summary.compatibility(), summary.createdAt());
    }
}
