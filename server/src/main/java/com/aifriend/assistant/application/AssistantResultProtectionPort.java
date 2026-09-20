package com.aifriend.assistant.application;

import com.aifriend.assistant.domain.*;

/**
 * 会话应用层的结果保护边界；绑定必须从已认证会话与持久请求推导。
 * @author Codex
 * @since 1.0.0
 */
public interface AssistantResultProtectionPort {
    /**
     * 加密待持久结果，不能替代当前来源与授权复验。
     * @param session 当前认证会话
     * @param request 持久请求
     * @param result 有限业务结果
     * @return 调用方负责清理的密文
     */
    byte[] encrypt(AssistantSession session, AssistantTurnRequest request, AssistantStoredResult result);
    /**
     * 解密并严格检查原请求绑定，不返回未通过绑定校验的内容。
     * @param session 当前认证会话
     * @param request 持久请求
     * @param encrypted 借用密文，不修改调用方数组
     * @return 尚须当前来源复验的结果
     */
    AssistantStoredResult decrypt(AssistantSession session, AssistantTurnRequest request, byte[] encrypted);
}
