package com.hapbang.modulithfixture;

import org.springframework.modulith.Modulithic;

/**
 * 모듈 경계 검사가 실패하는지 보는 테스트용 구조(AuxiliaryModularityTest). auxiliary-app과 같은 설정이다:
 * 공통 모듈 {@code global}과 공통 모듈만 허용한 업무 모듈 {@code alpha}·{@code beta}. {@code beta}가 {@code alpha}를 참조한다.
 * <p>
 * 실제 앱의 패키지({@code com.hapbang.auxiliary}) 밖에 두어 실제 검사에 섞이지 않는다. 스프링 빈이 아니다.
 */
@Modulithic(sharedModules = "global")
public class ModulithFixtureApplication {
}
