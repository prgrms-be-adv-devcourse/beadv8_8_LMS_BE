package com.hapbang.shared.appearance;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

// shared의 출연 계약·Client가 출연·다른 업무 모듈·global에 의존하지 않는지 확인한다 (D62, #301, 강의 SharedLectureDependencyTest와 같은 규칙).
// 다른 모듈은 출연 Java 타입이 아니라 이 계약과 내부 HTTP API로 출연을 부른다.
class SharedAppearanceDependencyTest {

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.hapbang.shared.appearance");

    @Test
    @DisplayName("shared.appearance는 출연·다른 업무 모듈·global·다른 모듈의 shared 계약(강의)을 참조하지 않는다")
    void noModuleDependencies() {
        noClasses().that().resideInAPackage("com.hapbang.shared.appearance..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.hapbang.billing..", "com.hapbang.appearance..", "com.hapbang.lecture..",
                        "com.hapbang.member..", "com.hapbang.chat..", "com.hapbang.customersupport..",
                        "com.hapbang.global..", "com.hapbang.shared.lecture..")
                .check(classes);
    }
}
