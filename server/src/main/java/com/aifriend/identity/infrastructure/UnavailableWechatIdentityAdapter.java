package com.aifriend.identity.infrastructure;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.aifriend.identity.application.WechatIdentityPort;
import com.aifriend.identity.domain.WechatIdentity;
import com.aifriend.shared.error.UpstreamFailureException;

/**
 * 生产环境真实微信适配器接入前的保守失败实现。
 *
 * <p>该实现不伪造登录成功、不调用未获授权的真实微信接口。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
@Profile("prod")
public class UnavailableWechatIdentityAdapter implements WechatIdentityPort {

    /**
     * 创建生产环境保守失败适配器。
     */
    public UnavailableWechatIdentityAdapter() {
    }

    /**
     * 始终保守失败，等待后续经用户确认接入真实微信服务。
     *
     * @param code 微信一次性 code
     * @return 不会返回
     * @throws UpstreamFailureException 始终抛出，表示真实微信身份服务尚未接入
     */
    @Override
    public WechatIdentity exchange(String code) {
        throw new UpstreamFailureException();
    }
}
