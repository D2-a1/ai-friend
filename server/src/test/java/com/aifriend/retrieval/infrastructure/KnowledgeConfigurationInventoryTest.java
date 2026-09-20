package com.aifriend.retrieval.infrastructure;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** 仓库配置交付一致性；只读取模板，不读取本机或云端实际env。 */
class KnowledgeConfigurationInventoryTest {
    @Test void everyKnowledgeEnvironmentBindingHasAnExplicitMatchingSafeTemplateDefault() throws Exception {
        var yaml = Files.readString(Path.of("src/main/resources/application.yml"));
        var matcher = Pattern.compile("\\$\\{(AI_FRIEND_KNOWLEDGE_[A-Z0-9_]+):([^}]*)}").matcher(yaml);
        var defaults = new TreeMap<String, String>();
        while (matcher.find()) {
            String previous = defaults.put(matcher.group(1), matcher.group(2));
            if (previous != null) { assertThat(previous).isEqualTo(matcher.group(2)); }
        }
        var entries = new TreeMap<String, String>();
        for (String line : Files.readAllLines(Path.of("server.env.example"))) {
            if (!line.startsWith("AI_FRIEND_KNOWLEDGE_")) { continue; }
            int separator = line.indexOf('=');
            assertThat(separator).isPositive();
            assertThat(entries.put(line.substring(0, separator), line.substring(separator + 1))).isNull();
        }
        assertThat(defaults).isNotEmpty();
        assertThat(entries).containsExactlyInAnyOrderEntriesOf(defaults);
    }

    @Test void templateCannotEnableNewFeaturesGrantBudgetOrCarryEvaluationSecrets() throws Exception {
        var template = Files.readString(Path.of("server.env.example"));
        assertThat(template).doesNotContain("AI_FRIEND_KNOWLEDGE_EVAL_");
        for (String line : template.lines().filter(value -> value.startsWith("AI_FRIEND_KNOWLEDGE_")).toList()) {
            var parts = line.split("=", 2);
            if (parts[0].endsWith("_ENABLED")) { assertThat(parts[1]).isEqualTo("false"); }
            if (parts[0].endsWith("_API_KEY")) { assertThat(parts[1]).isEmpty(); }
            if (parts[0].endsWith("_HOURLY_CALLS") || parts[0].endsWith("_DAILY_CALLS")) { assertThat(parts[1]).isEqualTo("0"); }
        }
    }
}
