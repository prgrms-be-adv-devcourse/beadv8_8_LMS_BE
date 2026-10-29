package com.hapbang.appearance.in;

import static com.hapbang.appearance.support.E2EMatchers.number;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;

import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.hapbang.appearance.app.AppearanceFacade;
import com.hapbang.appearance.app.AppearanceRequestFacade;
import com.hapbang.appearance.app.AppearanceUserFacade;
import com.hapbang.appearance.app.dto.AppearanceRequestResDto;
import com.hapbang.appearance.fixture.AppearanceRequestFixture;
import com.hapbang.appearance.fixture.AppearanceUserFixture;
import com.hapbang.appearance.out.AppearanceRepository;
import com.hapbang.appearance.out.billing.BillingClient;
import com.hapbang.appearance.support.AppearanceE2ETest;
import com.hapbang.appearance.support.AppearanceTestClock;
import com.hapbang.appearance.support.BillingStubs;
import com.hapbang.appearance.support.E2ELogin;
import com.hapbang.shared.internal.InternalApiHeaders;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.IntegrationTest;

import io.restassured.specification.RequestSpecification;

/**
 * 출연 수익 내부 API (#301)를 실제 서버에 RestAssured로 호출해 내부 API 보안 체인(global)부터 DB까지 관통해 확인한다. 상태 분류는 {@code AppearanceEarningQueryTest}가 맡는다.
 * <ul>
 *     <li>응답 모양은 강의 내부 API와 같다 (docs/api/internal-earnings.md): {@code RsData}, 페이지 {@code {content, page}},
 *     {@code sourceType} 문자열, null 필드 생략. 다른 점: 시각은 offset 없는 KST, 요약에 {@code asOf} 없음.</li>
 *     <li>흐름은 요청 전에 테스트 스레드에서 Facade로 만든다. Facade의 역할 검사를 통과하도록 로그인 정보는 흐름을 만들 때만 두고 지운다.
 *     요청은 서버 스레드에서 처리되고 내부 API 키만 싣는다.</li>
 * </ul>
 */
@AppearanceE2ETest
class AppearanceEarningInternalApiTest {

    private static final String SUMMARY = "/api/v1/internal/appearances/earnings/summary";
    private static final String EARNINGS = "/api/v1/internal/appearances/earnings";
    private static final Long HOST = 1L;
    private static final Long GUEST = 2L;
    /** offset 없는 ISO 날짜·시각 (예: 2026-10-20T10:00:00.123456) */
    private static final String LOCAL_DATE_TIME = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?";

    @Autowired
    AppearanceUserFacade appearanceUserFacade;

    @Autowired
    AppearanceRequestFacade appearanceRequestFacade;

    @Autowired
    AppearanceFacade appearanceFacade;

    @Autowired
    AppearanceRepository appearanceRepository;

    @Autowired
    BillingClient billingClient;

    @Autowired
    AppearanceTestClock clock;

    @BeforeEach
    void setUp() {
        for (Long id : List.of(HOST, GUEST)) {
            appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(id));
        }
    }

    /** 확인 대기 20,000 1건 + 처리 중(확인했지만 Billing 결과 모름) 22,000 1건. 처리 중 출연 ID */
    private Long pendingAndProcessing() {
        return asInfluencer(() -> {
            establish(20_000);
            Long processing = establish(22_000);
            clock.advanceTo(appearanceRepository.findById(processing).orElseThrow().getScheduledAt().plusHours(1));
            BillingStubs.unavailable(billingClient);
            appearanceFacade.confirm(HOST, processing);
            BillingStubs.available(billingClient);
            return processing;
        });
    }

    private Long establish(long fee) {
        AppearanceRequestResDto sent = appearanceRequestFacade.create(HOST, GUEST,
                AppearanceRequestFixture.content(clock.now(), fee));
        return appearanceRequestFacade.accept(GUEST, sent.id(), sent.version()).appearanceId();
    }

    /** 흐름을 만드는 동안만 인플루언서로 로그인해 둔다 */
    private static <T> T asInfluencer(Supplier<T> flow) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "influencer", null, List.of(new SimpleGrantedAuthority("ROLE_INFLUENCER"))));
        try {
            return flow.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static RequestSpecification internal() {
        return given().header(InternalApiHeaders.API_KEY, IntegrationTest.INTERNAL_API_KEY);
    }


    @Test
    @DisplayName("요약: 내부 키로 상태별 {amountHp, count}를 RsData로 준다. 지급 예정은 0, asOf는 없다")
    void summary() {
        pendingAndProcessing();

        internal().queryParam("userId", GUEST).get(SUMMARY)
                .then().statusCode(200)
                .body("statusCode", equalTo(200))
                .body("message", equalTo("출연 수익 요약을 조회했습니다."))
                .body("data.confirmationPending.amountHp", equalTo(20_000))
                .body("data.confirmationPending.count", equalTo(1))
                .body("data.scheduled.amountHp", equalTo(0))
                .body("data.scheduled.count", equalTo(0))
                .body("data.onHold.count", equalTo(0))
                .body("data.processing.amountHp", equalTo(22_000))
                .body("data.processing.count", equalTo(1))
                .body("data.failed.count", equalTo(0))
                .body("data.asOf", nullValue());
        // 종류 필터: 위약금만 고르면 0
        internal().queryParam("userId", GUEST).queryParam("earningType", "APPEARANCE_PENALTY").get(SUMMARY)
                .then().statusCode(200)
                .body("data.confirmationPending.count", equalTo(0))
                .body("data.processing.count", equalTo(0));
    }

    @Test
    @DisplayName("목록: 강의와 같은 필드·페이지 모양. sourceType APPEARANCE, 시각은 offset 없는 KST, 지급 예정 시각(null)은 응답에서 빠진다")
    void earnings() {
        Long processing = pendingAndProcessing();

        internal().queryParam("userId", GUEST).queryParam("status", "PROCESSING", "COMPLETED")
                .queryParam("earningType", "APPEARANCE_FEE").get(EARNINGS)
                .then().statusCode(200)
                .body("message", equalTo("출연 수익 목록을 조회했습니다."))
                .body("data.page.totalElements", equalTo(1))
                .body("data.content[0].sourceType", equalTo("APPEARANCE"))
                .body("data.content[0].sourceId", number(processing))
                .body("data.content[0].earningType", equalTo("APPEARANCE_FEE"))
                .body("data.content[0].title", equalTo(AppearanceRequestFixture.content(clock.now()).title()))
                .body("data.content[0].amountHp", equalTo(22_000))
                .body("data.content[0].status", equalTo("PROCESSING"))
                .body("data.content[0].payoutScheduledAt", nullValue())
                .body("data.content[0].statusUpdatedAt", matchesPattern(LOCAL_DATE_TIME));
    }

    @Test
    @DisplayName("상태를 주지 않거나 값 없이 주면 아직 받지 못한 수익만, page·size로 나눈다. 출연에 없는 상태(SCHEDULED)만 고르면 빈 결과")
    void defaultAndPaging() {
        Long processing = pendingAndProcessing();

        internal().queryParam("userId", GUEST).queryParam("size", 1).get(EARNINGS)
                .then().statusCode(200)
                .body("data.content.size()", equalTo(1))
                // 확인한 출연이 상태 기준 시각(확인 시각)이 가장 최근이라 먼저다
                .body("data.content[0].sourceId", number(processing))
                .body("data.page.size", equalTo(1))
                .body("data.page.number", equalTo(0))
                .body("data.page.totalElements", equalTo(2))
                .body("data.page.totalPages", equalTo(2));
        // 값 없는 status(?status=)는 Spring이 빈 목록으로 바꿔 고르지 않은 것과 같다
        internal().queryParam("userId", GUEST).queryParam("status", "").get(EARNINGS)
                .then().statusCode(200)
                .body("data.page.totalElements", equalTo(2));
        internal().queryParam("userId", GUEST).queryParam("status", "SCHEDULED").get(EARNINGS)
                .then().statusCode(200)
                .body("data.content.size()", equalTo(0))
                .body("data.page.totalElements", equalTo(0));
    }

    @Test
    @DisplayName("출연 기록이 없는 회원은 0원 요약·빈 목록")
    void unknownMember() {
        internal().queryParam("userId", 999).get(SUMMARY)
                .then().statusCode(200)
                .body("data.confirmationPending.count", equalTo(0))
                .body("data.failed.amountHp", equalTo(0));
        internal().queryParam("userId", 999).get(EARNINGS)
                .then().statusCode(200)
                .body("data.content.size()", equalTo(0));
    }

    @Test
    @DisplayName("userId가 없거나 1 미만, status·earningType이 계약에 없는 값이면 400 INVALID_REQUEST")
    void invalidRequest() {
        internal().get(SUMMARY)
                .then().statusCode(400)
                .body("code", equalTo("INVALID_REQUEST"))
                .body("errors[0].field", equalTo("userId"));
        internal().queryParam("userId", 0).get(EARNINGS)
                .then().statusCode(400)
                .body("code", equalTo("INVALID_REQUEST"));
        internal().queryParam("userId", GUEST).queryParam("status", "PAID").get(EARNINGS)
                .then().statusCode(400)
                .body("code", equalTo("INVALID_REQUEST"))
                .body("errors[0].field", equalTo("status"));
        // 다른 모듈의 종류(강의 LECTURE_SALE)도 출연 계약에 없는 값이다
        internal().queryParam("userId", GUEST).queryParam("earningType", "LECTURE_SALE").get(EARNINGS)
                .then().statusCode(400)
                .body("code", equalTo("INVALID_REQUEST"))
                .body("errors[0].field", equalTo("earningType"));
    }

    @Test
    @DisplayName("내부 키가 없거나 틀리면 본문 없는 401, 회원 로그인만으로도 401")
    void keyRequired() {
        given().queryParam("userId", GUEST).get(SUMMARY)
                .then().statusCode(401)
                .body(equalTo(""));
        given().queryParam("userId", GUEST)
                .header(InternalApiHeaders.API_KEY, IntegrationTest.INTERNAL_API_KEY + "x")
                .get(EARNINGS)
                .then().statusCode(401);
        E2ELogin.as(GUEST, Role.INFLUENCER).queryParam("userId", GUEST).get(EARNINGS)
                .then().statusCode(401);
    }

    @Test
    @DisplayName("내부 키로는 회원용 출연 API를 부를 수 없다")
    void keyIsNotLogin() {
        internal().queryParam("role", "GUEST").get("/api/v1/appearances")
                .then().statusCode(401);
    }
}
