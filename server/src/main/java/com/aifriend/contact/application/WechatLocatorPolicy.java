package com.aifriend.contact.application;

import java.util.regex.Pattern;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;

/**
 * 微信号稳定定位的规范化与版本契约。
 *
 * <p>邀请网页提交的是用户可见微信号，不是 OAuth OpenID、昵称或微信备注。该类只做
 * 最小格式规范化，不记录或展示微信号。
 *
 * @author Codex
 * @since 1.0.0
 */
public final class WechatLocatorPolicy {

    /** 稳定定位 owner 范围 HMAC 的固定域。 */
    public static final String HMAC_DOMAIN = "wechat-locator-v1:";

    /** 亲友在邀请网页明确提交微信号的定位来源版本。 */
    public static final String INVITATION_WECHAT_ID_VERSION = "wechat-invitation-id-v1";

    private static final Pattern WECHAT_ID_PATTERN =
            Pattern.compile("[A-Za-z][A-Za-z0-9_-]{5,63}");

    private WechatLocatorPolicy() {
    }

    /**
     * 规范化亲友明确提交的微信号。
     *
     * @param rawWechatId HTTPS 请求体中的微信号
     * @return 去除首尾空白后的微信号
     * @throws BusinessException 当输入不是受支持的微信号格式时抛出
     */
    public static String normalizeInvitationWechatId(String rawWechatId) {
        String normalized = rawWechatId == null ? "" : rawWechatId.strip();
        if (!WECHAT_ID_PATTERN.matcher(normalized).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return normalized;
    }
}
