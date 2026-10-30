package com.hapbang.appearance.in;

import static com.hapbang.appearance.support.E2EMatchers.number;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import java.time.Clock;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import com.hapbang.appearance.app.AppearanceUserFacade;
import com.hapbang.appearance.config.AppearanceConfig;
import com.hapbang.appearance.fixture.AppearanceUserFixture;
import com.hapbang.appearance.out.billing.BillingClient;
import com.hapbang.appearance.support.AppearanceE2ETest;
import com.hapbang.appearance.support.BillingStubs;
import com.hapbang.appearance.support.BillingStubs.Rejection;
import com.hapbang.appearance.support.E2ELogin;
import com.hapbang.shared.member.domain.Role;

import io.restassured.http.ContentType;
import io.restassured.response.Response;

/**
 * 실제 서버(보안 필터, 세션 로그인, CSRF, 메서드 보안, 서비스, DB, Billing 대역)를 띄우고 RestAssured로 출연 모집글 API를 호출한다.
 * 성공 응답은 RsData다.
 */
@AppearanceE2ETest
class AppearanceRecruitApiE2ETest {

    private static final String BASE = "/api/v1/appearances/recruits";
    private static final long HOST = 1L;
    private static final long OTHER = 3L;
    private static final long VIEWER = 4L;

    @Autowired
    AppearanceUserFacade appearanceUserFacade;

    @Autowired
    BillingClient billingClient;

    @Autowired
    @Qualifier(AppearanceConfig.CLOCK)
    Clock clock;

    @BeforeEach
    void setUp() {
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(HOST));
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(OTHER));
        appearanceUserFacade.syncJoined(AppearanceUserFixture.normalUser(VIEWER));
    }


    private String createBody(long feePerPerson, int recruitCount) {
        LocalDateTime now = LocalDateTime.now(clock);
        return """
                {"title": "주말 게임 합방 게스트 모집", "hostChannelUrl": "https://www.youtube.com/@host",
                 "scheduledAt": "%s", "expectedMinutes": 60, "content": "같이 플레이할 게스트를 모집합니다.",
                 "feePerPerson": %d, "recruitCount": %d, "recruitDeadline": "%s"}
                """.formatted(now.plusDays(7).withNano(0), feePerPerson, recruitCount, now.plusDays(2).withNano(0));
    }

    private Response createRequest(long hostId, Role role, String body) {
        return E2ELogin.as(hostId, role).contentType(ContentType.JSON).body(body).post(BASE);
    }

    private Response patchAs(long userId, long recruitId, String body) {
        return E2ELogin.as(userId, Role.INFLUENCER).contentType(ContentType.JSON).body(body).patch(BASE + "/" + recruitId);
    }

    /** 모집글을 올리고 ID를 돌려준다 */
    private long postRecruit(long hostId) {
        return createRequest(hostId, Role.INFLUENCER, createBody(30_000, 3))
                .then().statusCode(201)
                .extract().jsonPath().getLong("data.id");
    }

    @Test
    @DisplayName("올리면 201, RsData, RECRUITING, 1인당 출연료·모집 인원·성립 인원 0")
    void create() {
        createRequest(HOST, Role.INFLUENCER, createBody(30_000, 3))
                .then().statusCode(201)
                .body("statusCode", equalTo(201))
                .body("message", equalTo("출연 모집글을 올렸습니다."))
                .body("data.status", equalTo("RECRUITING"))
                .body("data.feePerPerson", equalTo(30_000))
                .body("data.recruitCount", equalTo(3))
                .body("data.selectedCount", equalTo(0))
                .body("data.version", instanceOf(Number.class));
    }

    @Test
    @DisplayName("모집 인원 11명은 400 RECRUIT_COUNT_OUT_OF_RANGE, 모집 인원 누락은 400 INVALID_REQUEST")
    void createInvalid() {
        createRequest(HOST, Role.INFLUENCER, createBody(30_000, 11))
                .then().statusCode(400)
                .body("code", equalTo("RECRUIT_COUNT_OUT_OF_RANGE"))
                .body("detail", equalTo("모집 인원은 1명 이상 10명 이하여야 합니다."));
        createRequest(HOST, Role.INFLUENCER, createBody(30_000, 3).replace("\"recruitCount\": 3,", ""))
                .then().statusCode(400)
                .body("code", equalTo("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("지갑이 잔액 부족으로 보류를 거절하면 409 INSUFFICIENT_BALANCE (문구는 출연이 정함)")
    void insufficientBalance() {
        BillingStubs.reject(billingClient, Rejection.INSUFFICIENT_BALANCE);

        createRequest(HOST, Role.INFLUENCER, createBody(30_000, 3))
                .then().statusCode(409)
                .body("code", equalTo("INSUFFICIENT_BALANCE"));
    }

    @Test
    @DisplayName("일반 유저는 공개 목록·상세를 볼 수 있지만 올릴 수는 없다 (403 FORBIDDEN)")
    void normalUser() {
        long recruitId = postRecruit(HOST);

        E2ELogin.as(VIEWER, Role.USER).get(BASE)
                .then().statusCode(200)
                .body("message", equalTo("모집 중인 출연 모집글 목록을 조회했습니다."))
                .body("data.content[0].id", number(recruitId))
                .body("data.page.totalElements", equalTo(1));
        E2ELogin.as(VIEWER, Role.USER).get(BASE + "/" + recruitId)
                .then().statusCode(200)
                .body("message", equalTo("출연 모집글을 조회했습니다."))
                .body("data.content", equalTo("같이 플레이할 게스트를 모집합니다."));
        createRequest(VIEWER, Role.USER, createBody(30_000, 3))
                .then().statusCode(403)
                .body("code", equalTo("FORBIDDEN"));
    }

    @Test
    @DisplayName("로그인하지 않으면 공개 목록도 401 (비회원 공개는 팀 합의 후)")
    void anonymous() {
        given().get(BASE).then().statusCode(401);
    }

    @Test
    @DisplayName("내 목록은 내가 올린 모집글만, 숨김 상태 필터는 400")
    void mine() {
        long mine = postRecruit(HOST);
        postRecruit(OTHER);

        E2ELogin.as(HOST, Role.INFLUENCER).queryParam("status", "RECRUITING").get(BASE + "/mine")
                .then().statusCode(200)
                .body("message", equalTo("내 출연 모집글 목록을 조회했습니다."))
                .body("data.content[0].id", number(mine))
                .body("data.page.totalElements", equalTo(1));
        E2ELogin.as(HOST, Role.INFLUENCER).queryParam("status", "HOLD_PENDING").get(BASE + "/mine")
                .then().statusCode(400)
                .body("code", equalTo("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("호스트는 기본 정보를 수정하고, 다른 회원은 403 NOT_RECRUIT_HOST, 빈 수정은 400")
    void update() {
        long recruitId = postRecruit(HOST);

        patchAs(HOST, recruitId, "{\"title\": \"바뀐 제목\"}")
                .then().statusCode(200)
                .body("message", equalTo("출연 모집글을 수정했습니다."))
                .body("data.title", equalTo("바뀐 제목"));
        patchAs(OTHER, recruitId, "{\"title\": \"남의 글\"}")
                .then().statusCode(403)
                .body("code", equalTo("NOT_RECRUIT_HOST"));
        patchAs(HOST, recruitId, "{}")
                .then().statusCode(400)
                .body("code", equalTo("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("조기 마감하면 200, CLOSED(EARLY), 반환 진행 상태 DONE(호스트만). 다른 회원 403, 다시 마감 409, 일반 유저 403")
    void closeEarly() {
        long recruitId = postRecruit(HOST);
        String path = BASE + "/" + recruitId + "/close";

        E2ELogin.as(OTHER, Role.INFLUENCER).post(path)
                .then().statusCode(403)
                .body("code", equalTo("NOT_RECRUIT_HOST"));
        E2ELogin.as(VIEWER, Role.USER).post(path)
                .then().statusCode(403)
                .body("code", equalTo("FORBIDDEN"));
        E2ELogin.as(HOST, Role.INFLUENCER).post(path)
                .then().statusCode(200)
                .body("message", equalTo("출연 모집을 마감했습니다."))
                .body("data.status", equalTo("CLOSED"))
                .body("data.closeType", equalTo("EARLY"))
                .body("data.closedAt", notNullValue())
                .body("data.hpReleaseStatus", equalTo("DONE"));
        E2ELogin.as(HOST, Role.INFLUENCER).post(path)
                .then().statusCode(409)
                .body("code", equalTo("APPEARANCE_RECRUIT_NOT_OPEN"));
        E2ELogin.as(VIEWER, Role.USER).get(BASE + "/" + recruitId)
                .then().statusCode(200)
                .body("data.status", equalTo("CLOSED"))
                .body("data.hpReleaseStatus", anyOf(nullValue(), emptyString()));
        E2ELogin.as(VIEWER, Role.USER).get(BASE)
                .then().statusCode(200)
                .body("data.page.totalElements", equalTo(0));
    }

    @Test
    @DisplayName("없는 모집글 상세는 404 APPEARANCE_RECRUIT_NOT_FOUND")
    void notFound() {
        E2ELogin.as(VIEWER, Role.USER).get(BASE + "/999999")
                .then().statusCode(404)
                .body("code", equalTo("APPEARANCE_RECRUIT_NOT_FOUND"));
    }

    @Test
    @DisplayName("모집 전체 취소: 다른 회원 403 NOT_RECRUIT_HOST, 호스트(본문 없음) 200 CANCELED·빈자리 반환 DONE, 다시 취소 409 APPEARANCE_RECRUIT_NOT_CANCELABLE")
    void cancelRecruit() {
        long recruitId = postRecruit(HOST);
        String path = BASE + "/" + recruitId + "/cancel";

        E2ELogin.as(OTHER, Role.INFLUENCER).post(path)
                .then().statusCode(403)
                .body("code", equalTo("NOT_RECRUIT_HOST"));
        E2ELogin.as(HOST, Role.INFLUENCER).post(path)
                .then().statusCode(200)
                .body("message", equalTo("출연 모집을 취소했습니다."))
                .body("data.status", equalTo("CANCELED"))
                .body("data.canceledAt", notNullValue())
                .body("data.hpReleaseStatus", equalTo("DONE"));
        E2ELogin.as(HOST, Role.INFLUENCER).post(path)
                .then().statusCode(409)
                .body("code", equalTo("APPEARANCE_RECRUIT_NOT_CANCELABLE"));
    }
}
