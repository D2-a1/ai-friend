package com.aifriend.assistant.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KnowledgeContractGenerationTest {
    @TempDir Path output;

    @Test
    void generatedServerModelsAndInterfacesCompileForJava17() throws IOException {
        Path generated = Path.of("target/generated-sources/openapi/src/main/java");
        assertThat(generated.resolve("com/aifriend/contract/model/AskAssistantQuestionRequest.java"))
                .isRegularFile();
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("契约编译需要JDK，不接受跳过").isNotNull();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var sources = Files.walk(generated);
                var manager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            var files = sources.filter(path -> path.toString().endsWith(".java"))
                    .sorted().map(Path::toFile).toList();
            var units = manager.getJavaFileObjectsFromFiles(files);
            String classpath = System.getProperty("surefire.test.class.path",
                    System.getProperty("java.class.path"));
            Boolean success = compiler.getTask(null, manager, diagnostics,
                    List.of("--release", "17", "-proc:none", "-encoding", "UTF-8",
                            "-classpath", classpath, "-d", output.toString()), null, units).call();
            assertThat(success).withFailMessage("生成契约编译失败：%s", diagnostics.getDiagnostics()).isTrue();
        }
        assertThat(Files.readString(generated.resolve("com/aifriend/contract/model/ConsentType.java")))
                .contains("KNOWLEDGE_MODEL", "CONTACT_GRAPH");
    }
}
