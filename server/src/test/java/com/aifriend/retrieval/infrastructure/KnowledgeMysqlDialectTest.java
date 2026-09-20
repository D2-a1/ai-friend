package com.aifriend.retrieval.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 仅作已发现SQL方言错误的源码回归，不能替代真实MySQL执行。 */
class KnowledgeMysqlDialectTest {
    @Test void everyNewKnowledgeSqlUsesMysqlBinaryUuidFunction() throws Exception {
        Path root = Path.of("src/main/java/com/aifriend");
        for (String area : List.of("retrieval", "knowledge")) {
            try (var files = Files.walk(root.resolve(area))) {
                for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    assertThat(Files.readString(file)).as(file.toString()).doesNotContain("UUID_FROM_BIN(");
                }
            }
        }
        assertThat(Files.readString(root.resolve("contact/infrastructure/ContactGraphSourceAdapter.java")))
                .contains("BIN_TO_UUID(").doesNotContain("UUID_FROM_BIN(");
    }
}
