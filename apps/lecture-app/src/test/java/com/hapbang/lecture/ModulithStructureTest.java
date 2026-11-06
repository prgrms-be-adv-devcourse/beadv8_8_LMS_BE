package com.hapbang.lecture;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.core.Violations;

import com.tngtech.archunit.core.domain.JavaClass;

import modulithfixture.ModulithFixtureApplication;

/**
 * lecture-app 모듈 경계 검사. 강의({@code com.hapbang.lecture})와 같은 JVM에 실리는 쿠폰({@code com.hapbang.coupon})은 서로 의존을
 * 허용하지 않는다({@code allowedDependencies = {}}). 1차 방어는 Gradle({@code :lecture-app}이 {@code :coupon}을 runtimeOnly로 싣고
 * 쿠폰은 강의를 의존하지 않는다)이고, 이 검사는 패키지 참조를 막는 2차 방어다.
 * <p>
 * 두 모듈은 형제 패키지라 {@code com.hapbang}을 루트로 읽는다. 앱의 모듈 감지 방식은 {@code @ApplicationModule}을 붙인 하위 패키지만
 * 모듈로 보는 {@code explicitly-annotated}(application.yaml)다. 실행 진입점({@code LectureApplication})의 패키지를 루트로 읽으면
 * 쿠폰이 루트 밖이고 강의는 루트 자신이라 모듈을 하나도 찾지 못한다. 공통 라이브러리 패키지는 모듈이 아니므로 읽지 않는다.
 */
class ModulithStructureTest {

    @Test
    @DisplayName("강의와 쿠폰 두 모듈을 찾고, 서로 참조하지 않는다")
    void verify() {
        ApplicationModules modules = ApplicationModules.of("com.hapbang", JavaClass.Predicates.resideInAnyPackage(
                "com.hapbang.shared..", "com.hapbang.common..", "com.hapbang.testsupport.."));

        assertThat(modules.stream().map(ApplicationModule::getIdentifier).map(Object::toString))
                .containsExactlyInAnyOrder("lecture", "coupon");
        modules.verify();
    }

    // 지금은 Gradle이 두 모듈의 참조를 컴파일에서 막아 실제 코드로는 위반을 만들 수 없다. 같은 설정(서로 의존 불허)의 테스트용 모듈 둘로
    // 검사가 실제로 실패하는지 본다
    @Test
    @DisplayName("의존을 허용하지 않은 모듈을 참조하면 검사가 실패한다")
    void rejectsReferenceBetweenModules() {
        // 기본 검사는 테스트 클래스를 읽지 않는다. 테스트용 모듈은 테스트 클래스라 모든 위치를 읽게 한다
        ApplicationModules fixture = ApplicationModules.of(ModulithFixtureApplication.class, location -> true);

        assertThat(fixture.stream().map(ApplicationModule::getIdentifier).map(Object::toString))
                .containsExactlyInAnyOrder("alpha", "beta");
        assertThatThrownBy(fixture::verify)
                .isInstanceOf(Violations.class)
                .hasMessageContaining("beta")
                .hasMessageContaining("alpha");
    }
}
