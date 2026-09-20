package com.aifriend.assistant.domain;

import java.util.Objects;
import com.aifriend.assistant.domain.AssistantAnswer.Purpose;
import com.aifriend.assistant.domain.AssistantAnswer.Status;
import com.aifriend.assistant.domain.AssistantAnswer.Mode;
import com.aifriend.retrieval.domain.IndexVersion;
import com.aifriend.retrieval.domain.RetrievalResult;

/**
 * 待绑定加密的回答及重放复验证明；不是当前来源仍有效的证明，读取必须重新核对。
 * 不保存原录音或联系执行资格，私人关系不得出现公开索引或模型信息。
 * @param answer 有限业务答案
 * @param evidenceVersion 公开答案实际索引版本，无索引时null
 * @param retrievalMode 公开答案实际检索通道，与版本同时存在
 * @param graphSourceDigest 私人回答实际权威源摘要，无关系结果时null
 * @param externalProcessing 本请求是否允许外部处理，重放也须重新检查同意
 * @param generationProfile 本请求冻结的生成profile，未使用生成配置时null
 * @param locale 原请求语言
 * @param appVersionCode 原请求应用版本
 * @author Codex
 * @since 1.0.0
 */
public record AssistantStoredResult(AssistantAnswer answer,IndexVersion evidenceVersion,RetrievalResult.Mode retrievalMode,
        String graphSourceDigest,boolean externalProcessing,String generationProfile,String locale,int appVersionCode) {
    /** 不允许省略已有内容的来源，也不把PROCESSING存成已完成答案。 */
    public AssistantStoredResult {
        Objects.requireNonNull(answer);
        if ((evidenceVersion==null)!=(retrievalMode==null) || appVersionCode<1 || locale==null || locale.length()>35
                || !locale.matches("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8}){0,4}")
                || (generationProfile!=null && (!generationProfile.matches("[A-Za-z0-9._:-]{1,100}") || !externalProcessing))
                || (graphSourceDigest!=null && !graphSourceDigest.matches("[a-f0-9]{64}"))
                || answer.status()==Status.PROCESSING) { throw new IllegalArgumentException("INVALID_STORED_RESULT"); }
        if (answer.purpose()==Purpose.PUBLIC_KNOWLEDGE) {
            if (graphSourceDigest!=null || (!answer.citations().isEmpty() && evidenceVersion==null)
                    || (answer.mode()==Mode.GENERATED && (!externalProcessing || generationProfile==null))
                    || (answer.status()==Status.UNAVAILABLE && evidenceVersion!=null)) {
                throw new IllegalArgumentException("INVALID_KNOWLEDGE_PROOF");
            }
            if (evidenceVersion!=null) {
                // 重用检索结果的不变量，拒绝重复片段、混版本、假HYBRID和总片段预算溢出。
                new RetrievalResult(evidenceVersion,retrievalMode,answer.citations());
            }
        } else if (evidenceVersion!=null || externalProcessing || generationProfile!=null
                || ((answer.status()==Status.ANSWERED || !answer.candidates().isEmpty()) && graphSourceDigest==null)
                || (answer.status()==Status.UNAVAILABLE && graphSourceDigest!=null)) {
            throw new IllegalArgumentException("INVALID_GRAPH_PROOF");
        }
        var candidates=new java.util.HashSet<java.util.UUID>();
        for(var candidate:answer.candidates()) {
            if(!candidates.add(candidate.contactId())) throw new IllegalArgumentException("DUPLICATE_GRAPH_CANDIDATE");
        }
    }
    @Override public String toString() { return "AssistantStoredResult[purpose="+answer.purpose()+", status="+answer.status()+"]"; }
}
