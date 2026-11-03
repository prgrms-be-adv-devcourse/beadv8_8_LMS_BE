package com.hapbang.appearance.in;

import static com.hapbang.appearance.support.E2EMatchers.number;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;

import com.hapbang.appearance.app.AppearanceApplicationFacade;
import com.hapbang.appearance.app.AppearanceRecruitFacade;
import com.hapbang.appearance.app.AppearanceUserFacade;
import com.hapbang.appearance.fixture.AppearanceRecruitFixture;
import com.hapbang.appearance.fixture.AppearanceUserFixture;
import com.hapbang.appearance.support.AppearanceE2ETest;
import com.hapbang.appearance.support.AppearanceTestClock;
import com.hapbang.appearance.support.E2ELogin;
import com.hapbang.shared.internal.InternalApiHeaders;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.IntegrationTest;

import io.restassured.specification.RequestSpecification;

/**
 * 실제 서버(보안 필터, 내부 API 키 체인, 메서드 보안, DB)를 띄우고 RestAssured로 모집글 내부 API를 호출한다 (#586).
 * 채팅 앱이 모집용 단체방 생성(모집글 요약)·초대 후보 검색(지원자 ID 키셋)·초대 직전 확인(지원 이력)에 쓴다.
 * 모집글·지원은 Facade로 준비한다 (테스트 스레드 역할은 {@code @WithMockUser}).
 * 외부 포트로 오면 404인 것은 경로 접두사로 막으므로 {@code InternalApiPortConfigTest}가 맡는다.
 */
@AppearanceE2ETest
@WithMockUser(roles = "INFLUENCER")
class AppearanceRecruitInternalApiE2ETest {

    private static final String BASE = "/api/v1/internal/appearances/recruits/";
    private static final long HOST = 1L;
    /** 지원하는 게스트. ID 순서와 지원 순서를 일부러 다르게 둔다 */
    private static final List<Long> GUESTS = List.of(5L, 2L, 4L, 3L);
    private static final long OTHER_GUEST = 6L;

    @Autowired
    AppearanceUserFacade appearanceUserFacade;

    @Autowired
    AppearanceRecruitFacade appearanceRecruitFacade;

    @Autowired
    AppearanceApplicationFacade appearanceApplicationFacade;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    AppearanceTestClock clock;

    @BeforeEach
    void setUp() {
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(HOST));
        for (long guest : GUESTS) {
            appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(guest));
        }
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(OTHER_GUEST));
    }

    private long postRecruit() {
        return appearanceRecruitFacade.create(HOST, AppearanceRecruitFixture.content(clock.now())).id();
    }

    private long apply(long guestId, long recruitId) {
        return appearanceApplicationFacade.apply(guestId, recruitId, null).id();
    }

    private static RequestSpecification internal() {
        return given().header(InternalApiHeaders.API_KEY, IntegrationTest.INTERNAL_API_KEY);
    }

    @Test
    @DisplayName("모집글 요약: {recruitId, hostId, title, status}. HP 보류 처리 중·실패를 포함해 상태를 그대로 준다")
    void recruit() {
        long recruitId = postRecruit();

        internal().get(BASE + recruitId)
                .then().statusCode(200)
                .body("statusCode", equalTo(200))
                .body("message", equalTo("출연 모집글을 조회했습니다."))
                .body("data.recruitId", number(recruitId))
                .body("data.hostId", number(HOST))
                .body("data.title", equalTo("주말 게임 합방 게스트 모집"))
                .body("data.status", equalTo("RECRUITING"));

        // 회원 API에서는 호스트 외에 숨기는 상태도 내부 API는 그대로 준다 (채팅이 판단)
        for (String status : List.of("HOLD_PENDING", "HOLD_FAILED", "CLOSED", "CANCELED")) {
            jdbcTemplate.update("update appearances.recruits set status = ? where id = ?", status, recruitId);
            internal().get(BASE + recruitId)
                    .then().statusCode(200)
                    .body("data.status", equalTo(status));
        }
    }

    @Test
    @DisplayName("모집글 요약: 없는 모집글 404 APPEARANCE_RECRUIT_NOT_FOUND, ID 1 미만·숫자 아님 400 INVALID_REQUEST")
    void recruitErrors() {
        internal().get(BASE + 999_999)
                .then().statusCode(404)
                .body("code", equalTo("APPEARANCE_RECRUIT_NOT_FOUND"));
        internal().get(BASE + 0)
                .then().statusCode(400)
                .body("code", equalTo("INVALID_REQUEST"));
        internal().get(BASE + "abc")
                .then().statusCode(400)
                .body("code", equalTo("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("지원자 ID: 지원·수락·미선정·지원 취소 모두 회원 ID 오름차순으로 한 번씩, 다른 모집글 지원자는 없다")
    void applicantsAllStatuses() {
        long recruitId = postRecruit();
        apply(5L, recruitId);
        long selected = apply(2L, recruitId);
        long rejected = apply(4L, recruitId);
        long withdrawn = apply(3L, recruitId);
        appearanceApplicationFacade.accept(HOST, selected);
        appearanceApplicationFacade.reject(HOST, rejected);
        appearanceApplicationFacade.withdraw(3L, withdrawn);
        apply(OTHER_GUEST, postRecruit());

        internal().get(BASE + recruitId + "/applicants")
                .then().statusCode(200)
                .body("message", equalTo("출연 모집글 지원자 목록을 조회했습니다."))
                .body("data.userIds", contains(2, 3, 4, 5))
                .body("data", not(hasKey("nextAfterUserId")));
    }

    @Test
    @DisplayName("지원자 ID 키셋: size로 나누고 nextAfterUserId로 이어 읽는다. afterUserId 0은 처음부터. 딱 맞게 끝나는 페이지와 빈 모집글은 다음 키가 없다")
    void applicantsKeyset() {
        long recruitId = postRecruit();
        for (long guest : GUESTS) {
            apply(guest, recruitId);
        }

        internal().queryParam("size", 3).get(BASE + recruitId + "/applicants")
                .then().statusCode(200)
                .body("data.userIds", contains(2, 3, 4))
                .body("data.nextAfterUserId", equalTo(4));
        internal().queryParam("size", 3).queryParam("afterUserId", 0).get(BASE + recruitId + "/applicants")
                .then().statusCode(200)
                .body("data.userIds", contains(2, 3, 4))
                .body("data.nextAfterUserId", equalTo(4));
        internal().queryParam("size", 3).queryParam("afterUserId", 4).get(BASE + recruitId + "/applicants")
                .then().statusCode(200)
                .body("data.userIds", contains(5))
                .body("data", not(hasKey("nextAfterUserId")));
        internal().queryParam("size", 4).get(BASE + recruitId + "/applicants")
                .then().statusCode(200)
                .body("data.userIds", contains(2, 3, 4, 5))
                .body("data", not(hasKey("nextAfterUserId")));
        internal().queryParam("afterUserId", 5).get(BASE + recruitId + "/applicants")
                .then().statusCode(200)
                .body("data.userIds", empty())
                .body("data", not(hasKey("nextAfterUserId")));
        internal().get(BASE + postRecruit() + "/applicants")
                .then().statusCode(200)
                .body("data.userIds", empty());
    }

    @Test
    @DisplayName("지원자 ID: size 0·501·숫자 아님, afterUserId 음수 400 INVALID_REQUEST, size 500 허용, 없는 모집글 404")
    void applicantsErrors() {
        long recruitId = postRecruit();
        String path = BASE + recruitId + "/applicants";

        for (Object size : List.of(0, 501, "abc")) {
            internal().queryParam("size", size).get(path)
                    .then().statusCode(400)
                    .body("code", equalTo("INVALID_REQUEST"));
        }
        internal().queryParam("afterUserId", -1).get(path)
                .then().statusCode(400)
                .body("code", equalTo("INVALID_REQUEST"));
        internal().queryParam("size", 500).get(path)
                .then().statusCode(200);
        internal().get(BASE + 999_999 + "/applicants")
                .then().statusCode(404)
                .body("code", equalTo("APPEARANCE_RECRUIT_NOT_FOUND"));
    }

    @Test
    @DisplayName("지원 이력 확인: 지원한 회원만 오름차순·중복 없이. 쉼표·반복 형식 모두 받고, 지원 취소한 회원도 이력이 있다")
    void exists() {
        long recruitId = postRecruit();
        apply(4L, recruitId);
        appearanceApplicationFacade.withdraw(2L, apply(2L, recruitId));
        apply(OTHER_GUEST, postRecruit());
        String path = BASE + recruitId + "/applicants/exists";

        internal().queryParam("userIds", "4,6,2,99,4").get(path)
                .then().statusCode(200)
                .body("message", equalTo("출연 모집글 지원 이력을 확인했습니다."))
                .body("data.appliedUserIds", contains(2, 4));
        internal().queryParam("userIds", 4).queryParam("userIds", 2).get(path)
                .then().statusCode(200)
                .body("data.appliedUserIds", contains(2, 4));
        internal().queryParam("userIds", 99).get(path)
                .then().statusCode(200)
                .body("data.appliedUserIds", empty());
    }

    @Test
    @DisplayName("지원 이력 확인: userIds 없음·101개·0·숫자 아님 400 INVALID_REQUEST, 100개 허용, 없는 모집글 404")
    void existsErrors() {
        long recruitId = postRecruit();
        String path = BASE + recruitId + "/applicants/exists";
        String hundred = LongStream.rangeClosed(1, 100).mapToObj(String::valueOf).collect(Collectors.joining(","));

        internal().get(path)
                .then().statusCode(400)
                .body("code", equalTo("INVALID_REQUEST"));
        for (String userIds : List.of(hundred + ",101", "0", "1,abc")) {
            internal().queryParam("userIds", userIds).get(path)
                    .then().statusCode(400)
                    .body("code", equalTo("INVALID_REQUEST"));
        }
        internal().queryParam("userIds", hundred).get(path)
                .then().statusCode(200);
        internal().queryParam("userIds", 1).get(BASE + 999_999 + "/applicants/exists")
                .then().statusCode(404)
                .body("code", equalTo("APPEARANCE_RECRUIT_NOT_FOUND"));
    }

    @Test
    @DisplayName("내부 키가 없거나 틀리면 본문 없는 401, 회원 로그인(호스트 본인)만으로도 401")
    void keyRequired() {
        long recruitId = postRecruit();
        for (String path : List.of(BASE + recruitId, BASE + recruitId + "/applicants",
                BASE + recruitId + "/applicants/exists?userIds=2")) {
            given().get(path)
                    .then().statusCode(401)
                    .body(equalTo(""));
            given().header(InternalApiHeaders.API_KEY, IntegrationTest.INTERNAL_API_KEY + "x").get(path)
                    .then().statusCode(401);
            E2ELogin.as(HOST, Role.INFLUENCER).get(path)
                    .then().statusCode(401);
            internal().get(path)
                    .then().statusCode(200);
        }
    }
}
