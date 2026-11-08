package com.hapbang.appearance.in;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;

import com.hapbang.appearance.app.AppearanceFacade;
import com.hapbang.appearance.app.AppearanceRequestFacade;
import com.hapbang.appearance.app.AppearanceReviewFacade;
import com.hapbang.appearance.app.AppearanceUserFacade;
import com.hapbang.appearance.app.dto.AppearanceRequestResDto;
import com.hapbang.appearance.fixture.AppearanceRequestFixture;
import com.hapbang.appearance.fixture.AppearanceUserFixture;
import com.hapbang.appearance.support.AppearanceE2ETest;
import com.hapbang.appearance.support.AppearanceTestClock;
import com.hapbang.appearance.support.E2ELogin;
import com.hapbang.shared.internal.InternalApiHeaders;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.IntegrationTest;

import io.restassured.response.ValidatableResponse;

/**
 * 실제 서버(보안 필터, 내부 API 키 체인, 메서드 보안, DB)를 띄우고 RestAssured로 출연 동기화 내부 API를 호출한다 (#713).
 * AI 추천이 주 1회 처음부터 끝까지 읽어 이벤트 누락을 바로잡는다 (docs/api/internal-appearance-sync.md).
 * 이벤트와 값이 같은지는 같은 DTO 생성 메서드를 쓰는 {@code AppearanceChangedEventTest}가 본다.
 * 외부 포트로 오면 404인 것은 경로 접두사({@code /api/v1/internal})로 막으므로 {@code InternalApiPortConfigTest}가 맡는다.
 */
@AppearanceE2ETest
@WithMockUser(roles = "INFLUENCER")
class AppearanceSyncInternalApiE2ETest {

    private static final String APPEARANCES = "/api/v1/internal/appearances/sync/appearances";
    private static final String REVIEWS = "/api/v1/internal/appearances/sync/reviews";
    private static final long HOST = 1L;
    private static final long GUEST = 2L;
    private static final long GUEST2 = 3L;
    private static final long GUEST3 = 4L;

    @Autowired
    AppearanceUserFacade appearanceUserFacade;

    @Autowired
    AppearanceRequestFacade appearanceRequestFacade;

    @Autowired
    AppearanceFacade appearanceFacade;

    @Autowired
    AppearanceReviewFacade appearanceReviewFacade;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    AppearanceTestClock clock;

    @BeforeEach
    void setUp() {
        for (long id : List.of(HOST, GUEST, GUEST2, GUEST3)) {
            appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(id));
        }
    }

    /** 요청서를 보내고 게스트가 수락해 성립한 출연 ID */
    private long establish(long guestId) {
        AppearanceRequestResDto sent = appearanceRequestFacade.create(HOST, guestId,
                AppearanceRequestFixture.content(clock.now()));
        return appearanceRequestFacade.accept(guestId, sent.id(), sent.version()).appearanceId();
    }

    /** 방송이 끝나 확인·리뷰 기간에 들어선다 */
    private void broadcastEnded(long appearanceId) {
        clock.advanceTo(jdbcTemplate.queryForObject("select scheduled_at from appearances.appearances where id = ?",
                LocalDateTime.class, appearanceId).plusHours(1));
    }

    private static ValidatableResponse page(String path, Object afterId, Object size) {
        var request = given().header(InternalApiHeaders.API_KEY, IntegrationTest.INTERNAL_API_KEY);
        if (afterId != null) {
            request = request.queryParam("afterId", afterId);
        }
        if (size != null) {
            request = request.queryParam("size", size);
        }
        return request.get(path).then().statusCode(200).body("statusCode", equalTo(200));
    }

    @Test
    @DisplayName("출연: ID 오름차순 키셋, 다음 페이지 키는 마지막 항목 ID, 마지막 페이지는 null. 모든 상태가 공개 결과로 나오고 금액은 없다")
    void appearances() {
        long scheduled = establish(GUEST);
        long canceled = establish(GUEST2);
        appearanceFacade.hostCancel(HOST, canceled, false, null);
        long cancelRequested = establish(GUEST3);
        appearanceFacade.requestCancel(GUEST3, cancelRequested, "일정이 생겼습니다.");

        page(APPEARANCES, null, 2)
                .body("message", equalTo("출연 동기화 목록을 조회했습니다."))
                .body("data.items.id", contains((int) scheduled, (int) canceled))
                .body("data.items.result", contains("SCHEDULED", "CANCELED"))
                .body("data.items.version", contains(0, 1))
                .body("data.items.hostId", everyItem(equalTo((int) HOST)))
                .body("data.items.guestId", contains((int) GUEST, (int) GUEST2))
                .body("data.items.scheduledAt", everyItem(notNullValue()))
                .body("data.items[0]", not(hasKey("fee")))
                .body("data.nextAfterId", equalTo((int) canceled));
        // 취소 요청 중은 아직 성립한 출연이다
        page(APPEARANCES, canceled, 2)
                .body("data.items.id", contains((int) cancelRequested))
                .body("data.items.result", contains("SCHEDULED"))
                .body("data.items.version", contains(1))
                .body("data.nextAfterId", nullValue());
        // 생략하면 처음부터 100개
        page(APPEARANCES, null, null)
                .body("data.items.id", contains((int) scheduled, (int) canceled, (int) cancelRequested))
                .body("data.nextAfterId", nullValue());
        // 딱 맞게 끝나면 다음 페이지 키가 없다 (size+1개를 읽어 판단)
        page(APPEARANCES, 0, 3).body("data.nextAfterId", nullValue());
        page(APPEARANCES, cancelRequested, 100)
                .body("data.items", empty())
                .body("data.nextAfterId", nullValue());
    }

    @Test
    @DisplayName("리뷰: 공개된 것만 ID 오름차순. 공개 전 리뷰가 사이에 있어도 키셋이 끊기지 않는다. 본문·쓴 사람은 없다")
    void reviews() {
        long both = establish(GUEST);
        long hostOnly = establish(GUEST2);
        broadcastEnded(both);
        appearanceFacade.confirm(HOST, both);
        appearanceFacade.confirm(HOST, hostOnly);
        long first = appearanceReviewFacade.write(HOST, both, 5, "본문은 나가지 않음").id();
        long unpublished = appearanceReviewFacade.write(HOST, hostOnly, 2, null).id(); // 상대가 쓰지 않아 공개 전
        long second = appearanceReviewFacade.write(GUEST, both, 3, null).id(); // 두 리뷰가 함께 공개된다

        page(REVIEWS, null, 1)
                .body("message", equalTo("공개된 출연 리뷰 동기화 목록을 조회했습니다."))
                .body("data.items.reviewId", contains((int) first))
                .body("data.items.appearanceId", contains((int) both))
                .body("data.items.revieweeUserId", contains((int) GUEST))
                .body("data.items.rating", contains(5))
                .body("data.items[0]", not(hasKey("content")))
                .body("data.items[0]", not(hasKey("reviewerUserId")))
                .body("data.nextAfterId", equalTo((int) first));
        // 다음 페이지는 공개 전 리뷰를 건너뛰고 이어진다
        page(REVIEWS, first, 1)
                .body("data.items.reviewId", contains((int) second))
                .body("data.items.revieweeUserId", contains((int) HOST))
                .body("data.items.rating", contains(3))
                .body("data.nextAfterId", nullValue());
        page(REVIEWS, null, null)
                .body("data.items.reviewId", contains((int) first, (int) second))
                .body("data.items.reviewId", not(hasItem((int) unpublished)));
    }

    @Test
    @DisplayName("size 1~500 밖·afterId 음수·숫자 아님 400 INVALID_REQUEST")
    void invalidQuery() {
        for (String path : List.of(APPEARANCES, REVIEWS)) {
            for (Object size : List.of(0, 501, "abc")) {
                given().header(InternalApiHeaders.API_KEY, IntegrationTest.INTERNAL_API_KEY)
                        .queryParam("size", size)
                        .get(path)
                        .then().statusCode(400)
                        .body("code", equalTo("INVALID_REQUEST"));
            }
            given().header(InternalApiHeaders.API_KEY, IntegrationTest.INTERNAL_API_KEY)
                    .queryParam("afterId", -1)
                    .get(path)
                    .then().statusCode(400)
                    .body("code", equalTo("INVALID_REQUEST"));
        }
        // 최대 크기는 받는다
        page(APPEARANCES, null, 500);
        page(REVIEWS, null, 500);
    }

    @Test
    @DisplayName("내부 키가 없거나 틀리면 본문 없는 401, 회원 로그인만으로도 401")
    void keyRequired() {
        for (String path : List.of(APPEARANCES, REVIEWS)) {
            given().get(path)
                    .then().statusCode(401)
                    .body(equalTo(""));
            given().header(InternalApiHeaders.API_KEY, IntegrationTest.INTERNAL_API_KEY + "x").get(path)
                    .then().statusCode(401);
            E2ELogin.as(HOST, Role.INFLUENCER).get(path)
                    .then().statusCode(401);
        }
    }
}
