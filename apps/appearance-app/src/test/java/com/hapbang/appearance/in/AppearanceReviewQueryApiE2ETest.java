package com.hapbang.appearance.in;

import static com.hapbang.appearance.support.E2EMatchers.number;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import java.time.Duration;
import java.time.LocalDateTime;

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

import io.restassured.specification.RequestSpecification;

/**
 * 실제 서버(보안 필터, Gateway 사용자 헤더 로그인, 내부 API 키 체인, 메서드 보안, DB)를 띄우고 RestAssured로 출연 리뷰 조회를 호출한다 (#514).
 * <ul>
 *     <li>출연 상세(회원 API)의 {@code reviewable}·{@code myReview}·{@code receivedReview} JSON 모양</li>
 *     <li>회원이 받은 공개 리뷰 목록(내부 API): 공개 전 제외, 최신 공개순, 페이징, 내부 키</li>
 * </ul>
 * 출연 성립·확인·리뷰 작성은 Facade로 준비한다 (테스트 스레드 역할은 {@code @WithMockUser}).
 * 상세의 리뷰 규칙(블라인드·정지·작성 기한)은 {@code AppearanceReviewDetailTest}가 맡는다.
 */
@AppearanceE2ETest
@WithMockUser(roles = "INFLUENCER")
class AppearanceReviewQueryApiE2ETest {

    private static final String BASE = "/api/v1/appearances";
    private static final long HOST = 1L;
    private static final long GUEST = 2L;
    /** offset 없는 ISO 날짜·시각 (예: 2026-10-20T10:00:00.123456) */
    private static final String LOCAL_DATE_TIME = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?";

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
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(HOST));
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(GUEST));
    }

    /** 요청서가 수락돼 성립한 출연 ID */
    private long establish() {
        AppearanceRequestResDto sent = appearanceRequestFacade.create(HOST, GUEST,
                AppearanceRequestFixture.content(LocalDateTime.now(clock)));
        return appearanceRequestFacade.accept(GUEST, sent.id(), sent.version()).appearanceId();
    }

    /** 방송이 끝난 뒤 호스트가 확인한다. 같은 시각에 만든 출연은 방송 예정 일시가 같다 */
    private void confirm(long... appearanceIds) {
        clock.advanceTo(jdbcTemplate.queryForObject("select scheduled_at from appearances.appearances where id = ?",
                LocalDateTime.class, appearanceIds[0]).plusHours(1));
        for (long appearanceId : appearanceIds) {
            appearanceFacade.confirm(HOST, appearanceId);
        }
    }

    private long write(long reviewerId, long appearanceId, int rating, String content) {
        return appearanceReviewFacade.write(reviewerId, appearanceId, rating, content).id();
    }

    private static String receivedPath(long userId) {
        return "/api/v1/internal/appearances/users/" + userId + "/reviews";
    }

    private static RequestSpecification internal() {
        return given().header(InternalApiHeaders.API_KEY, IntegrationTest.INTERNAL_API_KEY);
    }

    @Test
    @DisplayName("출연 상세: 한쪽만 쓰면 내 리뷰는 공개 전, 받은 리뷰는 null → 둘 다 쓰면 받은 리뷰가 채워진다")
    void detail() {
        long appearanceId = establish();
        confirm(appearanceId);
        E2ELogin.as(GUEST, Role.INFLUENCER).get(BASE + "/" + appearanceId)
                .then().statusCode(200)
                .body("data.reviewable", equalTo(true))
                .body("data", hasKey("myReview"))
                .body("data.myReview", nullValue())
                .body("data.receivedReview", nullValue());

        long guestReviewId = write(GUEST, appearanceId, 5, null);
        E2ELogin.as(GUEST, Role.INFLUENCER).get(BASE + "/" + appearanceId)
                .then().statusCode(200)
                .body("data.reviewable", equalTo(false))
                .body("data.myReview.id", number(guestReviewId))
                .body("data.myReview.rating", equalTo(5))
                .body("data.myReview", hasKey("content"))
                .body("data.myReview.content", nullValue())
                .body("data.myReview.published", equalTo(false))
                .body("data.myReview.publishedAt", nullValue())
                .body("data.myReview.createdAt", matchesPattern(LOCAL_DATE_TIME))
                .body("data.receivedReview", nullValue());

        long hostReviewId = write(HOST, appearanceId, 4, "준비가 꼼꼼했습니다.");
        E2ELogin.as(GUEST, Role.INFLUENCER).get(BASE + "/" + appearanceId)
                .then().statusCode(200)
                .body("data.myReview.published", equalTo(true))
                .body("data.myReview.publishedAt", matchesPattern(LOCAL_DATE_TIME))
                .body("data.receivedReview.id", number(hostReviewId))
                .body("data.receivedReview.rating", equalTo(4))
                .body("data.receivedReview.content", equalTo("준비가 꼼꼼했습니다."))
                .body("data.receivedReview.published", equalTo(true));
    }

    @Test
    @DisplayName("내부 목록: 받은 공개 리뷰만 최신 공개순(같은 시각이면 ID 역순)으로, 항목 필드는 계약대로. 공개 전·내가 쓴 리뷰는 없다")
    void received() {
        long first = establish();
        long second = establish();
        long third = establish();
        long hidden = establish();
        confirm(first, second, third, hidden);

        write(GUEST, first, 3, "보통이었습니다.");
        long firstReview = write(HOST, first, 4, "준비가 꼼꼼했습니다.");
        clock.advance(Duration.ofHours(1));
        write(GUEST, second, 5, null);
        long secondReview = write(HOST, second, 5, null);
        write(GUEST, third, 5, null);
        long thirdReview = write(HOST, third, 2, "늦게 왔습니다.");
        write(HOST, hidden, 1, "공개 전");
        // 테스트 시계는 흐르므로, 같은 시각에 공개된 경우를 만들려고 second·third의 공개 시각을 맞춘다
        jdbcTemplate.update("update appearances.reviews set published_at = ? where appearance_id in (?, ?)",
                clock.now(), second, third);

        internal().get(receivedPath(GUEST))
                .then().statusCode(200)
                .body("statusCode", equalTo(200))
                .body("message", equalTo("받은 출연 리뷰 목록을 조회했습니다."))
                .body("data.page.totalElements", equalTo(3))
                .body("data.content.size()", equalTo(3))
                // second·third는 공개 시각이 같아 리뷰 ID 역순, first는 1시간 먼저 공개
                .body("data.content[0].reviewId", number(thirdReview))
                .body("data.content[1].reviewId", number(secondReview))
                .body("data.content[2].reviewId", number(firstReview))
                .body("data.content[2].appearanceId", number(first))
                .body("data.content[2].reviewerUserId", number(HOST))
                .body("data.content[2].revieweeRole", equalTo("GUEST"))
                .body("data.content[2].rating", equalTo(4))
                .body("data.content[2].content", equalTo("준비가 꼼꼼했습니다."))
                .body("data.content[2].publishedAt", matchesPattern(LOCAL_DATE_TIME))
                .body("data.content[1]", hasKey("content"))
                .body("data.content[1].content", nullValue());
        // 호스트가 받은 리뷰는 게스트가 쓴 3건(공개)이고 역할은 HOST
        internal().get(receivedPath(HOST))
                .then().statusCode(200)
                .body("data.page.totalElements", equalTo(3))
                .body("data.content[0].revieweeRole", equalTo("HOST"))
                .body("data.content[0].reviewerUserId", number(GUEST));
    }

    @Test
    @DisplayName("내부 목록: page·size로 나누고, 요청한 정렬(sort)은 무시한다")
    void paging() {
        long first = establish();
        long second = establish();
        confirm(first, second);
        write(GUEST, first, 3, null);
        long firstReview = write(HOST, first, 3, null);
        clock.advance(Duration.ofHours(1));
        write(GUEST, second, 3, null);
        long secondReview = write(HOST, second, 3, null);

        internal().queryParam("size", 1).queryParam("sort", "publishedAt,asc").get(receivedPath(GUEST))
                .then().statusCode(200)
                .body("data.content.size()", equalTo(1))
                .body("data.content[0].reviewId", number(secondReview))
                .body("data.page.size", equalTo(1))
                .body("data.page.number", equalTo(0))
                .body("data.page.totalElements", equalTo(2))
                .body("data.page.totalPages", equalTo(2));
        internal().queryParam("size", 1).queryParam("page", 1).get(receivedPath(GUEST))
                .then().statusCode(200)
                .body("data.content[0].reviewId", number(firstReview))
                .body("data.page.number", equalTo(1));
    }

    @Test
    @DisplayName("내부 목록: 리뷰가 없는 회원은 빈 페이지, 회원 ID가 1 미만·숫자가 아니면 400 INVALID_REQUEST")
    void emptyAndInvalid() {
        internal().get(receivedPath(999))
                .then().statusCode(200)
                .body("data.content.size()", equalTo(0))
                .body("data.page.totalElements", equalTo(0));
        internal().get(receivedPath(0))
                .then().statusCode(400)
                .body("code", equalTo("INVALID_REQUEST"));
        internal().get("/api/v1/internal/appearances/users/abc/reviews")
                .then().statusCode(400)
                .body("code", equalTo("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("내부 목록: 내부 키가 없거나 틀리면 본문 없는 401, 회원 로그인만으로도 401")
    void keyRequired() {
        given().get(receivedPath(GUEST))
                .then().statusCode(401)
                .body(equalTo(""));
        given().header(InternalApiHeaders.API_KEY, IntegrationTest.INTERNAL_API_KEY + "x").get(receivedPath(GUEST))
                .then().statusCode(401);
        E2ELogin.as(GUEST, Role.INFLUENCER).get(receivedPath(GUEST))
                .then().statusCode(401);
        // 내부 키 요청이 맞는 경로인지도 확인 (위 401이 경로 오류가 아님)
        internal().get(receivedPath(GUEST))
                .then().statusCode(200)
                .body("data", notNullValue());
    }
}
