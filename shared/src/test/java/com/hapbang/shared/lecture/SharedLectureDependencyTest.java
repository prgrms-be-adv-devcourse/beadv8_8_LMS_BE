package com.hapbang.shared.lecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

// shared의 강의 계약·Client가 강의·다른 업무 모듈·global에 의존하지 않는지 확인한다 (D62, #300).
// 다른 모듈은 강의 Java 타입이 아니라 이 계약과 내부 HTTP API로 강의를 부른다.
class SharedLectureDependencyTest {

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.hapbang.shared.lecture", "com.hapbang.shared.internal");

    @Test
    @DisplayName("shared.lecture·shared.internal은 강의·다른 업무 모듈·global 패키지를 참조하지 않는다")
    void noModuleDependencies() {
        noClasses().that().resideInAnyPackage("com.hapbang.shared.lecture..", "com.hapbang.shared.internal..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.hapbang.billing..", "com.hapbang.appearance..", "com.hapbang.lecture..",
                        "com.hapbang.member..", "com.hapbang.chat..", "com.hapbang.customersupport..",
                        "com.hapbang.global..")
                .check(classes);
    }
}
