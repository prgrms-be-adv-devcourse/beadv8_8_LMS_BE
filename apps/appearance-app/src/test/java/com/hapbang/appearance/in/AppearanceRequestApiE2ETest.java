package com.hapbang.appearance.in;

import static com.hapbang.appearance.support.E2EMatchers.number;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

import java.time.Clock;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;

import com.hapbang.appearance.app.AppearanceUserFacade;
import com.hapbang.appearance.app.AppearanceUserSupport;
import com.hapbang.appearance.config.AppearanceConfig;
import com.hapbang.appearance.fixture.AppearanceUserFixture;
import com.hapbang.appearance.out.billing.BillingClient;
import com.hapbang.appearance.support.AppearanceE2ETest;
import com.hapbang.appearance.support.BillingStubs;
import com.hapbang.appearance.support.BillingStubs.Rejection;
import com.hapbang.appearance.support.E2ELogin;
import com.hapbang.shared.member.domain.Role;

import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

/**
 * 실제 서버(보안 필터, Gateway 사용자 헤더 로그인, 메서드 보안, 서비스, DB, Billing 대역)를 띄우고 RestAssured로 출연 요청서 API를 호출한다.
 * 로그인(Gateway 사용자 헤더)은 {@link E2ELogin}이 만든다.
 */
@AppearanceE2ETest
class AppearanceRequestApiE2ETest {

    private static final String BASE = "/api/v1/appearances/requests";
    private static final long HOST = 1L;
    private static final long GUEST = 2L;
    private static final long OTHER = 3L;

    @Autowired
    AppearanceUserFacade appearanceUserFacade;

    @Autowired
    AppearanceUserSupport appearanceUserSupport;

    @Autowired
    BillingClient billingClient;

    @Autowired
    @Qualifier(AppearanceConfig.CLOCK)
    Clock clock;

    @BeforeEach
    void setUp() {
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(HOST));
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(GUEST));
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(OTHER));
    }


    private String createBody(long guestId, long fee) {
        LocalDateTime now = LocalDateTime.now(clock);
        return """
                {
                  "guestId": %d,
                  "title": "주말 게임 합방 출연 요청",
                  "hostChannelUrl": "https://www.youtube.com/@host",
                  "scheduledAt": "%s",
                  "expectedMinutes": 60,
                  "content": "같이 신작 게임을 플레이하는 콘텐츠입니다.",
                  "fee": %d,
                  "acceptDeadline": "%s"
                }
                """.formatted(guestId, now.plusDays(7).withNano(0), fee, now.plusDays(2).withNano(0));
    }

    private RequestSpecification createRequest(long hostId, String body) {
        return E2ELogin.as(hostId, Role.INFLUENCER).contentType(ContentType.JSON).body(body);
    }

    private long sendRequest(long hostId, long guestId) {
        return createRequest(hostId, createBody(guestId, 30_000)).post(BASE)
                .then().statusCode(201)
                .extract().jsonPath().getLong("data.id");
    }

    @Test
    @DisplayName("요청서 보내기 → 보낸·받은 목록 → 상세 → 수정 흐름 (성공 응답은 RsData: statusCode, message, data)")
    void fullFlow() {
        // 보내기: HTTP 201, 본문 statusCode 201, data에 요청서
        long requestId = createRequest(HOST, createBody(GUEST, 30_000)).post(BASE)
                .then().statusCode(201)
                .body("statusCode", equalTo(201))
                .body("message", equalTo("출연 요청을 보냈습니다."))
                .body("data.status", equalTo("REQUESTED"))
                .extract().jsonPath().getLong("data.id");

        // 보낸 목록 (호스트): data에 PagedModel(content, page)
        E2ELogin.as(HOST, Role.INFLUENCER).get(BASE + "/sent")
                .then().statusCode(200)
                .body("statusCode", equalTo(200))
                .body("message", equalTo("보낸 출연 요청 목록을 조회했습니다."))
                .body("data.content[0].id", number(requestId))
                .body("data.content[0].status", equalTo("REQUESTED"))
                .body("data.page.totalElements", equalTo(1));

        // 받은 목록 (게스트)
        E2ELogin.as(GUEST, Role.VERIFIED_INFLUENCER).get(BASE + "/received")
                .then().statusCode(200)
                .body("message", equalTo("받은 출연 요청 목록을 조회했습니다."))
                .body("data.content[0].id", number(requestId))
                .body("data.content[0].hostId", number(HOST));

        // 상세 (게스트)
        E2ELogin.as(GUEST, Role.INFLUENCER).get(BASE + "/" + requestId)
                .then().statusCode(200)
                .body("message", equalTo("출연 요청서를 조회했습니다."))
                .body("data.title", equalTo("주말 게임 합방 출연 요청"))
                .body("data.fee", equalTo(30_000))
                .body("data.expectedMinutes", equalTo(60));

        // 수정 (호스트): 보낸 값만 바뀐다
        E2ELogin.as(HOST, Role.INFLUENCER).contentType(ContentType.JSON)
                .body("""
                        {"title": "제목 수정", "hostChannelUrl": "https://chzzk.naver.com/host"}
                        """)
                .patch(BASE + "/" + requestId)
                .then().statusCode(200)
                .body("message", equalTo("출연 요청서를 수정했습니다."))
                .body("data.title", equalTo("제목 수정"))
                .body("data.hostChannelUrl", equalTo("https://chzzk.naver.com/host"))
                .body("data.content", equalTo("같이 신작 게임을 플레이하는 콘텐츠입니다."));
    }

    @Test
    @DisplayName("로그인하지 않으면 401")
    void notLoggedIn() {
        given().get(BASE + "/sent")
                .then().statusCode(401);
    }

    @Test
    @DisplayName("일반 유저·관리자 로그인이면 Facade @PreAuthorize에서 403 Problem Detail (공통 FORBIDDEN)")
    void userRole() {
        E2ELogin.as(HOST, Role.USER).get(BASE + "/sent")
                .then().statusCode(403)
                .contentType(startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .body("code", equalTo("FORBIDDEN"));
        E2ELogin.as(HOST, Role.ADMIN).contentType(ContentType.JSON).body(createBody(GUEST, 30_000)).post(BASE)
                .then().statusCode(403)
                .body("code", equalTo("FORBIDDEN"));
    }

    @Test
    @DisplayName("요청 값이 형식에 맞지 않으면 400 Problem Detail (INVALID_REQUEST)에 항목별 오류를 담는다")
    void invalidBody() {
        createRequest(HOST, """
                {"guestId": 2, "title": " ", "hostChannelUrl": "youtube.com"}
                """).post(BASE)
                .then().statusCode(400)
                .body("code", equalTo("INVALID_REQUEST"))
                .body("errors.field", hasItem("title"))
                .body("errors.field", hasItem("hostChannelUrl"))
                .body("errors.field", hasItem("fee"));
    }

    @Test
    @DisplayName("최저 출연료 미달이면 400 (FEE_BELOW_MINIMUM)")
    void feeBelowMinimum() {
        createRequest(HOST, createBody(GUEST, 19_999)).post(BASE)
                .then().statusCode(400)
                .contentType(startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .body("data", nullValue())
                .body("code", equalTo("FEE_BELOW_MINIMUM"))
                .body("detail", equalTo("출연료는 최저 출연료(20,000 HP) 이상이어야 합니다."));
    }

    @Test
    @DisplayName("지갑이 잔액 부족으로 보류를 거절하면 409 INSUFFICIENT_BALANCE (문구는 출연이 정함)")
    void insufficientBalance() {
        BillingStubs.reject(billingClient, Rejection.INSUFFICIENT_BALANCE);

        createRequest(HOST, createBody(GUEST, 30_000)).post(BASE)
                .then().statusCode(409)
                .body("code", equalTo("INSUFFICIENT_BALANCE"))
                .body("detail", equalTo("HP 잔액이 부족합니다. 충전한 뒤 다시 시도해 주세요."));
    }

    @Test
    @DisplayName("응답 대기 중인 요청이 있는 게스트에게 다시 보내면 409 (DUPLICATE_APPEARANCE_REQUEST)")
    void duplicate() {
        sendRequest(HOST, GUEST);

        createRequest(HOST, createBody(GUEST, 30_000)).post(BASE)
                .then().statusCode(409)
                .body("code", equalTo("DUPLICATE_APPEARANCE_REQUEST"));
    }

    @Test
    @DisplayName("Billing이 응답하지 않으면 503 (BILLING_UNAVAILABLE)")
    void billingUnavailable() {
        BillingStubs.unavailable(billingClient);

        createRequest(HOST, createBody(GUEST, 30_000)).post(BASE)
                .then().statusCode(503)
                .body("code", equalTo("BILLING_UNAVAILABLE"));
    }

    @Test
    @DisplayName("다른 회원이 상세를 조회하면 404, 게스트가 수정하면 403 (NOT_REQUEST_HOST)")
    void permission() {
        long requestId = sendRequest(HOST, GUEST);

        E2ELogin.as(OTHER, Role.INFLUENCER).get(BASE + "/" + requestId)
                .then().statusCode(404)
                .body("code", equalTo("APPEARANCE_REQUEST_NOT_FOUND"));

        E2ELogin.as(GUEST, Role.INFLUENCER).contentType(ContentType.JSON).body("{\"title\": \"게스트가 바꾼 제목\"}")
                .patch(BASE + "/" + requestId)
                .then().statusCode(403)
                .body("code", equalTo("NOT_REQUEST_HOST"));
    }

    @Test
    @DisplayName("수정할 값을 하나도 보내지 않으면 400 (INVALID_REQUEST)")
    void updateNothing() {
        long requestId = sendRequest(HOST, GUEST);

        E2ELogin.as(HOST, Role.INFLUENCER).contentType(ContentType.JSON).body("{}")
                .patch(BASE + "/" + requestId)
                .then().statusCode(400)
                .body("code", equalTo("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("사용자 헤더 없이 보내면 401, 형식이 맞지 않으면(역할 없음) 401 (보안 필터)")
    void withoutUserHeaders() {
        given().contentType(ContentType.JSON).body(createBody(GUEST, 30_000))
                .post(BASE)
                .then().statusCode(401);
        given().contentType(ContentType.JSON).header("X-User-Id", String.valueOf(HOST)).body(createBody(GUEST, 30_000))
                .post(BASE)
                .then().statusCode(401);
    }
}
