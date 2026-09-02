package com.aifriend.task.application;

/**
 * Android 与服务端共同使用的语义通话协议版本。
 *
 * <p>动作规则版本描述本次语义执行协议；定位规则版本描述联系人本机验证后保存的
 * 稳定定位格式。二者必须独立，不能从同一个客户端字段互相推导。
 *
 * @author Codex
 * @since 1.0.0
 */
public final class WechatSemanticCallContract {

    /** 语义语音/视频通话动作计划的固定规则版本。 */
    public static final String RULE_VERSION = "wechat-semantic-call-v1";

    /** 亲友在邀请网页明确提交微信号的固定定位版本。 */
    public static final String LOCATOR_VERSION = "wechat-invitation-id-v1";

    private WechatSemanticCallContract() {
    }
}
