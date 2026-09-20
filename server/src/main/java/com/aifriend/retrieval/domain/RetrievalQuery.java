package com.aifriend.retrieval.domain;

/**
 * 仅查询公开知识的请求，不携带联系人或执行权限。
 * @param text 问题文本
 * @param locale 明确语言
 * @param appVersionCode 应用版本
 * @param limit 最终证据数
 * @param externalProcessing 上层校验用途同意和持久费用预留后的许可，不接受客户端直接指定
 * @author codex
 * @since 1.0.0
 */
public record RetrievalQuery(String text, String locale, int appVersionCode, int limit, ExternalProcessing externalProcessing) {
    /** 对外处理范围。 */
    public enum ExternalProcessing {
        /** 默认不外发。 */ LOCAL_ONLY,
        /** 上层已通过独立同意与费用预留。 */ ALLOWED
    }

    /**
     * 兼容本地检索调用，默认永远不外发。
     * @param text 问题
     * @param locale 语言
     * @param appVersionCode 版本
     * @param limit 证据数量
     */
    public RetrievalQuery(String text, String locale, int appVersionCode, int limit) {
        this(text, locale, appVersionCode, limit, ExternalProcessing.LOCAL_ONLY);
    }
    /** 硬边界不接受任意遍历或无限候选。 */
    public RetrievalQuery {
        java.util.Objects.requireNonNull(externalProcessing, "externalProcessing");
        text = KnowledgeText.require(text, 500, 2000, false);
        if (locale == null || locale.length() > 35
                || !locale.matches("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8}){0,4}")
                || appVersionCode < 1 || limit < 1 || limit > 4) {
            throw new IllegalArgumentException("INVALID_RETRIEVAL_QUERY");
        }
    }

    /** 不把问题内容写入日志。 */
    @Override public String toString() { return "RetrievalQuery[text=redacted]"; }
}
