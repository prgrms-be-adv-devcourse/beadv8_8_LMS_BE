package com.hapbang.appearance.in;

import static com.hapbang.appearance.support.E2EMatchers.number;
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
import com.hapbang.appearance.support.AppearanceE2ETest;
import com.hapbang.appearance.support.E2ELogin;
import com.hapbang.shared.member.domain.Role;

import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

/**
 * 실제 서버(보안 필터, Gateway 사용자 헤더 로그인, 메서드 보안, 서비스, DB, Billing 대역)를 띄우고 RestAssured로 출연 모집 지원 API를 호출한다.
 * 성공 응답은 RsData다.
 */
@AppearanceE2ETest
class AppearanceApplicationApiE2ETest {

    private static final String RECRUITS = "/api/v1/appearances/recruits";
    private static final String APPLICATIONS = "/api/v1/appearances/applications";
    private static final long HOST = 1L;
    private static final long GUEST = 2L;
    private static final long GUEST2 = 3L;
    private static final long VIEWER = 4L;
    private static final long GUEST3 = 5L;

    @Autowired
    AppearanceUserFacade appearanceUserFacade;

    @Autowired
    @Qualifier(AppearanceConfig.CLOCK)
    Clock clock;

    @BeforeEach
    void setUp() {
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(HOST));
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(GUEST));
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(GUEST2));
        appearanceUserFacade.syncJoined(AppearanceUserFixture.normalUser(VIEWER));
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(GUEST3));
    }


    /** 인플루언서로 로그인해 JSON 본문을 보낸다. 본문이 빈 문자열이면 본문 없이 보낸다 */
    private Response postAs(long userId, String path, String body) {
        RequestSpecification request = E2ELogin.as(userId, Role.INFLUENCER).contentType(ContentType.JSON);
        if (!body.isEmpty()) {
            request.body(body);
        }
        return request.post(path);
    }

    /** 호스트가 정원 {@code recruitCount}명 모집글을 올리고 ID를 돌려준다 */
    private long postRecruit(int recruitCount) {
        LocalDateTime now = LocalDateTime.now(clock);
        String body = """
                {"title": "주말 게임 합방 게스트 모집", "hostChannelUrl": "https://www.youtube.com/@host",
                 "scheduledAt": "%s", "expectedMinutes": 60, "content": "같이 플레이할 게스트를 모집합니다.",
                 "feePerPerson": 30000, "recruitCount": %d, "recruitDeadline": "%s"}
                """.formatted(now.plusDays(7).withNano(0), recruitCount, now.plusDays(2).withNano(0));
        return postAs(HOST, RECRUITS, body)
                .then().statusCode(201)
                .extract().jsonPath().getLong("data.id");
    }

    /** 지원하고 지원 ID를 돌려준다 */
    private long apply(long guestId, long recruitId) {
        return postAs(guestId, RECRUITS + "/" + recruitId + "/applications", "{\"message\": \"지원합니다.\"}")
                .then().statusCode(201)
                .extract().jsonPath().getLong("data.id");
    }

    @Test
    @DisplayName("지원하면 201, RsData, APPLIED, 메시지. 다시 지원하면 409 DUPLICATE_APPLICATION")
    void apply() {
        long recruitId = postRecruit(3);

        postAs(GUEST, RECRUITS + "/" + recruitId + "/applications", "{\"message\": \"지원합니다.\"}")
                .then().statusCode(201)
                .body("statusCode", equalTo(201))
                .body("message", equalTo("출연 모집에 지원했습니다."))
                .body("data.status", equalTo("APPLIED"))
                .body("data.recruitId", number(recruitId))
                .body("data.message", equalTo("지원합니다."));
        postAs(GUEST, RECRUITS + "/" + recruitId + "/applications", "{}")
                .then().statusCode(409)
                .body("code", equalTo("DUPLICATE_APPLICATION"));
    }

    @Test
    @DisplayName("메시지는 선택이라 요청 본문 없이 지원해도 201, 메시지 null")
    void applyWithoutBody() {
        long recruitId = postRecruit(3);

        E2ELogin.as(GUEST, Role.INFLUENCER).post(RECRUITS + "/" + recruitId + "/applications")
                .then().statusCode(201)
                .body("data.status", equalTo("APPLIED"))
                .body("data.message", nullValue());
    }

    @Test
    @DisplayName("메시지 501자 400, 자기 모집글 400 CANNOT_APPLY_OWN_RECRUIT, 일반 유저 403 FORBIDDEN")
    void applyRejected() {
        long recruitId = postRecruit(3);

        postAs(GUEST, RECRUITS + "/" + recruitId + "/applications", "{\"message\": \"" + "가".repeat(501) + "\"}")
                .then().statusCode(400)
                .body("code", equalTo("INVALID_REQUEST"));
        postAs(HOST, RECRUITS + "/" + recruitId + "/applications", "{}")
                .then().statusCode(400)
                .body("code", equalTo("CANNOT_APPLY_OWN_RECRUIT"));
        E2ELogin.as(VIEWER, Role.USER).contentType(ContentType.JSON).body("{}")
                .post(RECRUITS + "/" + recruitId + "/applications")
                .then().statusCode(403)
                .body("code", equalTo("FORBIDDEN"));
    }

    @Test
    @DisplayName("수락하면 200, SELECTED, 출연 ID. 정원이 찼으면 다음 수락은 409 RECRUIT_FULL, 지원은 계속 201")
    void acceptAndFull() {
        long recruitId = postRecruit(1);
        long first = apply(GUEST, recruitId);
        long second = apply(GUEST2, recruitId);

        postAs(HOST, APPLICATIONS + "/" + first + "/accept", "")
                .then().statusCode(200)
                .body("message", equalTo("지원을 수락했습니다."))
                .body("data.status", equalTo("SELECTED"))
                .body("data.appearanceId", instanceOf(Number.class));
        postAs(HOST, APPLICATIONS + "/" + second + "/accept", "")
                .then().statusCode(409)
                .body("code", equalTo("RECRUIT_FULL"));
        apply(GUEST3, recruitId); // 정원이 차도 모집 중이면 지원은 201
        E2ELogin.as(VIEWER, Role.USER).get(RECRUITS + "/" + recruitId)
                .then().statusCode(200)
                .body("data.status", equalTo("RECRUITING"))
                .body("data.selectedCount", equalTo(1))
                .body("data.applicantCount", equalTo(3));
    }

    @Test
    @DisplayName("거절하면 NOT_SELECTED, 다른 회원의 수락은 403 NOT_RECRUIT_HOST")
    void rejectAndNotHost() {
        long recruitId = postRecruit(3);
        long applicationId = apply(GUEST, recruitId);

        postAs(GUEST2, APPLICATIONS + "/" + applicationId + "/accept", "")
                .then().statusCode(403)
                .body("code", equalTo("NOT_RECRUIT_HOST"));
        postAs(HOST, APPLICATIONS + "/" + applicationId + "/reject", "")
                .then().statusCode(200)
                .body("message", equalTo("지원을 거절했습니다."))
                .body("data.status", equalTo("NOT_SELECTED"));
    }

    @Test
    @DisplayName("지원 취소하면 WITHDRAWN, 다른 회원은 403 NOT_APPLICATION_GUEST, 두 번째 취소는 409")
    void withdraw() {
        long recruitId = postRecruit(3);
        long applicationId = apply(GUEST, recruitId);

        postAs(GUEST2, APPLICATIONS + "/" + applicationId + "/withdraw", "")
                .then().statusCode(403)
                .body("code", equalTo("NOT_APPLICATION_GUEST"));
        postAs(GUEST, APPLICATIONS + "/" + applicationId + "/withdraw", "")
                .then().statusCode(200)
                .body("message", equalTo("지원을 취소했습니다."))
                .body("data.status", equalTo("WITHDRAWN"));
        postAs(GUEST, APPLICATIONS + "/" + applicationId + "/withdraw", "")
                .then().statusCode(409)
                .body("code", equalTo("APPLICATION_NOT_PENDING"));
    }

    @Test
    @DisplayName("지원자 목록은 호스트만(상태 필터), 내 지원 목록은 내 것만, 없는 상태 값은 400")
    void lists() {
        long recruitId = postRecruit(3);
        long mine = apply(GUEST, recruitId);
        apply(GUEST2, recruitId);

        E2ELogin.as(HOST, Role.INFLUENCER).queryParam("status", "APPLIED")
                .get(RECRUITS + "/" + recruitId + "/applications")
                .then().statusCode(200)
                .body("message", equalTo("지원자 목록을 조회했습니다."))
                .body("data.page.totalElements", equalTo(2));
        E2ELogin.as(GUEST, Role.INFLUENCER).get(RECRUITS + "/" + recruitId + "/applications")
                .then().statusCode(403)
                .body("code", equalTo("NOT_RECRUIT_HOST"));
        E2ELogin.as(GUEST, Role.INFLUENCER).get(APPLICATIONS + "/mine")
                .then().statusCode(200)
                .body("message", equalTo("내 지원 목록을 조회했습니다."))
                .body("data.content[0].id", number(mine))
                .body("data.content[0].recruitTitle", equalTo("주말 게임 합방 게스트 모집"))
                .body("data.content[0].recruitScheduledAt", notNullValue())
                .body("data.content[0].recruitStatus", equalTo("RECRUITING"))
                .body("data.page.totalElements", equalTo(1));
        E2ELogin.as(GUEST, Role.INFLUENCER).queryParam("status", "UNKNOWN").get(APPLICATIONS + "/mine")
                .then().statusCode(400)
                .body("code", equalTo("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("모집글 상세의 myApplication: 지원한 게스트는 내 지원, 지원 안 한 회원·호스트는 null (#243)")
    void myApplicationInDetail() {
        long recruitId = postRecruit(3);
        long mine = apply(GUEST, recruitId);

        E2ELogin.as(GUEST, Role.INFLUENCER).get(RECRUITS + "/" + recruitId)
                .then().statusCode(200)
                .body("data.myApplication.id", number(mine))
                .body("data.myApplication.status", equalTo("APPLIED"))
                .body("data.myApplication.message", equalTo("지원합니다."))
                .body("data.myApplication.appearanceId", nullValue());
        E2ELogin.as(VIEWER, Role.USER).get(RECRUITS + "/" + recruitId)
                .then().statusCode(200)
                .body("data.myApplication", nullValue());
        E2ELogin.as(HOST, Role.INFLUENCER).get(RECRUITS + "/" + recruitId)
                .then().statusCode(200)
                .body("data.myApplication", nullValue());
    }

    @Test
    @DisplayName("일반 유저가 보는 공개 목록에도 지원자 수가 담긴다")
    void applicantCountInOpenList() {
        long recruitId = postRecruit(3);
        apply(GUEST, recruitId);

        E2ELogin.as(VIEWER, Role.USER).get(RECRUITS)
                .then().statusCode(200)
                .body("data.content[0].applicantCount", equalTo(1));
    }

    @Test
    @DisplayName("없는 지원 수락은 404 APPLICATION_NOT_FOUND")
    void notFound() {
        postAs(HOST, APPLICATIONS + "/999999/accept", "")
                .then().statusCode(404)
                .body("code", equalTo("APPLICATION_NOT_FOUND"));
    }
}
