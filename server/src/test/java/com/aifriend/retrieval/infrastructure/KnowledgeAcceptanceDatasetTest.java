package com.aifriend.retrieval.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** 冻结输入与标注的完整性门禁；不代表60条场景执行通过，更不是模型质量评测。 */
class KnowledgeAcceptanceDatasetTest {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    @Test void frozenInputsMustRetainPreExecutionDigests() throws Exception {
        assertThat(digest("corpus-v1.jsonl"))
                .isEqualTo("0d890c3fda1545c7da5276e7af0fc37dd975c75237f028d9c575e9177e726784");
        assertThat(digest("cases-v1.jsonl"))
                .isEqualTo("e2a775e5b7d47dbb84b1c9a03248c710ff9df7a949528404b09f998d2f8cab6b");
    }

    @Test void sixtyCasesMustKeepIndependentCategoriesAndExplicitOracles() throws Exception {
        var rows = rows("cases-v1.jsonl");
        assertThat(rows).hasSize(60);
        var ids = new HashSet<String>();
        var counts = new HashMap<String, Integer>();
        for (var row : rows) {
            var fields = new HashSet<String>();
            row.fieldNames().forEachRemaining(fields::add);
            assertThat(fields).containsAll(Set.of("id", "category", "scenario", "input", "expected"))
                    .isSubsetOf(Set.of("id", "category", "scenario", "input", "expected", "evidenceSource", "requiredFact"));
            fields.forEach(field -> assertThat(row.get(field).isTextual()).isTrue());
            fields.forEach(field -> assertThat(row.get(field).asText()).isNotBlank());
            assertThat(ids.add(row.get("id").asText())).isTrue();
            counts.merge(row.get("category").asText(), 1, Integer::sum);
        }
        assertThat(counts).isEqualTo(Map.of("ANSWERABLE", 20, "NO_EVIDENCE", 8,
                "LIFECYCLE", 8, "GRAPH", 8, "SECURITY", 10, "FAULT", 6));
    }

    @Test void answerableOraclesMustReferenceSyntheticCorpusFacts() throws Exception {
        var corpus = rows("corpus-v1.jsonl");
        assertThat(corpus).hasSize(10);
        var documents = new HashMap<String, JsonNode>();
        for (var document : corpus) {
            assertThat(document.get("synthetic").asBoolean()).isTrue();
            assertThat(document.get("locale").asText()).isEqualTo("zh-CN");
            assertThat(document.get("minAppVersion").asInt()).isEqualTo(1);
            assertThat(document.get("maxAppVersion").asInt()).isEqualTo(10);
            assertThat(documents.put(document.get("id").asText(), document)).isNull();
        }
        for (var row : rows("cases-v1.jsonl")) {
            if (!row.get("category").asText().equals("ANSWERABLE")) continue;
            var document = documents.get(row.path("evidenceSource").asText());
            assertThat(document).as(row.get("id").asText()).isNotNull();
            assertThat(document.get("text").asText()).contains(row.path("requiredFact").asText());
        }
    }

    private static List<JsonNode> rows(String name) throws IOException {
        var result = new ArrayList<JsonNode>();
        for (var line : new String(bytes(name), StandardCharsets.UTF_8).lines().toList()) {
            assertThat(line).isNotBlank();
            var row = JSON.readTree(line);
            assertThat(row.isObject()).isTrue();
            result.add(row);
        }
        return List.copyOf(result);
    }

    private static byte[] bytes(String name) throws IOException {
        try (var stream = KnowledgeAcceptanceDatasetTest.class.getResourceAsStream("/knowledge-acceptance/" + name)) {
            if (stream == null) throw new IOException("Missing frozen acceptance resource");
            return stream.readAllBytes();
        }
    }

    private static String digest(String name) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes(name)));
    }
}
