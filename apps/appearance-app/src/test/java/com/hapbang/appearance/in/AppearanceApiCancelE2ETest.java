package com.hapbang.appearance.in;

import static com.hapbang.appearance.support.E2EMatchers.number;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;

import com.hapbang.appearance.app.AppearanceRequestFacade;
import com.hapbang.appearance.app.AppearanceUserFacade;
import com.hapbang.appearance.app.dto.AppearanceRequestResDto;
import com.hapbang.appearance.fixture.AppearanceRequestFixture;
import com.hapbang.appearance.fixture.AppearanceUserFixture;
import com.hapbang.appearance.support.AppearanceE2ETest;
import com.hapbang.appearance.support.AppearanceTestClock;
import com.hapbang.appearance.support.E2ELogin;
import com.hapbang.shared.member.domain.Role;

import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

/**
 * 실제 서버(보안 필터, 세션 로그인, CSRF, 메서드 보안, DB, Billing 대역)를 띄우고 RestAssured로 출연 조회·취소 요청 API를 호출한다.
 * 출연 성립까지는 Facade로 준비한다 (테스트 스레드 역할은 {@code @WithMockUser}, API 호출은 {@link E2ELogin}).
 */
@AppearanceE2ETest
@WithMockUser(roles = "INFLUENCER")
class AppearanceApiCancelE2ETest {

    private static final String BASE = "/api/v1/appearances";
    private static final long HOST = 1L;
    private static final long GUEST = 2L;
    private static final long OTHER = 3L;
    private static final long VIEWER = 4L;

    @Autowired
    AppearanceUserFacade appearanceUserFacade;

    @Autowired
    AppearanceRequestFacade appearanceRequestFacade;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    AppearanceTestClock clock;

    @BeforeEach
    void setUp() {
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(HOST));
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(GUEST));
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(OTHER));
        appearanceUserFacade.syncJoined(AppearanceUserFixture.normalUser(VIEWER));
    }


    /** 요청서가 수락돼 성립한 출연 ID */
    private long establish() {
        AppearanceRequestResDto sent = appearanceRequestFacade.create(HOST, GUEST,
                AppearanceRequestFixture.content(LocalDateTime.now(clock)));
        return appearanceRequestFacade.accept(GUEST, sent.id(), sent.version()).appearanceId();
    }

    private Response postAs(long userId, String path, String body) {
        RequestSpecification request = E2ELogin.as(userId, Role.INFLUENCER);
        if (body != null) {
            request.contentType(ContentType.JSON).body(body);
        }
        return request.post(path);
    }

    @Test
    @DisplayName("내 출연 목록(role 필수)·상세: 200 RsData, 다른 회원 상세 404, 일반 유저 403")
    void query() {
        long appearanceId = establish();

        E2ELogin.as(GUEST, Role.INFLUENCER).queryParam("role", "GUEST").get(BASE)
                .then().statusCode(200)
                .body("message", equalTo("내 출연 목록을 조회했습니다."))
                .body("data.content[0].id", number(appearanceId))
                .body("data.content[0].title", notNullValue())
                .body("data.page.totalElements", equalTo(1));
        E2ELogin.as(GUEST, Role.INFLUENCER).get(BASE)
                .then().statusCode(400);
        E2ELogin.as(HOST, Role.INFLUENCER).get(BASE + "/" + appearanceId)
                .then().statusCode(200)
                .body("message", equalTo("출연을 조회했습니다."))
                .body("data.status", equalTo("ESTABLISHED"))
                .body("data.cancelRequests", empty());
        E2ELogin.as(OTHER, Role.INFLUENCER).get(BASE + "/" + appearanceId)
                .then().statusCode(404)
                .body("code", equalTo("APPEARANCE_NOT_FOUND"));
        E2ELogin.as(VIEWER, Role.USER).get(BASE + "/" + appearanceId)
                .then().statusCode(403)
                .body("code", equalTo("FORBIDDEN"));
    }

    @Test
    @DisplayName("취소 요청 200 CANCEL_REQUESTED → 다시 요청 409 → 게스트가 수락 403 → 호스트 거절(본문 없음) 200 → 2차 요청 → 수락 200 CANCELED_BY_AGREEMENT")
    void cancelFlow() {
        long appearanceId = establish();
        String cancelPath = BASE + "/" + appearanceId + "/cancel-requests";

        postAs(GUEST, cancelPath, "{}")
                .then().statusCode(400)
                .body("code", equalTo("INVALID_REQUEST"));
        postAs(GUEST, cancelPath, "{\"reason\": \"일정이 생겼습니다.\"}")
                .then().statusCode(200)
                .body("message", equalTo("출연 취소를 요청했습니다."))
                .body("data.status", equalTo("CANCEL_REQUESTED"))
                .body("data.cancelRequests[0].reason", equalTo("일정이 생겼습니다."));
        postAs(GUEST, cancelPath, "{\"reason\": \"다시\"}")
                .then().statusCode(409)
                .body("code", equalTo("CANCEL_REQUEST_ALREADY_PENDING"));
        postAs(GUEST, cancelPath + "/accept", null)
                .then().statusCode(403)
                .body("code", equalTo("NOT_APPEARANCE_HOST"));
        postAs(HOST, cancelPath + "/reject", null)
                .then().statusCode(200)
                .body("message", equalTo("출연 취소 요청을 거절했습니다."))
                .body("data.status", equalTo("ESTABLISHED"))
                .body("data.cancelRequests[0].status", equalTo("REJECTED"));

        postAs(GUEST, cancelPath, "{\"reason\": \"정말 어렵습니다.\"}")
                .then().statusCode(200);
        postAs(HOST, cancelPath + "/accept", null)
                .then().statusCode(200)
                .body("message", equalTo("출연 취소 요청을 수락했습니다."))
                .body("data.status", equalTo("CANCELED_BY_AGREEMENT"))
                .body("data.cancelRequests[1].sequence", equalTo(2))
                .body("data.cancelRequests[1].status", equalTo("ACCEPTED"));
        postAs(GUEST, cancelPath, "{\"reason\": \"세 번째\"}")
                .then().statusCode(409)
                .body("code", equalTo("APPEARANCE_NOT_CANCELABLE"));
    }

    @Test
    @DisplayName("CSRF 토큰이 없으면 403, 숫자가 아닌 출연 ID는 출연 API로 매핑되지 않는다")
    void csrfAndPath() {
        long appearanceId = establish();

        E2ELogin.withoutCsrf(GUEST, Role.INFLUENCER)
                .contentType(ContentType.JSON).body("{\"reason\": \"사유\"}")
                .post(BASE + "/" + appearanceId + "/cancel-requests")
                .then().statusCode(403);
        E2ELogin.as(GUEST, Role.INFLUENCER).get(BASE + "/abc")
                .then().statusCode(404);
    }

    @Test
    @DisplayName("호스트 일방 취소: 처리 중인 취소 요청이 있으면 확인 없이 409 CANCEL_REQUEST_CONFIRM_REQUIRED, 확인하면 200 CANCELED_BY_HOST·위약금 9,000·정산 DONE. 게스트는 403")
    void hostCancel() {
        long appearanceId = establish();
        String path = BASE + "/" + appearanceId + "/host-cancel";
        postAs(GUEST, BASE + "/" + appearanceId + "/cancel-requests", "{\"reason\": \"사유\"}")
                .then().statusCode(200);

        postAs(GUEST, path, null)
                .then().statusCode(403)
                .body("code", equalTo("NOT_APPEARANCE_HOST"));
        postAs(HOST, path, null)
                .then().statusCode(409)
                .body("code", equalTo("CANCEL_REQUEST_CONFIRM_REQUIRED"));
        postAs(HOST, path, "{\"reason\": \"방송 취소\", \"confirmPendingCancelRequest\": true}")
                .then().statusCode(200)
                .body("message", equalTo("출연을 취소했습니다."))
                .body("data.status", equalTo("CANCELED_BY_HOST"))
                .body("data.penaltyAmount", equalTo(9_000))
                .body("data.hostCancelReason", equalTo("방송 취소"))
                .body("data.hpPenaltyStatus", equalTo("DONE"))
                .body("data.cancelRequests[0].status", equalTo("CLOSED_BY_HOST_CANCEL"));
    }

    /** 방송이 끝나 확인 기간에 들어선다 (테스트 시계를 방송 예정 일시 1시간 뒤로) */
    private void broadcastEnded(long appearanceId) {
        clock.advanceTo(jdbcTemplate.queryForObject("select scheduled_at from appearances.appearances where id = ?",
                LocalDateTime.class, appearanceId).plusHours(1));
    }

    @Test
    @DisplayName("출연 확인 (#282): 방송 전 409 APPEARANCE_CONFIRM_NOT_OPEN, 게스트 403, 호스트(본문 없음) 200 CONFIRMED·HOST·지급 DONE, "
            + "다시 응답 409 APPEARANCE_NOT_CONFIRMABLE")
    void confirm() {
        long appearanceId = establish();
        String path = BASE + "/" + appearanceId + "/confirm";

        postAs(HOST, path, null)
                .then().statusCode(409)
                .body("code", equalTo("APPEARANCE_CONFIRM_NOT_OPEN"));
        broadcastEnded(appearanceId);
        postAs(GUEST, path, null)
                .then().statusCode(403)
                .body("code", equalTo("NOT_APPEARANCE_HOST"));
        postAs(HOST, path, null)
                .then().statusCode(200)
                .body("message", equalTo("출연을 확인했습니다."))
                .body("data.status", equalTo("CONFIRMED"))
                .body("data.confirmType", equalTo("HOST"))
                .body("data.confirmedAt", notNullValue())
                .body("data.hpPayoutStatus", equalTo("DONE"));
        postAs(HOST, BASE + "/" + appearanceId + "/absent", null)
                .then().statusCode(409)
                .body("code", equalTo("APPEARANCE_NOT_CONFIRMABLE"));
    }

    @Test
    @DisplayName("불참 (#282): 사유 501자 400 INVALID_REQUEST, 다른 회원 404, 호스트 200 ABSENT·사유·반환 DONE, 본문 없이도 200")
    void absent() {
        long appearanceId = establish();
        long other = establish();
        broadcastEnded(appearanceId);
        broadcastEnded(other);
        String path = BASE + "/" + appearanceId + "/absent";

        postAs(HOST, path, "{\"reason\": \"%s\"}".formatted("가".repeat(501)))
                .then().statusCode(400)
                .body("code", equalTo("INVALID_REQUEST"));
        postAs(OTHER, path, null)
                .then().statusCode(404)
                .body("code", equalTo("APPEARANCE_NOT_FOUND"));
        postAs(HOST, path, "{\"reason\": \"연락이 되지 않았습니다.\"}")
                .then().statusCode(200)
                .body("message", equalTo("불참으로 처리했습니다."))
                .body("data.status", equalTo("ABSENT"))
                .body("data.absentReason", equalTo("연락이 되지 않았습니다."))
                .body("data.absentAt", notNullValue())
                .body("data.hpReleaseStatus", equalTo("DONE"));
        postAs(HOST, BASE + "/" + other + "/absent", null)
                .then().statusCode(200)
                .body("data.status", equalTo("ABSENT"))
                .body("data.absentReason", nullValue());
    }
}
