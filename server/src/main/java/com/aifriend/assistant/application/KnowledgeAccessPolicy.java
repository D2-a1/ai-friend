package com.aifriend.assistant.application;

import java.util.Objects;
import java.util.UUID;

import com.aifriend.consent.application.ConsentGrantQueryPort;
import com.aifriend.consent.domain.ConsentType;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;

/**
 * 新问答的独立用途授权；不授予任何联系任务或管理权限。
 *
 * @author codex
 * @since 1.0.0
 */
public final class KnowledgeAccessPolicy {

    /** 外部问答处理政策版本。 */
    public static final String MODEL_POLICY = "knowledge-model-v1";
    /** 私人关系查询政策版本。 */
    public static final String GRAPH_POLICY = "contact-graph-v1";
    /** 既有授权权威查询端口。 */
    private final ConsentGrantQueryPort consents;

    /**
     * 创建用途策略。
     * @param consents 授权查询端口
     */
    public KnowledgeAccessPolicy(ConsentGrantQueryPort consents) {
        this.consents = Objects.requireNonNull(consents);
    }

    /**
     * 复验外部问答处理授权，不继承任务音频或记忆授权。
     * @param owner 当前已认证主体
     * @throws BusinessException 当主体或当前政策授权缺失时抛出
     */
    public void requireExternalModelConsent(UUID owner) {
        require(owner, ConsentType.KNOWLEDGE_MODEL, MODEL_POLICY);
    }

    /**
     * 复验本地关系查询授权；本授权不允许外发图谱。
     * @param owner 当前已认证主体
     * @throws BusinessException 当主体或当前政策授权缺失时抛出
     */
    public void requireGraphConsent(UUID owner) {
        require(owner, ConsentType.CONTACT_GRAPH, GRAPH_POLICY);
    }

    /** 校验确切用途和政策版本，不将基础设施异常解释为授权成功。 */
    private void require(UUID owner, ConsentType type, String policy) {
        if (owner == null) {
            throw new BusinessException(ErrorCode.AUTH_REQUIRED);
        }
        if (!consents.isGrantedForPolicy(owner, type, policy)) {
            throw new BusinessException(ErrorCode.CONSENT_REQUIRED);
        }
    }
}
