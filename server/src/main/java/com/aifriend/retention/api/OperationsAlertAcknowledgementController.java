package com.aifriend.retention.api;

import static java.nio.charset.StandardCharsets.US_ASCII;

import java.util.Arrays;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aifriend.retention.application.AccountClosureAlertAcknowledgement;
import com.aifriend.retention.application.AccountClosureAlertAcknowledgementCommand;
import com.aifriend.retention.application.AccountClosureAlertAcknowledgementService;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.error.UpstreamFailureException;

/**
 * 注销 P0 告警浏览器接手确认入口。
 *
 * <p>该入口不接受普通用户 JWT 或客户端自报责任组，只把表单中的六位 TOTP
 * 交给独立运维身份端口。响应始终使用固定中文页面并禁止缓存和引用来源传播。</p>
 *
 * @author Codex
 * @since 1.0.0
 */
@RestController
@RequestMapping(
        "/operations/account-closure-alert-deliveries/{deliveryId}/acknowledgement")
public class OperationsAlertAcknowledgementController {

    /** 页面响应内容类型。 */
    private static final MediaType CHINESE_HTML =
            MediaType.parseMediaType("text/html;charset=UTF-8");
    /** 表单幂等键固定域。 */
    private static final String IDEMPOTENCY_DOMAIN = "operations-ack-v1:";
    /** 页面内容安全策略。 */
    private static final String CONTENT_SECURITY_POLICY =
            "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; "
                    + "base-uri 'none'; frame-ancestors 'none'";

    /** 接手确认应用服务。 */
    private final AccountClosureAlertAcknowledgementService acknowledgementService;
    /** 固定中文页面渲染器。 */
    private final OperationsAlertAcknowledgementPageRenderer pageRenderer;

    /**
     * 创建浏览器接手确认入口。
     *
     * @param acknowledgementService 接手确认应用服务
     * @param pageRenderer 固定中文页面渲染器
     */
    public OperationsAlertAcknowledgementController(
            AccountClosureAlertAcknowledgementService acknowledgementService,
            OperationsAlertAcknowledgementPageRenderer pageRenderer) {
        this.acknowledgementService = acknowledgementService;
        this.pageRenderer = pageRenderer;
    }

    /**
     * 显示固定中文动态验证码表单。
     *
     * @param deliveryId 随机告警投递 UUID
     * @return 禁止缓存的中文表单页
     */
    @GetMapping(produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> form(@PathVariable UUID deliveryId) {
        return html(HttpStatus.OK, pageRenderer.formPage());
    }

    /**
     * 使用六位动态验证码确认接手告警。
     *
     * @param deliveryId 随机告警投递 UUID
     * @param code 六位一次性动态验证码
     * @return 成功或不泄露内部细节的固定中文结果页
     */
    @PostMapping(
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> acknowledge(
            @PathVariable UUID deliveryId,
            @RequestParam(name = "code", defaultValue = "") String code) {
        if (!code.matches("[0-9]{6}")) {
            return html(HttpStatus.BAD_REQUEST, pageRenderer.failurePage(false));
        }
        byte[] credentialProof = code.getBytes(US_ASCII);
        try {
            AccountClosureAlertAcknowledgement acknowledgement =
                    acknowledgementService.acknowledge(
                            new AccountClosureAlertAcknowledgementCommand(
                                    deliveryId,
                                    IDEMPOTENCY_DOMAIN + deliveryId,
                                    credentialProof));
            return html(HttpStatus.OK, pageRenderer.successPage(acknowledgement));
        } catch (BusinessException exception) {
            HttpStatus status = exception.errorCode() == ErrorCode.RATE_LIMITED
                    ? HttpStatus.TOO_MANY_REQUESTS
                    : HttpStatus.BAD_REQUEST;
            return html(status, pageRenderer.failurePage(false));
        } catch (UpstreamFailureException exception) {
            return html(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    pageRenderer.failurePage(true));
        } catch (RuntimeException exception) {
            return html(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    pageRenderer.failurePage(true));
        } finally {
            Arrays.fill(credentialProof, (byte) 0);
        }
    }

    private ResponseEntity<String> html(HttpStatus status, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(CHINESE_HTML);
        headers.setCacheControl(CacheControl.noStore());
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Content-Security-Policy", CONTENT_SECURITY_POLICY);
        return new ResponseEntity<>(body, headers, status);
    }
}
