package com.hapbang.chat.domain;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/**
 * 도메인 경계: 도메인은 JSON 변환(Jackson)에 의존하지 않는다. 저장하는 JSON 값의 쓰기·읽기는 app의 {@code ChatStoredJson}이 한다.
 */
class ChatDomainBoundaryTest {

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.hapbang.chat");

    @Test
    @DisplayName("domain은 Jackson에 의존하지 않는다")
    void domainDoesNotDependOnJackson() {
        noClasses().that().resideInAPackage("com.hapbang.chat.domain..")
                .should().dependOnClassesThat().resideInAnyPackage("tools.jackson..", "com.fasterxml.jackson..")
                .check(classes);
    }
}
