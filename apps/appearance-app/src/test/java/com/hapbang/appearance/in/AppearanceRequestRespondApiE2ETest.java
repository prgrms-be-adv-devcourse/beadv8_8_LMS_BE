package com.hapbang.appearance.in;

import static com.hapbang.appearance.support.E2EMatchers.number;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.notNullValue;

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
import com.hapbang.appearance.support.E2ELogin;
import com.hapbang.shared.member.domain.Role;

import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;

/**
 * 출연 요청 응답 API(수락·거절·응답 전 취소)와 목록 상태 필터를 실제 서버에 RestAssured로 호출한다. 성공 응답은 RsData다.
 */
@AppearanceE2ETest
class AppearanceRequestRespondApiE2ETest {

    private static final String BASE = "/api/v1/appearances/requests";
    private static final long HOST = 1L;
    private static final long GUEST = 2L;

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
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(GUEST));
    }


    /** 요청서를 보내고 응답의 {id, version}을 돌려준다 */
    private long[] send() {
        LocalDateTime now = LocalDateTime.now(clock);
        String body = """
                {"guestId": %d, "title": "주말 게임 합방 출연 요청", "hostChannelUrl": "https://www.youtube.com/@host",
                 "scheduledAt": "%s", "expectedMinutes": 60, "content": "같이 플레이하는 콘텐츠입니다.",
                 "fee": 30000, "acceptDeadline": "%s"}
                """.formatted(GUEST, now.plusDays(7).withNano(0), now.plusDays(2).withNano(0));
        JsonPath response = E2ELogin.as(HOST, Role.INFLUENCER).contentType(ContentType.JSON).body(body).post(BASE)
                .then().statusCode(201)
                .extract().jsonPath();
        return new long[] {response.getLong("data.id"), response.getLong("data.version")};
    }

    private Response postAs(long userId, String path, String body) {
        return E2ELogin.as(userId, Role.INFLUENCER).contentType(ContentType.JSON).body(body).post(BASE + path);
    }

    @Test
    @DisplayName("보내기 응답의 version으로 수락하면 200, ACCEPTED, 성립한 출연 ID")
    void accept() {
        long[] sent = send();

        postAs(GUEST, "/" + sent[0] + "/accept", "{\"version\": " + sent[1] + "}")
                .then().statusCode(200)
                .body("statusCode", equalTo(200))
                .body("message", equalTo("출연 요청을 수락했습니다."))
                .body("data.status", equalTo("ACCEPTED"))
                .body("data.appearanceId", instanceOf(Number.class))
                .body("data.respondedAt", notNullValue());
    }

    @Test
    @DisplayName("본 뒤 호스트가 수정했으면 409 APPEARANCE_REQUEST_MODIFIED, version이 없으면 400")
    void acceptModifiedOrMissingVersion() {
        long[] sent = send();
        E2ELogin.as(HOST, Role.INFLUENCER).contentType(ContentType.JSON).body("{\"title\": \"바뀐 제목\"}")
                .patch(BASE + "/" + sent[0])
                .then().statusCode(200);

        postAs(GUEST, "/" + sent[0] + "/accept", "{\"version\": " + sent[1] + "}")
                .then().statusCode(409)
                .body("code", equalTo("APPEARANCE_REQUEST_MODIFIED"));
        postAs(GUEST, "/" + sent[0] + "/accept", "{}")
                .then().statusCode(400)
                .body("code", equalTo("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("사유와 함께 거절하면 200, REJECTED, 사유, 반환 완료(DONE)")
    void reject() {
        long[] sent = send();

        postAs(GUEST, "/" + sent[0] + "/reject", "{\"reason\": \"다른 방송 일정이 있습니다.\"}")
                .then().statusCode(200)
                .body("message", equalTo("출연 요청을 거절했습니다."))
                .body("data.status", equalTo("REJECTED"))
                .body("data.rejectReason", equalTo("다른 방송 일정이 있습니다."))
                .body("data.hpReleaseStatus", equalTo("DONE"));
    }

    @Test
    @DisplayName("반환이 실패해도 거절은 200이고 반환 상태는 PENDING, 사유 501자는 400")
    void rejectReleasePendingOrTooLong() {
        long[] sent = send();
        postAs(GUEST, "/" + sent[0] + "/reject", "{\"reason\": \"" + "가".repeat(501) + "\"}")
                .then().statusCode(400)
                .body("code", equalTo("INVALID_REQUEST"));

        BillingStubs.unavailable(billingClient);
        postAs(GUEST, "/" + sent[0] + "/reject", "{}")
                .then().statusCode(200)
                .body("data.status", equalTo("REJECTED"))
                .body("data.hpReleaseStatus", equalTo("PENDING"));
    }

    @Test
    @DisplayName("호스트가 응답 전 취소하면 200, CANCELED, 반환 완료. 게스트가 취소하면 403 NOT_REQUEST_HOST")
    void cancel() {
        long[] sent = send();

        E2ELogin.as(GUEST, Role.INFLUENCER).post(BASE + "/" + sent[0] + "/cancel")
                .then().statusCode(403)
                .body("code", equalTo("NOT_REQUEST_HOST"));
        E2ELogin.as(HOST, Role.INFLUENCER).post(BASE + "/" + sent[0] + "/cancel")
                .then().statusCode(200)
                .body("message", equalTo("출연 요청을 취소했습니다."))
                .body("data.status", equalTo("CANCELED"))
                .body("data.hpReleaseStatus", equalTo("DONE"));
    }

    @Test
    @DisplayName("이미 거절한 요청서를 수락하면 409 APPEARANCE_REQUEST_NOT_AWAITING_RESPONSE")
    void respondAfterRejected() {
        long[] sent = send();
        postAs(GUEST, "/" + sent[0] + "/reject", "{}").then().statusCode(200);

        postAs(GUEST, "/" + sent[0] + "/accept", "{\"version\": " + sent[1] + "}")
                .then().statusCode(409)
                .body("code", equalTo("APPEARANCE_REQUEST_NOT_AWAITING_RESPONSE"));
    }

    @Test
    @DisplayName("목록 상태 필터: status를 여러 번 보내면 그 상태만, 숨김 상태나 없는 값이면 400")
    void statusFilter() {
        long[] rejected = send();
        postAs(GUEST, "/" + rejected[0] + "/reject", "{}").then().statusCode(200);

        E2ELogin.as(GUEST, Role.INFLUENCER).queryParam("status", "REJECTED", "CANCELED").get(BASE + "/received")
                .then().statusCode(200)
                .body("data.content[0].id", number(rejected[0]))
                .body("data.page.totalElements", equalTo(1));
        E2ELogin.as(GUEST, Role.INFLUENCER).queryParam("status", "REQUESTED").get(BASE + "/received")
                .then().statusCode(200)
                .body("data.page.totalElements", equalTo(0));
        E2ELogin.as(GUEST, Role.INFLUENCER).queryParam("status", "HOLD_FAILED").get(BASE + "/received")
                .then().statusCode(400)
                .body("code", equalTo("INVALID_REQUEST"));
        E2ELogin.as(GUEST, Role.INFLUENCER).queryParam("status", "UNKNOWN").get(BASE + "/received")
                .then().statusCode(400)
                .body("code", equalTo("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("일반 유저 세션으로 수락하면 403 FORBIDDEN (Facade @PreAuthorize)")
    void userRole() {
        long[] sent = send();

        E2ELogin.as(GUEST, Role.USER).contentType(ContentType.JSON).body("{\"version\": " + sent[1] + "}")
                .post(BASE + "/" + sent[0] + "/accept")
                .then().statusCode(403)
                .body("code", equalTo("FORBIDDEN"));
    }
}
