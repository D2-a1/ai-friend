package com.aifriend.invitation.infrastructure;

import java.util.Base64;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.aifriend.invitation.application.WechatInvitationIdentity;
import com.aifriend.invitation.application.WechatInvitationIdentityPort;
import com.aifriend.shared.error.UpstreamFailureException;
import com.aifriend.shared.security.DigestService;

/**
 * dev/test 环境隔离的本地邀请 OAuth code 验证器。
 *
 * <p>只接受 {@code local_<subject>.<nonce>}，并在当前进程内保证一次性消费；
 * 不调用微信且不会在 prod profile 注册。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
@Profile({"dev", "test"})
public class LocalWechatInvitationIdentityAdapter implements WechatInvitationIdentityPort {

    private static final Pattern LOCAL_CODE = Pattern.compile(
            "^local_([A-Za-z0-9_-]{3,64})\\.([A-Za-z0-9_-]{8,128})$");

    /** 当前进程已消费本地 code 的 SHA-256 摘要集合。 */
    private final Set<String> consumedCodeDigests = ConcurrentHashMap.newKeySet();
    private final DigestService digestService;

    /**
     * 创建本地邀请身份验证器。
     *
     * @param digestService code 摘要服务
     */
    public LocalWechatInvitationIdentityAdapter(DigestService digestService) {
        this.digestService = digestService;
    }

    /**
     * 验证隔离的本地 code 并提取稳定测试主体。
     *
     * @param code 本地一次性测试 code，不得记录
     * @return 本地测试主体
     * @throws UpstreamFailureException 当 code 不是隔离格式时抛出
     */
    @Override
    public WechatInvitationIdentity exchangeCode(String code) {
        Matcher matcher = LOCAL_CODE.matcher(code == null ? "" : code);
        if (!matcher.matches()) {
            throw new UpstreamFailureException();
        }
        String codeDigest = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(digestService.sha256(code));
        if (!consumedCodeDigests.add(codeDigest)) {
            throw new UpstreamFailureException();
        }
        return new WechatInvitationIdentity(matcher.group(1));
    }
}
