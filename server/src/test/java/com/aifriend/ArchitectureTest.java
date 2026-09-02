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
