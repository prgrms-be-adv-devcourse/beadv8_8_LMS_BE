package com.hapbang.coupon.in;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.AccessTarget;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

// 진입 어댑터(in)는 요청·메시지를 원시 값이나 app 입력(app/dto)으로 넘기고, 도메인 객체는 app이 만든다.
// in이 도메인 타입을 읽는 것(요청 필드·응답 분기의 enum 등)은 막지 않는다. 도메인 생성자·정적 메서드(팩토리) 호출과 그 참조만 막는다
class CouponInboundBoundaryTest {

    private static final String DOMAIN = "com.hapbang.coupon.domain";

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.hapbang.coupon");

    @Test
    @DisplayName("in은 도메인 타입의 생성자·정적 메서드를 부르지 않는다")
    void inboundDoesNotBuildDomainObjects() {
        noClasses().that().resideInAPackage("com.hapbang.coupon.in..")
                .should().accessTargetWhere(constructsDomainObject())
                .check(classes);
    }

    private static DescribedPredicate<JavaAccess<?>> constructsDomainObject() {
        return DescribedPredicate.describe("도메인 생성자·정적 메서드(enum 값 읽기 제외)", access -> {
            AccessTarget target = access.getTarget();
            String packageName = target.getOwner().getPackageName();
            if (!packageName.equals(DOMAIN) && !packageName.startsWith(DOMAIN + ".")) {
                return false;
            }
            if (target instanceof AccessTarget.ConstructorCallTarget
                    || target instanceof AccessTarget.ConstructorReferenceTarget) {
                return true;
            }
            if (target instanceof AccessTarget.MethodCallTarget || target instanceof AccessTarget.MethodReferenceTarget) {
                // enum의 values()·valueOf()는 값을 읽는 것이다(enum switch를 javac가 values()로 바꾸기도 한다)
                if (target.getOwner().isEnum()
                        && (target.getName().equals("values") || target.getName().equals("valueOf"))) {
                    return false;
                }
                return ((AccessTarget.CodeUnitAccessTarget) target).resolveMember()
                        .map(member -> member.getModifiers().contains(JavaModifier.STATIC))
                        .orElse(false);
            }
            return false;
        });
    }
}
