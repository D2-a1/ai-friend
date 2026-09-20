package com.aifriend.retrieval.infrastructure;

import java.util.ArrayList;
import java.util.Set;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.aifriend.retrieval.application.KnowledgeGatewayException;
import com.aifriend.retrieval.domain.KnowledgeAnswerDraft;

/**
 * 独立知识生成的严格Chat Completions响应解码；不恢复截断JSON、不剥Markdown、不造引用。
 * @author codex
 * @since 1.0.0
 */
public final class KnowledgeAnswerProtocol {
    private final ObjectMapper mapper;

    /**
     * 复制现有JSON配置并启用重复键及深度限制，不修改共享ObjectMapper。
     * @param mapper 基础JSON组件
     */
    public KnowledgeAnswerProtocol(ObjectMapper mapper) {
        this.mapper = java.util.Objects.requireNonNull(mapper, "mapper").copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(12).maxNumberLength(32).maxStringLength(32768).build());
    }

    /**
     * 完整响应必须只有一个正常结束、无工具调用的assistant choice。
     * @param response 完整响应字节，最大64KiB
     * @param expectedModel 已配置的准确模型名称
     * @return 严格草稿
     * @throws KnowledgeGatewayException 所有非法输出均为脱敏PROTOCOL
     */
    public KnowledgeAnswerDraft decode(byte[] response, String expectedModel) {
        if (response == null || response.length == 0 || response.length > 65536
                || expectedModel == null || expectedModel.isBlank()) { throw invalid(); }
        try (var parser = mapper.createParser(response)) {
            JsonNode root = mapper.readTree(parser);
            if (parser.nextToken() != null || !fields(root, Set.of("id", "object", "created", "model",
                    "choices", "usage", "system_fingerprint", "service_tier"), Set.of("model", "choices"))
                    || !root.get("model").isTextual() || !expectedModel.equals(root.get("model").textValue())
                    || (root.has("object") && !"chat.completion".equals(root.get("object").textValue()))) {
                throw invalid();
            }
            var choices = root.get("choices");
            if (!choices.isArray() || choices.size() != 1) { throw invalid(); }
            var choice = choices.get(0);
            if (!fields(choice, Set.of("index", "message", "finish_reason", "logprobs"), Set.of("index", "message", "finish_reason"))
                    || !choice.get("index").isIntegralNumber() || !choice.get("index").canConvertToInt()
                    || choice.get("index").intValue() != 0 || !"stop".equals(choice.get("finish_reason").textValue())) {
                throw invalid();
            }
            var message = choice.get("message");
            if (!fields(message, Set.of("role", "content", "refusal", "reasoning_content"), Set.of("role", "content"))
                    || !"assistant".equals(message.get("role").textValue()) || !message.get("content").isTextual()
                    || (message.has("refusal") && !message.get("refusal").isNull())
                    || (message.has("reasoning_content") && !message.get("reasoning_content").isNull()
                        && !message.get("reasoning_content").isTextual())) { throw invalid(); }
            String content = message.get("content").textValue();
            if (content.length() > 8192) { throw invalid(); }
            try (var inner = mapper.createParser(content)) {
                JsonNode answer = mapper.readTree(inner);
                if (inner.nextToken() != null || !fields(answer, Set.of("status", "sentences"), Set.of("status", "sentences"))
                        || !answer.get("status").isTextual() || !answer.get("sentences").isArray()
                        || answer.get("sentences").size() > 3) { throw invalid(); }
                var sentences = new ArrayList<KnowledgeAnswerDraft.Sentence>();
                for (var sentence : answer.get("sentences")) {
                    if (!fields(sentence, Set.of("text", "evidenceIds"), Set.of("text", "evidenceIds"))
                            || !sentence.get("text").isTextual() || !sentence.get("evidenceIds").isArray()
                            || sentence.get("evidenceIds").size() > 4) { throw invalid(); }
                    var ids = new ArrayList<String>();
                    for (var id : sentence.get("evidenceIds")) {
                        if (!id.isTextual()) { throw invalid(); }
                        ids.add(id.textValue());
                    }
                    sentences.add(new KnowledgeAnswerDraft.Sentence(sentence.get("text").textValue(), ids));
                }
                return new KnowledgeAnswerDraft(KnowledgeAnswerDraft.Status.valueOf(answer.get("status").textValue()), sentences);
            }
        } catch (KnowledgeGatewayException expected) { throw expected; }
        catch (Exception malformed) { throw invalid(); }
    }

    private static boolean fields(JsonNode node, Set<String> allowed, Set<String> required) {
        if (node == null || !node.isObject()) { return false; }
        var names = node.fieldNames();
        while (names.hasNext()) { if (!allowed.contains(names.next())) { return false; } }
        return required.stream().allMatch(node::has);
    }

    private static KnowledgeGatewayException invalid() {
        return new KnowledgeGatewayException(KnowledgeGatewayException.Kind.PROTOCOL);
    }
}
