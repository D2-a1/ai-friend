package com.aifriend.identity.application;

import com.aifriend.identity.domain.WechatIdentity;

/**
 * 微信一次性 code 换取最小稳定主体的外部端口。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface WechatIdentityPort {

    /**
     * 消费一次性微信 code。
     *
     * @param code 微信一次性 code，不得记录或持久化
     * @return 最小微信主体
     */
    WechatIdentity exchange(String code);
}
