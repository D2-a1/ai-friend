package com.aifriend.assistant.application;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import com.aifriend.assistant.domain.*;

/**
 * 会话与请求账本的同事务存储；外部模型/检索必须在admit返回后且execute=true时调用。
 * 本端口不验证答案语义或来源，读取结果只能交给独立绑定解密/授权/来源复验层。
 * @author Codex
 * @since 1.0.0
 */
public interface AssistantTurnRepository {
    /**
     * 先按同key查账再校验新请求版本；重复请求不增加问题次数、不续租。
     * @param owner 认证主体
     * @param sessionId 已认证会话
     * @param question 原始请求
     * @return 仅首次成功持久受理返回execute=true
     */
    Receipt admit(UUID owner, UUID sessionId, AssistantQuestion question);
    /**
     * 返回原请求状态供重放复验；查状态绝不重新调用模型。
     * @param owner 认证主体
     * @param sessionId 会话
     * @param requestKey 原始键
     * @return 不存在则empty，超时PROCESSING在同事务变成EXPIRED
     */
    Optional<Receipt> findRequest(UUID owner, UUID sessionId, String requestKey);
    /**
     * 按内部历史引用读取原请求；与原key查询具有相同授权及超时约束，不对外接受任意owner。
     * @param owner 认证主体
     * @param sessionId 当前会话
     * @param requestId 会话密文中记录的内部引用
     * @return 不存在为empty，返回值永不授权重新执行
     */
    Optional<Receipt> findRequestById(UUID owner, UUID sessionId, UUID requestId);
    /**
     * 以原执行栅栏原子保存结果和上下文；迟到结果不写入，转为过期终态。
     * @param owner 认证主体
     * @param sessionId 会话
     * @param requestId 已持久逻辑请求ID
     * @param admittedVersion 受理时版本
     * @param token 原始执行栅栏
     * @param encryptedResult 由上层完成来源/同意复验及绑定加密的结果，不接受原始模型响应
     * @param history 可选的本次安全摘要，只能引用当前request及完成版本
     * @return 当前存储结果；重复完成保留原结果，仍必须重放复验才可返回用户
     */
    Receipt complete(UUID owner, UUID sessionId, UUID requestId, long admittedVersion, UUID token,
            byte[] encryptedResult, Optional<AssistantConversation.Turn> history);
    /**
     * 持久结果/待执行资格，不包含任何联系授权。
     * @param session 当前会话版本
     * @param request 当前请求状态
     * @param execute 仅新受理且事务已成功提交时为true
     */
    record Receipt(AssistantSession session, AssistantTurnRequest request, boolean execute) {
        /** 请求归属必须对应会话；execute不是可重用的长期许可。 */
        public Receipt {
            Objects.requireNonNull(session); Objects.requireNonNull(request);
            if (!session.id().equals(request.sessionId()) || !session.owner().equals(request.owner())
                    || session.purpose()!=request.purpose() || request.admittedVersion()>session.version()
                    || (execute && request.state()!=AssistantTurnRequest.State.PROCESSING)) {
                throw new IllegalArgumentException("INVALID_TURN_RECEIPT");
            }
        }
        @Override public String toString() { return "AssistantTurnReceipt[state="+request.state()+", execute="+execute+"]"; }
    }
}
