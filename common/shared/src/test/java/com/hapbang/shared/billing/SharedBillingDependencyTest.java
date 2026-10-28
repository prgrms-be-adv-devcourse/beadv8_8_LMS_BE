package com.hapbang.shared.billing;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

// shared의 Billing 연동 계약이 Billing·다른 업무 모듈·global에 의존하지 않는지 확인한다(shared는 모든 모듈이 쓰는 공통 계약이다).
class SharedBillingDependencyTest {

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.hapbang.shared.billing");

    @Test
    @DisplayName("shared.billing은 Billing·다른 업무 모듈·global 패키지를 참조하지 않는다")
    void noModuleDependencies() {
        noClasses().that().resideInAPackage("com.hapbang.shared.billing..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.hapbang.billing..", "com.hapbang.appearance..", "com.hapbang.lecture..",
                        "com.hapbang.member..", "com.hapbang.chat..", "com.hapbang.customersupport..",
                        "com.hapbang.global..")
                .check(classes);
    }
}
