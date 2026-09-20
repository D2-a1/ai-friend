package com.aifriend;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

/**
 * 模块依赖基础约束。
 *
 * @author Codex
 * @since 1.0.0
 */
class ArchitectureTest {

    @Test
    void privateGraphMustNotDependOnModelGateways() {
        var importedClasses = new ClassFileImporter().importPackages("com.aifriend");
        noClasses().that().resideInAPackage("..knowledge..")
                .or().haveSimpleName("ContactGraphSourceAdapter")
                .should().dependOnClassesThat().haveSimpleName("EmbeddingPort")
                .orShould().dependOnClassesThat().haveSimpleName("KnowledgeAnswerGenerationPort")
                .orShould().dependOnClassesThat().haveSimpleName("KnowledgeModelTransport")
                .orShould().dependOnClassesThat().resideInAPackage("..retrieval.infrastructure..")
                .orShould().dependOnClassesThat().resideInAPackage("..assistant.infrastructure..")
                .allowEmptyShould(true).check(importedClasses);
    }

    @Test
    void knowledgeMustNotDependOnContactExecutionOrTaskAuthorization() {
        var importedClasses = new ClassFileImporter().importPackages("com.aifriend");
        noClasses().that().resideInAnyPackage("..assistant..", "..retrieval..", "..knowledge..")
                .or().haveSimpleName("ContactGraphSourceAdapter")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..task..", "..channel..", "..wechat..")
                .allowEmptyShould(true)
                .check(importedClasses);
    }

    @Test
    void domainPackagesMustNotDependOnSpringOrJpa() {
        var importedClasses = new ClassFileImporter().importPackages("com.aifriend");

        noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework..", "jakarta.persistence..")
                .allowEmptyShould(true)
                .check(importedClasses);
    }
}
