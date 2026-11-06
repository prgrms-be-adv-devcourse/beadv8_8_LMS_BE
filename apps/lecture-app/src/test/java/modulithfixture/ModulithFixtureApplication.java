package modulithfixture;

import org.springframework.modulith.Modulithic;

/**
 * 모듈 경계 검사가 실패하는지 보는 테스트용 구조(ModulithStructureTest). lecture-app과 같은 설정이다:
 * 서로 의존을 허용하지 않는 모듈 {@code alpha}·{@code beta}. {@code beta}가 {@code alpha}를 참조한다.
 * <p>
 * 실제 검사는 {@code com.hapbang}을 루트로 읽는데, 그 아래에 {@code @Modulithic} 클래스가 있으면 그것을 실행 진입점으로 잡아 루트가 바뀐다.
 * 그래서 auxiliary-app의 같은 테스트용 구조({@code com.hapbang.modulithfixture})와 달리 {@code com.hapbang} 밖에 둔다. 스프링 빈이 아니다.
 */
@Modulithic
public class ModulithFixtureApplication {
}
