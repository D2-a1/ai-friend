package com.aifriend.assistant.application;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import com.aifriend.assistant.domain.AssistantAnswer.Purpose;
import com.aifriend.assistant.domain.AssistantConversation;
import com.aifriend.retrieval.domain.RetrievalQuery;

/**
 * 有界、确定性的公开知识追问检索扩展，不是通用指代消解模型。
 * 只把最近一个未显式列举多对象的原问题接到有限追问前；摘要不能充当检索来源或新证据。
 * 裸指代、显式多对象、链中未解决指代及长度溢出均要求用户说出具体功能。
 */
final class PublicKnowledgeContext {
    private static final Pattern BARE = Pattern.compile("(?:这个|那个|这|那|它|该功能|这个功能|那个功能|it|this|that)(?:呢|啊|呀)?");
    private static final Pattern MULTIPLE = Pattern.compile("这[两几]个|那[两几]个|这些|那些|它们|前者|后者|第[一二三四]个|以及|或者|还是|同时|分别|和|与|、|，|,|；|;|(?i)\\b(and|or|both|former|latter|those|these)\\b");
    private static final Pattern FOLLOW = Pattern.compile(
            "(?:(?:这个功能|那个功能|该功能|这个|那个|它|那么|那))?"
            + "(?:(?:怎么|如何)(?:关闭|开启|打开|使用|用|设置|取消)|在哪里(?:设置|关闭|开启)|"
            + "需要联网吗|需要付费吗|收费吗|安全吗|有什么限制)"
            + "|(?:how (?:do i|can i) (?:use|enable|disable|configure) (?:it|this|that))");
    private static final Pattern UNRESOLVED = Pattern.compile(
            "(?:这个功能|那个功能|该功能|这个|那个|它|那么|那)(?:怎么|如何|在哪|需要|能|可|有|是|会|为).*"
            + "|(?:刚才|之前|前面)(?:说的|提到的)?那个(?:呢)?"
            + "|(?:can|does|is|will|should) (?:it|this|that)\\b.*");

    private PublicKnowledgeContext() { }

    /** empty表示缺少安全的检索主题；返回的新查询仍服从原语言、版本、限额及外发许可。 */
    static Optional<RetrievalQuery> resolve(RetrievalQuery current, AssistantConversation history) {
        if (history.purpose() != Purpose.PUBLIC_KNOWLEDGE) {
            throw new IllegalArgumentException("PRIVATE_CONTEXT_FORBIDDEN");
        }
        Kind kind = classify(current.text());
        if (kind == Kind.MISSING) { return Optional.empty(); }
        if (kind == Kind.DIRECT) { return Optional.of(current); }
        var parts = new ArrayList<String>();
        parts.add(current.text());
        for (int i = history.turns().size() - 1; i >= 0; i--) {
            String previous = history.turns().get(i).question();
            Kind previousKind = classify(previous);
            if (previousKind == Kind.MISSING || MULTIPLE.matcher(previous).find()) { return Optional.empty(); }
            parts.add(previous);
            if (previousKind == Kind.DIRECT) {
                Collections.reverse(parts);
                String expanded = String.join("\n", parts);
                if (expanded.codePointCount(0, expanded.length()) > 500
                        || expanded.getBytes(StandardCharsets.UTF_8).length > 2000) { return Optional.empty(); }
                return Optional.of(new RetrievalQuery(expanded, current.locale(), current.appVersionCode(),
                        current.limit(), current.externalProcessing()));
            }
        }
        return Optional.empty();
    }

    private static Kind classify(String text) {
        String value = text.strip().toLowerCase(Locale.ROOT).replaceAll("[？?。！!，,；;]+$", "").strip();
        if (BARE.matcher(value).matches() || value.matches("(?:这[两几]个|那[两几]个|这些|那些|它们|前者|后者|第[一二三四]个|those|these).*")) {
            return Kind.MISSING;
        }
        if (FOLLOW.matcher(value).matches()) { return Kind.FOLLOW; }
        // 明确含指代但不属于支持的有限追问时，不能当新主题交给检索/模型猜测。
        return UNRESOLVED.matcher(value).matches() ? Kind.MISSING : Kind.DIRECT;
    }

    private enum Kind { DIRECT, FOLLOW, MISSING }
}
