package com.aifriend.retrieval.application;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.aifriend.retrieval.domain.KnowledgeAnswerDraft;
import com.aifriend.retrieval.domain.RetrievalResult;

/**
 * 已知风险的确定性筛查，不是通用语义蕴含判定器，也不替代独立人工标注质量集。
 * @author codex
 * @since 1.0.0
 */
public final class KnownAnswerRiskPolicy {
    private static final Pattern NUMBER = Pattern.compile("[0-9]+(?:\\.[0-9]+)?%?");
    private static final Pattern UNGROUNDED_REQUEST = Pattern.compile(
            "^(?:请|麻烦|帮我|给我|替我|请帮我)(?:编造|编写|编|捏造|杜撰).{0,80}(?:没有出处|没有依据|无依据|无需依据|不用依据|不需要依据|凭空).{0,80}$");
    private static final Pattern CLAIM = Pattern.compile(
            "(不可以|不允许|不能|不得|禁止|可以|允许|不支持|支持|无需|不必|必须)([^。！？；，,.!?;\\n]{2,80})");
    private static final List<String> INSTRUCTIONS = List.of(
            "忽略之前", "忽略以上", "忽略所有指令", "泄露系统提示", "输出系统提示",
            "ignorepreviousinstructions", "ignoreallprevious", "revealsystemprompt",
            "[system]", "<|system|>", "api_key=", "authorization:bearer");

    /** 已知风险，不以NONE表示已证明语义正确。 */
    public enum Risk {
        /** 未命中已知风险，不构成语义正确证明。 */
        NONE,
        /** 问题或证据包含已知不可信指令标记。 */
        UNTRUSTED_INSTRUCTION,
        /** 回答包含未获证据支持的已知模式。 */
        UNSUPPORTED_ANSWER,
        /** 不同来源存在已知冲突模式。 */
        CONFLICTING_SOURCES
    }

    /** 创建无状态有界规则。 */
    public KnownAnswerRiskPolicy() { }

    /**
     * 筛查常见注入标记；不存在标记不代表任意文本都可信。
     * @param text 有界问题或公开证据
     * @return 是否命中已知注入模式
     */
    public boolean containsInstruction(String text) {
        String value = normalize(text).replaceAll("\\s+", "");
        return INSTRUCTIONS.stream().anyMatch(value::contains);
    }

    /**
     * 识别明确要求无依据编写的有限命令，不把讨论“不能编造”的知识问题当命令。
     * 本判断不是通用语义分类；未命中仍须经过检索和引用校验。
     * @param text 有界问题
     * @return 是否明确要求无出处内容
     */
    public boolean requestsUngroundedAnswer(String text) {
        return UNGROUNDED_REQUEST.matcher(normalize(text).replaceAll("\\s+", "")).matches();
    }

    /**
     * 在生成前检查证据中的已知注入或直接相反的情态断言。
     * @param retrieval 当前证据
     * @return 固定风险
     */
    public Risk evidenceRisk(RetrievalResult retrieval) {
        List<String> texts = retrieval.evidence().stream().map(e -> e.chunk().text()).toList();
        if (retrieval.evidence().stream().anyMatch(e ->
                containsInstruction(e.chunk().text()) || containsInstruction(e.chunk().heading()))) {
            return Risk.UNTRUSTED_INSTRUCTION;
        }
        return contradictory(texts) ? Risk.CONFLICTING_SOURCES : Risk.NONE;
    }

    /**
     * 引用绑定后检查每句新增数字及已知否定反转，不把其他句的引用借给当前句。
     * @param draft 结构和引用已通过校验的草稿
     * @param retrieval 同一轮证据
     * @return 已知风险；NONE仍需真实profile质量验收
     */
    public Risk answerRisk(KnowledgeAnswerDraft draft, RetrievalResult retrieval) {
        for (var sentence : draft.sentences()) {
            if (containsInstruction(sentence.text())) { return Risk.UNTRUSTED_INSTRUCTION; }
            List<String> sources = sentence.evidenceIds().stream()
                    .map(id -> retrieval.evidence().get(id.charAt(1) - '1').chunk().text()).toList();
            Set<String> sourceNumbers = new HashSet<>();
            sources.forEach(text -> sourceNumbers.addAll(numbers(text)));
            if (!sourceNumbers.containsAll(numbers(sentence.text()))) { return Risk.UNSUPPORTED_ANSWER; }
            var claims = new ArrayList<Claim>();
            sources.forEach(text -> claims.addAll(claims(text)));
            for (Claim answer : claims(sentence.text())) {
                if (claims.stream().anyMatch(source -> source.opposes(answer))) {
                    return Risk.UNSUPPORTED_ANSWER;
                }
            }
        }
        return Risk.NONE;
    }

    private static Set<String> numbers(String text) {
        var result = new HashSet<String>();
        var matcher = NUMBER.matcher(normalize(text));
        while (matcher.find()) { result.add(matcher.group()); }
        return result;
    }

    private static boolean contradictory(List<String> texts) {
        var all = new ArrayList<Claim>();
        texts.forEach(text -> all.addAll(claims(text)));
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                if (all.get(i).opposes(all.get(j))) { return true; }
            }
        }
        return false;
    }

    private static List<Claim> claims(String text) {
        var result = new ArrayList<Claim>();
        var matcher = CLAIM.matcher(normalize(text));
        while (matcher.find()) {
            String cue = matcher.group(1);
            String family = switch (cue) {
                case "不支持", "支持" -> "support";
                case "无需", "不必", "必须" -> "require";
                default -> "permission";
            };
            boolean positive = Set.of("可以", "允许", "支持", "必须").contains(cue);
            result.add(new Claim(family, matcher.group(2).strip(), positive));
        }
        return result;
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private record Claim(String family, String predicate, boolean positive) {
        boolean opposes(Claim other) {
            return family.equals(other.family) && predicate.equals(other.predicate) && positive != other.positive;
        }
    }
}
