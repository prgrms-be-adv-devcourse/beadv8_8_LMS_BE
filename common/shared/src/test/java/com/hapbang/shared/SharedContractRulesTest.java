package com.hapbang.shared;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/**
 * shared 전체가 모든 앱이 함께 쓰는 계약만 두는지 확인한다 (#280, D62).
 * shared에는 DTO·이벤트·안정적인 Enum·오류 코드·공통 HTTP Client만 둔다. 같은 JVM에서 다른 bounded context를 부르는
 * Java Port는 두지 않는다(모듈 간 동기 연동은 제공 앱의 내부 HTTP API다). 컨텍스트별 세부 규칙은 각 {@code Shared*DependencyTest}가 맡는다.
 */
class SharedContractRulesTest {

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.hapbang.shared");

    @Test
    @DisplayName("shared는 앱 패키지(업무 모듈·앱별 global)를 참조하지 않는다")
    void noAppDependencies() {
        noClasses().that().resideInAPackage("com.hapbang.shared..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.hapbang.member..", "com.hapbang.billing..", "com.hapbang.appearance..",
                        "com.hapbang.lecture..", "com.hapbang.chat..", "com.hapbang.customersupport..",
                        "com.hapbang.global..")
                .check(classes);
    }

    @Test
    @DisplayName("shared에는 Java Port가 없다: port 패키지가 없고, 인터페이스는 결과 타입 같은 sealed 인터페이스뿐이다")
    void noJavaPorts() {
        noClasses().should().resideInAPackage("..port..").check(classes);
        // package-info(패키지 문서)는 클래스 파일에서 인터페이스로 보이므로 뺀다
        classes().that().areInterfaces().and().areNotAnnotations().and().doNotHaveSimpleName("package-info")
                .should(beSealed()).check(classes);
    }

    private static ArchCondition<JavaClass> beSealed() {
        return new ArchCondition<>("be sealed") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                boolean sealed = javaClass.reflect().isSealed();
                events.add(new SimpleConditionEvent(javaClass, sealed,
                        javaClass.getName() + (sealed ? " is sealed" : " is not sealed")));
            }
        };
    }
}
