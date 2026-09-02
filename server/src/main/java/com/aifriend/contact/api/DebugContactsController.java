package com.aifriend.contact.api;

import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aifriend.contact.application.ContactAliasSummary;
import com.aifriend.contact.application.ContactSummary;
import com.aifriend.contact.application.DebugDemoContactService;
import com.aifriend.shared.api.ApiResponse;
import com.aifriend.shared.security.CurrentUser;

/**
 * 只在 dev/test 注册的 Debug 体验联系人 Controller。
 *
 * @author Codex
 * @since 1.0.0
 */
@Profile({"dev", "test"})
@RestController
@RequestMapping("/contacts")
public class DebugContactsController {

    private final DebugDemoContactService debugDemoContactService;

    /**
     * 创建 Debug 体验联系人 Controller。
     *
     * @param debugDemoContactService 体验联系人服务
     */
    public DebugContactsController(DebugDemoContactService debugDemoContactService) {
        this.debugDemoContactService = debugDemoContactService;
    }

    /**
     * 准备当前 owner 的唯一合成体验联系人。
     *
     * @param jwt 已验证 JWT
     * @return 可继续录入称呼的联系人
     */
    @PostMapping("/debug-demo")
    public ApiResponse<ContactResp> ensureDebugDemoContact(
            @AuthenticationPrincipal Jwt jwt) {
        ContactSummary summary = debugDemoContactService.ensure(CurrentUser.from(jwt).id());
        return ApiResponse.success(toResponse(summary));
    }

    private ContactResp toResponse(ContactSummary summary) {
        return new ContactResp(
                summary.id(), summary.displayName(), summary.remark(),
                summary.avatarUrl(), summary.relationship(), summary.status(),
                summary.aliasCount(), summary.aliases().stream()
                        .map(this::toAliasResponse).toList(),
                summary.localVerificationVersion(), summary.verifiedAt(),
                summary.version(), summary.createdAt(), summary.updatedAt());
    }

    private ContactAliasResp toAliasResponse(ContactAliasSummary summary) {
        return new ContactAliasResp(
                summary.id(), summary.displayText(), summary.dialectCode(),
                summary.dialectPackageVersion(), summary.modelVersion(),
                summary.thresholdVersion(), summary.compatibility(), summary.createdAt());
    }
}
