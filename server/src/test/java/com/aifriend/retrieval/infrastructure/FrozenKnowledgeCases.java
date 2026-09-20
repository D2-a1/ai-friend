package com.aifriend.retrieval.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 只读冻结场景加载器；场景由真实测试夹具执行，加载成功不代表功能通过。 */
public final class FrozenKnowledgeCases {
    private FrozenKnowledgeCases() { }

    /** @param categories 要执行的固定分类 @return 校验原始摘要后的不可变场景 */
    public static List<Scenario> load(Set<String> categories) throws Exception {
        try (var input=FrozenKnowledgeCases.class.getResourceAsStream("/knowledge-acceptance/cases-v1.jsonl")) {
            if(input==null) throw new IllegalStateException("Missing frozen cases");
            byte[] bytes=input.readAllBytes();
            String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if(!digest.equals("e2a775e5b7d47dbb84b1c9a03248c710ff9df7a949528404b09f998d2f8cab6b"))
                throw new IllegalStateException("Frozen cases changed before replay");
            var mapper=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            var result=new ArrayList<Scenario>();
            for(var line:new String(bytes,StandardCharsets.UTF_8).lines().toList()) {
                var value=mapper.readTree(line);
                if(categories.contains(value.get("category").asText())) result.add(new Scenario(
                        value.get("id").asText(),value.get("scenario").asText(),value.get("expected").asText(),value.get("input").asText()));
            }
            if(result.isEmpty()) throw new IllegalArgumentException("No frozen scenarios selected");
            return List.copyOf(result);
        }
    }

    /** @param ids 要执行的确切编号 @return 完整选择；缺失或重复均失败 */
    public static List<Scenario> loadIds(Set<String> ids) throws Exception {
        var selected=load(Set.of("ANSWERABLE","NO_EVIDENCE","LIFECYCLE","GRAPH","SECURITY","FAULT"))
                .stream().filter(row->ids.contains(row.id())).toList();
        if(selected.size()!=ids.size() || selected.stream().map(Scenario::id).distinct().count()!=ids.size())
            throw new IllegalArgumentException("Missing or duplicate frozen scenario");
        return selected;
    }

    /** @param id 固定编号 @param operation 操作场景 @param expected 冻结预期 @param input 冻结输入或场景描述 */
    public record Scenario(String id,String operation,String expected,String input) {
        @Override public String toString() { return id; }
    }
}
