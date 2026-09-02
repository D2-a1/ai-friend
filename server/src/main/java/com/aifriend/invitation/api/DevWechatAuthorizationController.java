package com.aifriend.invitation.api;

import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * dev/test 环境本地微信授权承接页 Controller。
 *
 * <p>页面明确提示不会调用微信，只生成符合隔离格式的一次性本地 code，
 * 并回到现有 OAuth 回调验证链；prod profile 不注册本 Controller。
 *
 * @author Codex
 * @since 1.0.0
 */
@Controller
@Profile({"dev", "test"})
public class DevWechatAuthorizationController {

    private static final String REFERRER_POLICY = "Referrer-Policy";
    private static final Resource AUTHORIZATION_PAGE =
            new ClassPathResource("static/invite/dev-authorization.html");

    /**
     * 创建 dev/test 环境本地微信授权承接页 Controller。
     */
    public DevWechatAuthorizationController() {
    }

    /**
     * 返回本地授权说明和显式继续按钮。
     *
     * @return 禁止缓存的固定开发页
     */
    @GetMapping(value = "/dev/wechat-authorization", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public ResponseEntity<Resource> authorizeForLocalDevelopment() {
        return ResponseEntity.ok()
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(REFERRER_POLICY, "no-referrer")
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.TEXT_HTML)
                .body(AUTHORIZATION_PAGE);
    }
}
