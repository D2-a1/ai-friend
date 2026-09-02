package com.aifriend.invitation.api;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 同源亲友邀请页面 Controller。
 *
 * <p>页面是无服务端插值的固定资源，公开邀请编号和 fragment proof 只由浏览器脚本处理。
 * Controller 不读取 proof、OAuth code/state 或微信主体，避免敏感值进入模板、日志和缓存键。
 *
 * @author Codex
 * @since 1.0.0
 */
@Controller
public class InvitationWebController {

    private static final String REFERRER_POLICY = "Referrer-Policy";
    private static final Resource INVITATION_PAGE =
            new ClassPathResource("static/invite/invitation.html");

    /**
     * 创建同源亲友邀请页面 Controller。
     */
    public InvitationWebController() {
    }

    /**
     * 返回首次打开的邀请页。
     *
     * @param publicInvitationId 无权限公开邀请编号，只用于匹配页面路径
     * @return 禁止缓存的固定邀请页面
     */
    @GetMapping(value = "/invite/{publicInvitationId}", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public ResponseEntity<Resource> invitation(@PathVariable String publicInvitationId) {
        return invitationPage();
    }

    /**
     * 返回微信 OAuth 成功后的无敏感参数继续页。
     *
     * @return 禁止缓存的固定邀请页面
     */
    @GetMapping(value = "/invite/continue", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public ResponseEntity<Resource> continuation() {
        return invitationPage();
    }

    /**
     * 返回统一不可用页，不区分失败原因。
     *
     * @return 禁止缓存的固定邀请页面
     */
    @GetMapping(value = "/invite/unavailable", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public ResponseEntity<Resource> unavailable() {
        return invitationPage();
    }

    private ResponseEntity<Resource> invitationPage() {
        return ResponseEntity.ok()
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(REFERRER_POLICY, "no-referrer")
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.TEXT_HTML)
                .body(INVITATION_PAGE);
    }
}
