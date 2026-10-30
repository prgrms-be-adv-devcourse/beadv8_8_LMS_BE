package com.hapbang.appearance;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.appearance.app.AppearanceUserFacade;
import com.hapbang.appearance.app.AppearanceUserSupport;
import com.hapbang.appearance.fixture.AppearanceUserFixture;
import com.hapbang.appearance.global.auth.LoginUserId;
import com.hapbang.appearance.out.AppearanceUserRepository;
import com.hapbang.appearance.support.AppearanceE2ETest;
import com.hapbang.appearance.support.E2ELogin;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.event.UserModifiedEvent;

import io.restassured.specification.RequestSpecification;
import lombok.RequiredArgsConstructor;

/**
 * 실제 서버(보안 필터, Gateway 사용자 헤더 로그인, 출연 서비스, DB)를 띄우고 RestAssured로 API 요청 흐름을 확인한다.
 * <ul>
 *     <li>사용자 헤더({@code X-User-Id}·{@code X-User-Role}) 로그인 → {@code @LoginUserId}로 회원 ID 주입 (#395)</li>
 *     <li>출연 회원 자격 확인 → 실패하면 Problem Detail 응답</li>
 * </ul>
 * 로그인·자격 확인 기반만 테스트 전용 컨트롤러로 확인한다. 실제 출연 API는 {@code AppearanceRequestApiE2ETest}에서 확인한다.
 */
@AppearanceE2ETest
@Import(AppearanceApiE2ETest.ParticipantProbeController.class)
class AppearanceApiE2ETest {

    private static final String PROBE_PATH = "/test/appearance/participant";

    @Autowired
    AppearanceUserFacade appearanceUserFacade;

    @Autowired
    AppearanceUserSupport appearanceUserSupport;

    @Autowired
    AppearanceUserRepository appearanceUserRepository;

    @Autowired
    ApplicationEventPublisher eventPublisher;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("로그인하지 않으면 401")
    void notLoggedIn() {
        given().get(PROBE_PATH)
                .then().statusCode(401);
    }

    @Test
    @DisplayName("로그인한 인플루언서는 헤더의 회원 ID로 출연 회원 자격이 확인되고, 세션 쿠키는 내려가지 않는다")
    void influencer() {
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(1L));

        E2ELogin.as(1L, Role.INFLUENCER).get(PROBE_PATH)
                .then().statusCode(200)
                .header("Set-Cookie", nullValue())
                .body(equalTo("1"));
    }

    @Test
    @DisplayName("헤더 역할은 인플루언서여도 복제한 회원이 일반 유저면 403 (NOT_INFLUENCER, 토큰에 아직 반영되지 않은 강등)")
    void demotedAfterTokenIssued() {
        appearanceUserFacade.syncJoined(AppearanceUserFixture.normalUser(5L));

        E2ELogin.as(5L, Role.INFLUENCER).get(PROBE_PATH)
                .then().statusCode(403)
                .body("code", equalTo("NOT_INFLUENCER"));
    }

    @Test
    @DisplayName("출연 회원 정보가 없으면 404 Problem Detail (APPEARANCE_USER_NOT_FOUND)")
    void appearanceUserNotFound() {
        E2ELogin.as(2L, Role.INFLUENCER).get(PROBE_PATH)
                .then().statusCode(404)
                .contentType(startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .body("code", equalTo("APPEARANCE_USER_NOT_FOUND"));
    }

    @Test
    @DisplayName("일반 유저는 403 Problem Detail (NOT_INFLUENCER), 한글 메시지가 UTF-8로 나간다")
    void normalUser() {
        appearanceUserFacade.syncJoined(AppearanceUserFixture.normalUser(3L));

        E2ELogin.as(3L, Role.USER).get(PROBE_PATH)
                .then().statusCode(403)
                .body("code", equalTo("NOT_INFLUENCER"))
                .body("detail", equalTo("인플루언서 이상만 출연 거래에 참여할 수 있습니다."));
    }

    @Test
    @DisplayName("회원 모듈에서 정지되면 같은 토큰(헤더)의 다음 요청은 403 (APPEARANCE_USER_NOT_ACTIVE)")
    void suspendedByUserModifiedEvent() {
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(4L));
        RequestSpecification login = E2ELogin.as(4L, Role.INFLUENCER);
        given().spec(login).get(PROBE_PATH)
                .then().statusCode(200);

        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(new UserModifiedEvent(
                AppearanceUserFixture.user(4L, Role.INFLUENCER, UserStatus.SUSPENDED))));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(
                appearanceUserRepository.findById(4L).orElseThrow().getStatus()).isEqualTo(UserStatus.SUSPENDED));

        given().spec(login).get(PROBE_PATH)
                .then().statusCode(403)
                .body("code", equalTo("APPEARANCE_USER_NOT_ACTIVE"));
    }

    /**
     * 테스트 전용 컨트롤러. {@code @TestComponent}라서 다른 테스트의 컴포넌트 스캔에는 잡히지 않는다.
     */
    @TestComponent
    @RestController
    @RequiredArgsConstructor
    static class ParticipantProbeController {

        private final AppearanceUserSupport appearanceUserSupport;

        @GetMapping(PROBE_PATH)
        Long participant(@LoginUserId Long userId) {
            return appearanceUserSupport.getParticipant(userId).getId();
        }
    }
}
