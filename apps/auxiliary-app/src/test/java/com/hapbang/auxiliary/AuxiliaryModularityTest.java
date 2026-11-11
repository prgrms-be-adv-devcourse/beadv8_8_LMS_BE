package com.hapbang.auxiliary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.core.Violations;

import com.hapbang.modulithfixture.ModulithFixtureApplication;

/**
 * auxiliary-app 모듈 경계 검사(#714). 모든 모듈이 클래스패스에 있는 이 프로젝트에서만 실행할 수 있다.
 * <ul>
 *     <li>업무 모듈은 {@code @ApplicationModule(allowedDependencies = "global")}이라 공통 모듈만 참조할 수 있다.</li>
 *     <li>업무 모듈끼리 직접 참조하면 {@code verify()}가 실패한다. 모듈 사이 연동은 이벤트로만 한다.</li>
 * </ul>
 */
class AuxiliaryModularityTest {

    @Test
    @DisplayName("공통 모듈과 챗봇 모듈을 찾고, 경계 규칙을 지킨다")
    void verify() {
        ApplicationModules modules = ApplicationModules.of(AuxiliaryApplication.class);

        assertThat(modules.stream().map(ApplicationModule::getIdentifier).map(Object::toString))
                .contains("global", "chatbot");
        modules.verify();
    }

    // 업무 모듈이 하나뿐이라 실제 코드로는 위반을 만들 수 없다. 같은 설정(공통 모듈만 허용)의 테스트용 모듈 둘로 검사가 실패하는지 본다
    @Test
    @DisplayName("업무 모듈이 다른 업무 모듈을 참조하면 검사가 실패한다")
    void rejectsReferenceBetweenBusinessModules() {
        // 기본 검사는 테스트 클래스를 읽지 않는다. 테스트용 모듈은 테스트 클래스라 모든 위치를 읽게 한다
        ApplicationModules fixture = ApplicationModules.of(ModulithFixtureApplication.class, location -> true);

        assertThat(fixture.stream().map(ApplicationModule::getIdentifier).map(Object::toString))
                .containsExactlyInAnyOrder("global", "alpha", "beta");
        assertThatThrownBy(fixture::verify)
                .isInstanceOf(Violations.class)
                .hasMessageContaining("beta")
                .hasMessageContaining("alpha");
    }
}
