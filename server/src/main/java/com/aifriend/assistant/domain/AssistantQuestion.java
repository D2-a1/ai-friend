package com.aifriend.assistant.domain;

import java.util.Objects;
import java.util.UUID;
import com.aifriend.assistant.domain.AssistantAnswer.Purpose;
import com.aifriend.knowledge.domain.GraphQueryType;
import com.aifriend.retrieval.domain.KnowledgeText;

/**
 * 已校验的问题载荷；不包含owner、模型许可或执行权限，owner须来自认证主体。
 * @param expectedVersion 原始请求版本，幂等重试不得改写
 * @param requestKey 原始幂等键，持久化时使用带命名空间的HMAC
 * @param locale 明确语言
 * @param appVersionCode 应用版本
 * @param payload 严格区分公开文本和私人查询
 * @author Codex
 * @since 1.0.0
 */
public record AssistantQuestion(long expectedVersion, String requestKey, String locale, int appVersionCode, Payload payload) {
    /** 校验长度与联合类型，空白问题保留给澄清而不是猜测任务。 */
    public AssistantQuestion {
        Objects.requireNonNull(payload);
        requireKey(requestKey);
        if (expectedVersion < 0 || expectedVersion > Long.MAX_VALUE - 2 || appVersionCode < 1
                || locale == null || locale.length() > 35
                || !locale.matches("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8}){0,4}")) {
            throw new IllegalArgumentException("INVALID_ASSISTANT_QUESTION");
        }
    }
    /**
     * 校验创建/问题幂等键；不规范化大小写、空格或Unicode，以免不同键被合并。
     * @param key 原始键
     * @return 原始键
     */
    public static String requireKey(String key) {
        KnowledgeText.require(key, 128, 512, false);
        if (key.codePointCount(0, key.length()) < 16) { throw new IllegalArgumentException("INVALID_REQUEST_KEY"); }
        return key;
    }
    /** 封闭输入联合类型，图谱输入不得当成公开文本传给模型。 */
    public sealed interface Payload permits PublicText, PrivateGraph {
        /**
         * 声明问题载荷的唯一用途，禁止跨用途混用。
         * @return 唯一适用会话用途
         */
        Purpose purpose();
    }
    /**
     * 公开知识文本，不自动压缩或规范化正文。
     * @param text 1至500码点
     */
    public record PublicText(String text) implements Payload {
        /** 空白和标点可进入后续澄清；零长度及非法Unicode拒绝。 */
        public PublicText {
            KnowledgeText.require(text, 500, 2000, true);
            if (text.isEmpty()) { throw new IllegalArgumentException("EMPTY_INPUT"); }
        }
        @Override public Purpose purpose() { return Purpose.PUBLIC_KNOWLEDGE; }
        @Override public String toString() { return "PublicQuestion[redacted]"; }
    }
    /**
     * 私人有限查询，不携带其他账号或任意遍历表达式。
     * @param type 查询类型
     * @param contactId 仅LIST_ALIASES可用
     * @param aliasText 仅FIND_CONTACT_BY_ALIAS可用
     */
    public record PrivateGraph(GraphQueryType type, UUID contactId, String aliasText) implements Payload {
        /** 按类型校验唯一参数组合。 */
        public PrivateGraph {
            Objects.requireNonNull(type);
            boolean valid = switch (type) {
                case LIST_CONTACTS -> contactId == null && aliasText == null;
                case LIST_ALIASES -> contactId != null && aliasText == null;
                case FIND_CONTACT_BY_ALIAS -> contactId == null && aliasText != null;
            };
            if (!valid) { throw new IllegalArgumentException("INVALID_GRAPH_QUERY"); }
            if (aliasText != null) { KnowledgeText.require(aliasText, 100, 400, false); }
        }
        @Override public Purpose purpose() { return Purpose.CONTACT_GRAPH; }
        @Override public String toString() { return "PrivateQuestion[type=" + type + ", redacted]"; }
    }
    @Override public String toString() { return "AssistantQuestion[purpose=" + payload.purpose() + ", redacted]"; }
}
