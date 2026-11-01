package com.hapbang.shared.billing;

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

// shared의 Billing 연동 계약이 Billing·다른 업무 모듈·global에 의존하지 않고, 같은 JVM 호출용 Java Port를 두지 않는지 확인한다
// (shared는 모든 모듈이 쓰는 공통 계약이며 DTO·안정적인 Enum·오류 코드·공통 HTTP Client만 둔다, #280·#486).
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

    @Test
    @DisplayName("shared.billing에는 Java Port가 없다: port 패키지가 없고, 인터페이스는 결과 타입 같은 sealed 인터페이스뿐이다")
    void noJavaPorts() {
        noClasses().should().resideInAPackage("com.hapbang.shared.billing.port..").check(classes);
        classes().that().areInterfaces().and().areNotAnnotations().should(beSealed()).check(classes);
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
