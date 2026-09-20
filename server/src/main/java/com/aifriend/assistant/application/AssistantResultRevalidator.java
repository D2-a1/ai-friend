package com.aifriend.assistant.application;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import com.aifriend.assistant.domain.*;
import com.aifriend.assistant.domain.AssistantAnswer.Purpose;
import com.aifriend.assistant.domain.AssistantAnswer.Status;
import com.aifriend.knowledge.application.ContactGraphSourcePort;
import com.aifriend.knowledge.application.ContactGraphEvidencePort;
import com.aifriend.knowledge.domain.*;
import com.aifriend.retrieval.application.KnowledgeRepositoryPort;
import com.aifriend.retrieval.domain.RetrievalEvidence;

/**
 * 已绑定解密结果在提交、重放和历史复用前的权威来源复验。
 * 不提供执行权限，不调用模型，不开启包围多次来源读取的长事务；调用方另验会话期限及版本。
 * HTTP和会话提交共用此复验；来源端口异常转换为固定问答原因，不能回退旧缓存。
 * @author Codex
 * @since 1.0.0
 */
public final class AssistantResultRevalidator {
    private final KnowledgeRepositoryPort knowledge;
    private final ContactGraphSourcePort graph;
    private final KnowledgeAccessPolicy access;
    private final Supplier<Optional<String>> generationProfile;

    /**
     * 构造只读复验器。
     * @param knowledge 当前公开索引权威端口
     * @param graph 当前私人关系权威端口
     * @param access 独立用途同意
     * @param generationProfile 本地配置元数据读取，禁止在此发出网络请求
     */
    public AssistantResultRevalidator(KnowledgeRepositoryPort knowledge, ContactGraphSourcePort graph,
            KnowledgeAccessPolicy access, Supplier<Optional<String>> generationProfile) {
        this.knowledge=Objects.requireNonNull(knowledge); this.graph=Objects.requireNonNull(graph);
        this.access=Objects.requireNonNull(access); this.generationProfile=Objects.requireNonNull(generationProfile);
    }

    /**
     * 仅全部复验通过才正常返回；这不是无限期有效的许可。
     * @param owner 已认证且经会话仓储验证ACTIVE的主体
     * @param result 经过请求/会话绑定解密的结果，不接收客户端提供的证明
     */
    public void requireCurrent(UUID owner, AssistantStoredResult result) {
        Objects.requireNonNull(owner); Objects.requireNonNull(result); checkCancelled();
        try {
            if (result.answer().purpose()==Purpose.CONTACT_GRAPH) { requireGraph(owner,result); }
            else { requireKnowledge(owner,result); }
        } catch (com.aifriend.retrieval.application.KnowledgeRetrievalException failure) {
            throw new AssistantSessionException(switch(failure.kind()) {
                case NO_INDEX,SOURCE_CHANGED -> AssistantReason.EVIDENCE_INVALIDATED;
                case INDEX_INVALID -> AssistantReason.INDEX_INVALID;
            });
        } catch (com.aifriend.knowledge.application.GraphSourceException failure) {
            throw new AssistantSessionException(switch(failure.kind()) {
                case SOURCE_CHANGED -> AssistantReason.EVIDENCE_INVALIDATED;
                case SOURCE_INVALID -> AssistantReason.SOURCE_INVALID;
                case GRAPH_LIMIT -> AssistantReason.GRAPH_LIMIT;
                case DECRYPTION_FAILED -> AssistantReason.DECRYPTION_FAILED;
                case STORAGE_UNAVAILABLE -> AssistantReason.STORAGE_UNAVAILABLE;
            });
        }
        checkCancelled();
    }

    /** 公开证据必须仍是完整索引的原始片段，语言和版本适用范围不能靠旧缓存。 */
    private void requireKnowledge(UUID owner, AssistantStoredResult result) {
        requireModelScope(owner,result);
        if (result.evidenceVersion()!=null) {
            var chunks=result.answer().citations().stream().map(RetrievalEvidence::chunk).toList();
            if (knowledge instanceof com.aifriend.retrieval.application.KnowledgeEvidencePort scoped) {
                if (!scoped.isCurrentForScope(result.evidenceVersion(),chunks,result.locale(),result.appVersionCode()))
                    throw invalidEvidence();
                // 同意/profile仍在新鲜来源读取之后复验，不能把来源能力当作授权。
                requireModelScope(owner,result);
                return;
            }
            var current=knowledge.readActive().orElseThrow(AssistantResultRevalidator::invalidEvidence);
            if (!current.version().equals(result.evidenceVersion())) throw invalidEvidence();
            for (var chunk:chunks) {
                if (!current.chunks().contains(chunk) || current.documents().stream().noneMatch(document ->
                        document.id().equals(chunk.documentId()) && document.version()==chunk.documentVersion()
                        && document.appliesTo(result.locale(),result.appVersionCode()))) throw invalidEvidence();
            }
            checkCancelled();
            if (!knowledge.isCurrent(result.evidenceVersion(),chunks)) throw invalidEvidence();
        } else if (result.answer().status()==Status.NO_EVIDENCE && knowledge.readActive().isPresent()) {
            // 无索引回答也有失效条件：新索引发布后不能继续重放“没有资料”。
            throw invalidEvidence();
        }
        requireModelScope(owner,result);
    }

    /** 仅读取本地profile标识，不运行生成器；私人关系路径不会调用此方法。 */
    private void requireModelScope(UUID owner, AssistantStoredResult result) {
        checkCancelled();
        if (result.externalProcessing()) access.requireExternalModelConsent(owner);
        if (result.generationProfile()!=null && !Optional.of(result.generationProfile()).equals(generationProfile.get())) {
            throw new AssistantSessionException(AssistantReason.PROFILE_CHANGED);
        }
    }

    /** 先验同意再解密必要称呼，末尾再次验证来源和同意，绝不外发关系。 */
    private void requireGraph(UUID owner, AssistantStoredResult result) {
        access.requireGraphConsent(owner);
        if (result.graphSourceDigest()!=null) {
            var candidates=result.answer().candidates();
            var evidence=graphEvidence(owner,result);
            var before=evidence.before(); requireGraphScope(owner,result,before);
            if (!candidates.isEmpty()) {
                var current=evidence.displays();
                if (current.size()!=candidates.size() || !displayMap(current).equals(displayMap(candidates))) throw invalidEvidence();
                for (var candidate:current) {
                    var node=before.nodes().stream().filter(n -> n.type()==GraphNode.Type.CONTACT
                            && n.sourceId().equals(candidate.contactId()) && n.sourceVersion()==candidate.contactVersion())
                            .findFirst().orElseThrow(AssistantResultRevalidator::invalidEvidence);
                    long aliases=before.edges().stream().filter(e -> e.type()==GraphEdge.Type.HAS_ALIAS && e.fromId().equals(node.id())).count();
                    if (aliases!=candidate.aliases().size()) throw invalidEvidence();
                }
            }
            checkCancelled();
            var after=evidence.after(); requireGraphScope(owner,result,after);
            if (!facts(before).equals(facts(after))) throw invalidEvidence();
        } else if (result.answer().status()==Status.NO_EVIDENCE) {
            // 没有权威源摘要就无法证明“没有匹配亲友”仍然成立。
            throw invalidEvidence();
        }
        access.requireGraphConsent(owner);
    }

    /** 生产适配器复用本次显示内部的两次新事务事实；基础端口保留原始前后读取。 */
    private ContactGraphEvidencePort.Evidence graphEvidence(UUID owner, AssistantStoredResult result) {
        var ids=result.answer().candidates().stream().map(ContactDisplay::contactId).toList();
        if (!ids.isEmpty() && graph instanceof ContactGraphEvidencePort detailed) {
            return detailed.displayEvidence(owner,result.graphSourceDigest(),ids);
        }
        var before=graph.snapshot(owner);
        requireGraphScope(owner,result,before);
        // 空候选没有显示密文读取或解密窗口；一个新的权威快照即可复验原来源摘要。
        // 不复用查询/先前阶段的快照，外层仍在返回前再次检查用途同意。
        if (ids.isEmpty()) {
            checkCancelled();
            return new ContactGraphEvidencePort.Evidence(before,before,List.of());
        }
        var displays=graph.displayCurrent(owner,result.graphSourceDigest(),ids);
        checkCancelled();
        return new ContactGraphEvidencePort.Evidence(before,graph.snapshot(owner),displays);
    }

    /** 顺序变化不使相同候选失效；重复ID和缺失候选仍整批拒绝。 */
    private static Map<UUID,ContactDisplay> displayMap(List<ContactDisplay> displays) {
        var mapped=new HashMap<UUID,ContactDisplay>();
        for (var display:displays) {
            var sorted=new ContactDisplay(display.contactId(),display.contactVersion(),display.aliases().stream().sorted().toList());
            if (mapped.put(display.contactId(),sorted)!=null) throw invalidEvidence();
        }
        return mapped;
    }

    /** 对比来源事实而非投影生成ID，防止只比较相同摘要标签。 */
    private static Set<String> facts(GraphSnapshot snapshot) {
        var keys=snapshot.nodes().stream().collect(Collectors.toMap(GraphNode::id,n -> n.type()+":"+n.sourceId()));
        var facts=new HashSet<String>();
        snapshot.nodes().forEach(n -> facts.add("N:"+keys.get(n.id())+":"+n.sourceVersion()));
        snapshot.edges().forEach(e -> facts.add("E:"+e.type()+":"+keys.get(e.fromId())+":"+keys.get(e.toId())));
        return facts;
    }

    /** 主体与权威源摘要必须都匹配，不能跨账号复用。 */
    private static void requireGraphScope(UUID owner, AssistantStoredResult result, GraphSnapshot snapshot) {
        if (!owner.equals(snapshot.ownerUserId()) || !result.graphSourceDigest().equals(snapshot.sourceDigest())) throw invalidEvidence();
    }
    /** 固定错误不携带任何证据或称呼。 */
    private static AssistantSessionException invalidEvidence() { return new AssistantSessionException(AssistantReason.EVIDENCE_INVALIDATED); }
    /** 取消后不继续读取来源或复用结果，保留中断标记。 */
    private static void checkCancelled() { if (Thread.currentThread().isInterrupted()) throw new CancellationException("ASSISTANT_CANCELLED"); }
}
