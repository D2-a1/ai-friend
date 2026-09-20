package com.aifriend.assistant.application;

import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import com.aifriend.assistant.domain.*;
import com.aifriend.assistant.domain.AssistantAnswer.*;
import com.aifriend.knowledge.application.GraphQueryPort;
import com.aifriend.knowledge.application.GraphSourceException;
import com.aifriend.knowledge.application.GraphProjectionException;
import com.aifriend.knowledge.domain.GraphQueryType;
import com.aifriend.retrieval.domain.RetrievalQuery;

/**
 * 独立问答会话的同步处理编排。仅持久首次受理才处理，重放不重新调用生成器或图谱查询。
 * 数据库短事务在仓储内完成，检索/生成/加密不包围数据库事务；HTTP装配注入有界执行器。
 * @author Codex
 * @since 1.0.0
 */
public final class AssistantSessionService {
    private final AssistantTurnRepository requests;
    private final AssistantResultReader reader;
    private final AssistantResultProtectionPort protection;
    private final AssistantResultRevalidator revalidator;
    private final KnowledgeAnswerService knowledge;
    private final GraphQueryPort graph;
    private final Supplier<Optional<String>> generationProfile;
    private final java.util.function.LongSupplier ticker;
    private final boolean externalProcessing;
    private final AssistantExecutionPort execution;

    /**
     * 配置来源为服务端依赖，不能直接绑定用户请求；构造没有DB或外部调用。
     * @param requests 原子受理/提交仓储
     * @param reader 安全结果与历史读取
     * @param protection 请求绑定加密
     * @param revalidator 返回与提交前权威复验
     * @param knowledge 公开知识服务
     * @param graph 私人有限查询
     * @param generationProfile 本地生成profile读取，不得在此联网
     * @param clock UTC时钟；最终期限仍由仓储DB时间验证
     * @param externalProcessing 服务端固定外部处理模式，仍必须获得独立用途同意
     */
    public AssistantSessionService(AssistantTurnRepository requests,AssistantResultReader reader,
            AssistantResultProtectionPort protection,AssistantResultRevalidator revalidator,
            KnowledgeAnswerService knowledge,GraphQueryPort graph,Supplier<Optional<String>> generationProfile,
            Clock clock,boolean externalProcessing) {
        this(requests,reader,protection,revalidator,knowledge,graph,generationProfile,clock,externalProcessing,
                (deadline,operation)->operation.accept(()->{}));
    }
    /**
     * 显式执行边界；生产装配须提供有界执行器，旧构造仅保留同步嵌入/离线测试。
     * @param requests 请求仓储
     * @param reader 安全读取
     * @param protection 加密
     * @param revalidator 来源复验
     * @param knowledge 公开问答
     * @param graph 私人查询
     * @param generationProfile 本地模型配置
     * @param clock UTC时钟
     * @param externalProcessing 是否允许外部处理，另验同意
     * @param execution 有界执行边界
     */
    public AssistantSessionService(AssistantTurnRepository requests,AssistantResultReader reader,
            AssistantResultProtectionPort protection,AssistantResultRevalidator revalidator,
            KnowledgeAnswerService knowledge,GraphQueryPort graph,Supplier<Optional<String>> generationProfile,
            Clock clock,boolean externalProcessing,AssistantExecutionPort execution) {
        this(requests,reader,protection,revalidator,knowledge,graph,generationProfile,clock,externalProcessing,execution,System::nanoTime);
    }
    AssistantSessionService(AssistantTurnRepository requests,AssistantResultReader reader,
            AssistantResultProtectionPort protection,AssistantResultRevalidator revalidator,
            KnowledgeAnswerService knowledge,GraphQueryPort graph,Supplier<Optional<String>> generationProfile,
            Clock clock,boolean externalProcessing,AssistantExecutionPort execution,java.util.function.LongSupplier ticker) {
        this.requests=Objects.requireNonNull(requests); this.reader=Objects.requireNonNull(reader);
        this.protection=Objects.requireNonNull(protection); this.revalidator=Objects.requireNonNull(revalidator);
        this.knowledge=Objects.requireNonNull(knowledge); this.graph=Objects.requireNonNull(graph);
        this.generationProfile=Objects.requireNonNull(generationProfile); Objects.requireNonNull(clock);
        this.ticker=Objects.requireNonNull(ticker);
        this.externalProcessing=externalProcessing;
        this.execution=Objects.requireNonNull(execution);
    }

    /**
     * 原问题幂等键始终不变，八秒截止来自首次持久受理，经过的时间不会因阶段切换而重置。
     * @param owner 认证主体
     * @param sessionId 独立会话
     * @param question 完整原始请求，重复时不可改写版本或正文
     * @return 仅安全读取器复验后的原请求状态/答案
     */
    public AssistantResultReader.Result ask(UUID owner,UUID sessionId,AssistantQuestion question) {
        Objects.requireNonNull(question);
        if(Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("ASSISTANT_CANCELLED");
        long beforeAdmission=ticker.getAsLong();
        var receipt=requests.admit(owner,sessionId,question);
        if (!receipt.execute()) return read(owner,sessionId,question);
        var request=receipt.request();
        var budget=new AssistantExecutionBudget(request,beforeAdmission,ticker);
        var deadline=new KnowledgeAnswerDeadline(budget);
        try {
            execution.executeBudget(request.deadline(),budget,control->process(owner,sessionId,question,receipt,deadline,control,budget));
        } catch(AssistantExecutionPort.Expired expired) {
            // 原key按DB截止收尾，不重新受理或调用处理器。
        }
        return read(owner,sessionId,question);
    }

    private void process(UUID owner,UUID sessionId,AssistantQuestion question,AssistantTurnRepository.Receipt receipt,
            KnowledgeAnswerDeadline deadline,AssistantExecutionPort.Control control,AssistantExecutionPort.Budget budget) {
        var request=receipt.request();
        Runnable check=()->{ control.check(); deadline.check(); };
        try {
            check.run();
            AssistantStoredResult result;
            String historyQuestion;
            AssistantConversation usedHistory=null;
            if(question.payload() instanceof AssistantQuestion.PublicText text) {
                historyQuestion=text.text();
                if(text.text().isBlank()) {
                    result=plain(Purpose.PUBLIC_KNOWLEDGE,Status.NEEDS_CLARIFICATION,AssistantReason.EMPTY_INPUT,
                            "请告诉我想了解小友的哪个功能。",question);
                } else {
                    var history=reader.publicQueryHistory(owner,sessionId,receipt.session().version(),
                            question.locale(),question.appVersionCode(),externalProcessing);
                    usedHistory=history;
                    check.run();
                    var profile=externalProcessing ? generationProfile.get() : Optional.<String>empty();
                    check.run();
                    var query=new RetrievalQuery(text.text(),question.locale(),question.appVersionCode(),4,
                            externalProcessing ? RetrievalQuery.ExternalProcessing.ALLOWED : RetrievalQuery.ExternalProcessing.LOCAL_ONLY);
                    var answered=knowledge.answerWithEvidence(new KnowledgeAnswerService.Request(owner,request.id(),query,request.deadline(),profile,history,Optional.of(budget)));
                    result=new AssistantStoredResult(answered.answer(),answered.evidenceVersion().orElse(null),answered.retrievalMode().orElse(null),
                            null,externalProcessing,profile.orElse(null),question.locale(),question.appVersionCode());
                }
            } else {
                var input=(AssistantQuestion.PrivateGraph)question.payload();
                historyQuestion=switch(input.type()) {
                    case LIST_CONTACTS -> "查看已绑定亲友";
                    case LIST_ALIASES -> "查看所选亲友称呼";
                    case FIND_CONTACT_BY_ALIAS -> "查找称呼："+input.aliasText();
                };
                // 私人查询是显式有限参数，不借历史推断目标，也不访问生成配置或公开端口。
                try {
                    var answer=graph.query(new GraphQueryPort.Query(owner,input.type(),Optional.ofNullable(input.contactId()),Optional.ofNullable(input.aliasText())));
                    result=graphResult(answer,input.type(),question);
                } catch(GraphSourceException failure) {
                    AssistantReason reason=switch(failure.kind()) {
                        case SOURCE_CHANGED -> AssistantReason.EVIDENCE_INVALIDATED;
                        case SOURCE_INVALID -> AssistantReason.SOURCE_INVALID;
                        case GRAPH_LIMIT -> AssistantReason.GRAPH_LIMIT;
                        case DECRYPTION_FAILED -> AssistantReason.DECRYPTION_FAILED;
                        case STORAGE_UNAVAILABLE -> AssistantReason.STORAGE_UNAVAILABLE;
                    };
                    result=plain(Purpose.CONTACT_GRAPH,Status.UNAVAILABLE,reason,"亲友信息暂时无法查询，请稍后重试。",question);
                } catch(GraphProjectionException failure) {
                    // 投影CAS冲突或提交未知也必须以原key收尾；不再写一次，不返回陈旧投影候选。
                    AssistantReason reason=switch(failure.kind()) {
                        case CONFLICT -> AssistantReason.VERSION_MISMATCH;
                        case INVALID -> AssistantReason.GRAPH_INVALID;
                        case STORAGE_UNAVAILABLE -> AssistantReason.STORAGE_UNAVAILABLE;
                    };
                    result=plain(Purpose.CONTACT_GRAPH,Status.UNAVAILABLE,reason,"亲友信息暂时无法查询，请稍后重试。",question);
                }
            }
            check.run(); revalidateHistory(owner,sessionId,receipt,question,usedHistory); check.run();
            revalidator.requireCurrent(owner,result); check.run();
            byte[] encrypted=protection.encrypt(receipt.session(),request,result);
            try {
                check.run();
                var history=result.answer().status()==Status.UNAVAILABLE || historyQuestion.isBlank()
                        ? Optional.<AssistantConversation.Turn>empty()
                        : Optional.of(new AssistantConversation.Turn(request.id(),request.admittedVersion()+1,
                                historyQuestion,AssistantResultReader.summary(result.answer())));
                // 加密期间也可能发生来源变化，写入前重新核对；实际CAS仍检查原版本/token/DB截止。
                revalidator.requireCurrent(owner,result); check.run();
                revalidateHistory(owner,sessionId,receipt,question,usedHistory); check.run();
                requests.complete(owner,sessionId,request.id(),request.admittedVersion(),request.leaseToken(),encrypted,history);
            } finally { Arrays.fill(encrypted,(byte)0); }
        } catch(KnowledgeAnswerDeadline.Expired expired) {
            // 不交付迟到答案、不重新生成；原key读取按DB时间收尾为EXPIRED或保持原在途状态。
        }
    }

    /** 历史不是新引用，但生成/加密等待期间其来源也可能被删除或撤权；提交前不得仅复验新答案。 */
    private void revalidateHistory(UUID owner,UUID sessionId,AssistantTurnRepository.Receipt receipt,
            AssistantQuestion question,AssistantConversation used) {
        if (used==null || used.turns().isEmpty()) return;
        var current=reader.publicQueryHistory(owner,sessionId,receipt.session().version(),
                question.locale(),question.appVersionCode(),externalProcessing);
        if (!used.equals(current)) throw new AssistantSessionException(AssistantReason.EVIDENCE_INVALIDATED);
    }

    private AssistantResultReader.Result read(UUID owner,UUID sessionId,AssistantQuestion question) {
        return reader.read(owner,sessionId,question.requestKey()).orElseThrow(()->new AssistantSessionException(AssistantReason.RESULT_STALE));
    }
    /** 固定安全提示没有来源内容，不把基础设施故障说成没有亲友。 */
    private static AssistantStoredResult plain(Purpose purpose,Status status,AssistantReason reason,String text,AssistantQuestion question) {
        return new AssistantStoredResult(new AssistantAnswer(purpose,status,Mode.NONE,reason,text,List.of(),List.of()),null,null,null,
                false,null,question.locale(),question.appVersionCode());
    }
    /** 候选列表完整保留；短播报只展示必要称呼，不拼接内部ID或选择执行对象。 */
    private static AssistantStoredResult graphResult(GraphQueryPort.Result result,GraphQueryType type,AssistantQuestion question) {
        Status status=result.candidates().isEmpty()?Status.NO_EVIDENCE:result.ambiguous()?Status.NEEDS_CLARIFICATION:Status.ANSWERED;
        Mode mode=result.candidates().isEmpty()?Mode.NONE:Mode.TEMPLATE;
        AssistantReason reason=result.candidates().isEmpty()?AssistantReason.NO_SUPPORT:result.ambiguous()?AssistantReason.AMBIGUOUS_CONTACT:AssistantReason.NONE;
        String text=result.candidates().isEmpty()?"没有找到符合条件的已绑定亲友。":
                (result.ambiguous()?"有多个同名称呼的亲友，请进一步区分：":"查询结果：")+
                result.candidates().stream().map(candidate -> type==GraphQueryType.LIST_ALIASES
                        ? String.join("、",candidate.aliases()) : candidate.aliases().stream().findFirst().orElse("未设置称呼"))
                        .collect(Collectors.joining("；"));
        return new AssistantStoredResult(new AssistantAnswer(Purpose.CONTACT_GRAPH,status,mode,reason,text,List.of(),result.candidates()),
                null,null,result.sourceDigest(),false,null,question.locale(),question.appVersionCode());
    }
}
