package com.aifriend.assistant.application;

import java.util.*;
import java.util.concurrent.CancellationException;
import com.aifriend.assistant.application.AssistantTurnRepository.Receipt;
import com.aifriend.assistant.domain.*;
import com.aifriend.assistant.domain.AssistantAnswer.Purpose;
import com.aifriend.assistant.domain.AssistantTurnRequest.State;

/**
 * 原请求结果重放和有界历史复用。解密、来源复验后重新读取权威会话/请求，禁止直接展示存储密文缓存。
 * 不调用模型、不重新受理请求、不延长任何期限，也不提供联系动作权限；HTTP入口仅映射受检结果。
 * @author Codex
 * @since 1.0.0
 */
public final class AssistantResultReader {
    private final AssistantSessionRepository sessions;
    private final AssistantTurnRepository requests;
    private final AssistantResultProtectionPort protection;
    private final AssistantResultRevalidator revalidator;
    private final KnowledgeAccessPolicy access;

    /**
     * 注入分离的短事务读取与结果复验，不用长事务包围来源或密码操作。
     * @param sessions ACTIVE主体及有效会话读取
     * @param requests 原请求账本及DB时间复核
     * @param protection 绑定结果解密
     * @param revalidator 当前来源/同意/profile复验
     * @param access 历史外发前的独立用途同意
     */
    public AssistantResultReader(AssistantSessionRepository sessions, AssistantTurnRepository requests,
            AssistantResultProtectionPort protection, AssistantResultRevalidator revalidator, KnowledgeAccessPolicy access) {
        this.sessions=Objects.requireNonNull(sessions); this.requests=Objects.requireNonNull(requests);
        this.protection=Objects.requireNonNull(protection); this.revalidator=Objects.requireNonNull(revalidator);
        this.access=Objects.requireNonNull(access);
    }

    /**
     * 查原key；处理中及失败终态不解密、不运行模型。只有完成结果通过全部检查才返回答案。
     * @param owner 认证主体
     * @param sessionId 会话ID
     * @param key 原请求键
     * @return 不存在为empty；不返回内部证明、密文或上下文
     */
    public Optional<Result> read(UUID owner, UUID sessionId, String key) {
        checkCancelled();
        var receipt=requests.findRequest(owner,sessionId,key);
        if (receipt.isEmpty()) return Optional.empty();
        var first=receipt.orElseThrow(); requireScope(owner,sessionId,first.session());
        var proof=first.request().state()==State.COMPLETED ? Optional.of(decode(first)) : Optional.<AssistantStoredResult>empty();
        requireUnchanged(first);
        proof.ifPresent(result->requireConsent(owner,result));
        return Optional.of(new Result(first.session().version(),first.request().id(),first.request().state(),
                first.request().resultVersion(),proof.map(AssistantStoredResult::answer),
                proof.map(AssistantStoredResult::retrievalMode).orElse(com.aifriend.retrieval.domain.RetrievalResult.Mode.NONE)));
    }

    /**
     * 读取当前版本全部历史；任一旧结果失效整批拒绝，不部分返回造成指代变化。
     * @param owner 认证主体
     * @param sessionId 会话ID
     * @param expectedVersion 调用方当前版本
     * @return 仅供当前用途的已复验历史，私人历史不得外发
     */
    public AssistantConversation history(UUID owner,UUID sessionId,long expectedVersion) {
        return history(owner,sessionId,expectedVersion,false);
    }

    /**
     * 唯一允许准备外部模型历史的入口；图谱用途在任何结果解密前拒绝。
     * @param owner 认证主体
     * @param sessionId 公开知识会话
     * @param expectedVersion 当前版本
     * @return 最多四条原问题/原答案短摘要，不创建外发的长期许可
     */
    public AssistantConversation publicModelHistory(UUID owner,UUID sessionId,long expectedVersion) {
        return history(owner,sessionId,expectedVersion,true);
    }

    /**
     * 当前公开问题专用历史；所有来源照常复验，语言/版本不一致时整批不复用，不能拼接部分历史改变指代。
     * @param owner 认证主体
     * @param sessionId 公开知识会话
     * @param expectedVersion 原受理版本
     * @param locale 当前问题语言
     * @param appVersionCode 当前应用版本
     * @param external 是否准备外发，仍须独立用途同意
     * @return 同范围已复验历史，范围变化或最近一轮未得到证据时为空
     */
    public AssistantConversation publicQueryHistory(UUID owner,UUID sessionId,long expectedVersion,
            String locale,int appVersionCode,boolean external) {
        Objects.requireNonNull(locale);
        if (appVersionCode < 1) throw new IllegalArgumentException("INVALID_APP_VERSION");
        return history(owner,sessionId,expectedVersion,external,new QueryScope(locale,appVersionCode));
    }

    /** 串联内部引用、绑定证明、原文短摘要、来源复验和最终权威状态检查。 */
    private AssistantConversation history(UUID owner,UUID sessionId,long version,boolean external) {
        return history(owner,sessionId,version,external,null);
    }

    private AssistantConversation history(UUID owner,UUID sessionId,long version,boolean external,QueryScope scope) {
        checkCancelled();
        var initial=sessions.find(owner,sessionId).orElseThrow(AssistantResultReader::stale);
        requireScope(owner,sessionId,initial);
        if (initial.version()!=version) throw stale();
        if (external || scope != null) {
            if (initial.purpose()!=Purpose.PUBLIC_KNOWLEDGE) throw new AssistantSessionException(AssistantReason.INVALID_REQUEST);
        }
        if (external) access.requireExternalModelConsent(owner);
        var checked=new ArrayList<Receipt>();
        var proofs=new ArrayList<AssistantStoredResult>();
        for (var turn:initial.conversation().turns()) {
            checkCancelled();
            var receipt=requests.findRequestById(owner,sessionId,turn.requestId()).orElseThrow(AssistantResultReader::stale);
            if (!initial.equals(receipt.session()) || !turn.requestId().equals(receipt.request().id())
                    || receipt.request().state()!=State.COMPLETED || !Objects.equals(turn.resultVersion(),receipt.request().resultVersion())) throw stale();
            var proof=decode(receipt);
            if (!turn.answerSummary().equals(summary(proof.answer()))) throw new AssistantSessionException(AssistantReason.EVIDENCE_INVALIDATED);
            checked.add(receipt);
            proofs.add(proof);
        }
        // DB时间、会话版本与请求状态在所有来源检查之后重新读取，不能重用初始快照。
        for (var receipt:checked) requireUnchanged(receipt);
        var latest=sessions.find(owner,sessionId).orElseThrow(AssistantResultReader::stale);
        if (!initial.equals(latest)) throw stale();
        proofs.forEach(proof->requireConsent(owner,proof));
        if (external) access.requireExternalModelConsent(owner);
        checkCancelled();
        if (scope != null && (proofs.stream().anyMatch(proof -> !scope.locale().equals(proof.locale())
                || scope.appVersionCode()!=proof.appVersionCode())
                || (!proofs.isEmpty() && proofs.get(proofs.size()-1).answer().status()!=AssistantAnswer.Status.ANSWERED
                    && proofs.get(proofs.size()-1).answer().status()!=AssistantAnswer.Status.EVIDENCE_ONLY))) {
            return new AssistantConversation(Purpose.PUBLIC_KNOWLEDGE,List.of());
        }
        return initial.conversation();
    }

    private record QueryScope(String locale,int appVersionCode) { }

    /**
     * 用原答案确定性截取摘要，不调用模型改写，不拆开Unicode码点。
     * @param answer 已复验原答案
     * @return 最多360码点；空答案不应写入历史
     */
    public static String summary(AssistantAnswer answer) {
        String text=Objects.requireNonNull(answer).text();
        int length=Math.min(360,text.codePointCount(0,text.length()));
        return text.substring(0,text.offsetByCodePoints(0,length));
    }

    /** 调用方密文副本在成功、失败和取消后均清理。 */
    private AssistantStoredResult decode(Receipt receipt) {
        checkCancelled(); byte[] cipher=receipt.request().encryptedResult();
        try {
            var result=protection.decrypt(receipt.session(),receipt.request(),cipher);
            if (result.answer().purpose()!=receipt.session().purpose()) throw stale();
            revalidator.requireCurrent(receipt.session().owner(),result);
            return result;
        } finally { if (cipher!=null) Arrays.fill(cipher,(byte)0); }
    }

    /** 返回前的持久层读取包含DB时间和ACTIVE/政策检查，版本改变则交由新查询处理。 */
    private void requireUnchanged(Receipt expected) {
        checkCancelled();
        var actual=requests.findRequestById(expected.session().owner(),expected.session().id(),expected.request().id())
                .orElseThrow(AssistantResultReader::stale);
        var a=expected.request(); var b=actual.request();
        byte[] ac=a.encryptedResult(),bc=b.encryptedResult();
        try {
            if (!expected.session().equals(actual.session()) || !a.id().equals(b.id()) || !a.owner().equals(b.owner())
                    || !a.sessionId().equals(b.sessionId()) || a.purpose()!=b.purpose() || !a.keyHash().equals(b.keyHash())
                    || !a.requestDigest().equals(b.requestDigest()) || a.admittedVersion()!=b.admittedVersion()
                    || !a.leaseToken().equals(b.leaseToken()) || !a.createdAt().equals(b.createdAt())
                    || !a.deadline().equals(b.deadline()) || !a.expiresAt().equals(b.expiresAt())
                    || a.state()!=b.state() || !Objects.equals(a.resultVersion(),b.resultVersion()) || !Arrays.equals(ac,bc)) throw stale();
        } finally { if(ac!=null) Arrays.fill(ac,(byte)0); if(bc!=null) Arrays.fill(bc,(byte)0); }
        checkCancelled();
    }
    /** 防御接口实现返回错误归属或终态会话；真实时间和政策仍由仓储权威读取负责。 */
    private static void requireScope(UUID owner,UUID sessionId,AssistantSession session) {
        if (!Objects.requireNonNull(owner).equals(session.owner()) || !Objects.requireNonNull(sessionId).equals(session.id())
                || session.state()!=AssistantSession.State.OPEN) throw stale();
    }
    private static AssistantSessionException stale() { return new AssistantSessionException(AssistantReason.RESULT_STALE); }
    /** 最后账本读取也可能等待，返回前再次确认独立用途同意。 */
    private void requireConsent(UUID owner,AssistantStoredResult result) {
        checkCancelled();
        if(result.answer().purpose()==Purpose.CONTACT_GRAPH) access.requireGraphConsent(owner);
        else if(result.externalProcessing()) access.requireExternalModelConsent(owner);
    }
    private static void checkCancelled() { if(Thread.currentThread().isInterrupted()) throw new CancellationException("ASSISTANT_CANCELLED"); }

    /**
     * 有限返回值，不携带存储证明或私人历史。
     * @param sessionVersion 当前会话版本
     * @param requestId 原请求ID
     * @param state 原请求状态
     * @param resultVersion 仅完成状态存在
     * @param answer 仅完成且所有复验通过时存在
     * @param retrievalMode 受检证明中的实际检索通道，无检索为NONE
     */
    public record Result(long sessionVersion,UUID requestId,State state,Long resultVersion,Optional<AssistantAnswer> answer,
            com.aifriend.retrieval.domain.RetrievalResult.Mode retrievalMode) {
        /**
         * 兼容不含检索的调用；生产读取使用完整构造。
         * @param sessionVersion 当前会话版本
         * @param requestId 原请求标识
         * @param state 原请求状态
         * @param resultVersion 完成结果版本，未完成时为空
         * @param answer 已完成且复验通过的回答，否则为空
         */
        public Result(long sessionVersion,UUID requestId,State state,Long resultVersion,Optional<AssistantAnswer> answer) {
            this(sessionVersion,requestId,state,resultVersion,answer,com.aifriend.retrieval.domain.RetrievalResult.Mode.NONE);
        }
        /** 完成与答案存在必须一致。 */
        public Result {
            Objects.requireNonNull(requestId); Objects.requireNonNull(state); Objects.requireNonNull(answer);
            Objects.requireNonNull(retrievalMode);
            if(sessionVersion<1 || (state==State.COMPLETED)!=answer.isPresent()
                    || (state==State.COMPLETED)!=(resultVersion!=null)) throw new IllegalArgumentException("INVALID_READ_RESULT");
        }
        @Override public String toString() { return "AssistantReadResult[state="+state+"]"; }
    }
}
