package com.hapbang.appearance.in;

import static com.hapbang.appearance.support.E2EMatchers.number;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;

import com.hapbang.appearance.app.AppearanceFacade;
import com.hapbang.appearance.app.AppearanceRequestFacade;
import com.hapbang.appearance.app.AppearanceUserFacade;
import com.hapbang.appearance.app.dto.AppearanceRequestResDto;
import com.hapbang.appearance.fixture.AppearanceRequestFixture;
import com.hapbang.appearance.fixture.AppearanceUserFixture;
import com.hapbang.appearance.support.AppearanceE2ETest;
import com.hapbang.appearance.support.AppearanceTestClock;
import com.hapbang.appearance.support.E2ELogin;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;

/**
 * 실제 서버(보안 필터, Gateway 사용자 헤더 로그인, 메서드 보안, DB, Billing 대역)를 띄우고 RestAssured로 출연 리뷰 작성 API를 호출한다 (#512).
 * 출연 성립·확인까지는 Facade로 준비한다 (테스트 스레드 역할은 {@code @WithMockUser}, API 호출은 {@link E2ELogin}).
 * 작성 기한({@code REVIEW_PERIOD_ENDED})은 운영 설정이 무기한이라 HTTP로 만들지 않고 {@code AppearanceReviewFlowTest}가 확인한다.
 */
@AppearanceE2ETest
@WithMockUser(roles = "INFLUENCER")
class AppearanceReviewApiE2ETest {

    private static final String BASE = "/api/v1/appearances";
    private static final long HOST = 1L;
    private static final long GUEST = 2L;
    private static final long OTHER = 3L;
    private static final long ADMIN = 9L;

    @Autowired
    AppearanceUserFacade appearanceUserFacade;

    @Autowired
    AppearanceRequestFacade appearanceRequestFacade;

    @Autowired
    AppearanceFacade appearanceFacade;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    AppearanceTestClock clock;

    @BeforeEach
    void setUp() {
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(HOST));
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(GUEST));
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(OTHER));
        appearanceUserFacade.syncJoined(AppearanceUserFixture.user(ADMIN, Role.ADMIN, UserStatus.ACTIVE));
    }

    /** 요청서가 수락돼 성립한 출연 ID */
    private long establish() {
        AppearanceRequestResDto sent = appearanceRequestFacade.create(HOST, GUEST,
                AppearanceRequestFixture.content(LocalDateTime.now(clock)));
        return appearanceRequestFacade.accept(GUEST, sent.id(), sent.version()).appearanceId();
    }

    /** 방송이 끝난 뒤 호스트가 확인한 출연 ID */
    private long confirmed() {
        long appearanceId = establish();
        clock.advanceTo(jdbcTemplate.queryForObject("select scheduled_at from appearances.appearances where id = ?",
                LocalDateTime.class, appearanceId).plusHours(1));
        appearanceFacade.confirm(HOST, appearanceId);
        return appearanceId;
    }

    private static String reviewPath(long appearanceId) {
        return BASE + "/" + appearanceId + "/reviews";
    }

    private static Response writeAs(long userId, Role role, long appearanceId, String body) {
        return E2ELogin.as(userId, role).body(body).post(reviewPath(appearanceId));
    }

    private static Response write(long userId, long appearanceId, String body) {
        return writeAs(userId, Role.INFLUENCER, appearanceId, body);
    }

    @Test
    @DisplayName("작성: 201 RsData {id}, 응답에 공개 여부는 없다 → 상대가 쓰면 201, 둘 다 공개")
    void writeAndPublish() {
        long appearanceId = confirmed();

        Response first = write(HOST, appearanceId, "{\"rating\": 5, \"content\": \"준비가 꼼꼼했습니다.\"}");
        first.then().statusCode(201)
                .body("statusCode", equalTo(201))
                .body("message", equalTo("리뷰를 작성했습니다."))
                .body("data.id", notNullValue())
                .body("data.size()", equalTo(1));
        long hostReviewId = first.jsonPath().getLong("data.id");
        write(GUEST, appearanceId, "{\"rating\": 4}")
                .then().statusCode(201);

        Integer published = jdbcTemplate.queryForObject(
                "select count(*) from appearances.reviews where appearance_id = ? and published_at is not null",
                Integer.class, appearanceId);
        assertThat(published).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "select reviewee_id from appearances.reviews where id = ?", Long.class, hostReviewId)).isEqualTo(GUEST);
    }

    @Test
    @DisplayName("요청 값 검증: 별점 없음·0·6, 본문 1,001자, 본문 없는 요청은 400 INVALID_REQUEST")
    void validation() {
        long appearanceId = confirmed();

        for (String body : new String[]{"{}", "{\"rating\": 0}", "{\"rating\": 6}",
                "{\"rating\": 5, \"content\": \"" + "가".repeat(1001) + "\"}"}) {
            write(HOST, appearanceId, body)
                    .then().statusCode(400)
                    .body("code", equalTo("INVALID_REQUEST"));
        }
        E2ELogin.as(HOST, Role.INFLUENCER).post(reviewPath(appearanceId))
                .then().statusCode(400)
                .body("code", equalTo("INVALID_REQUEST"));
        write(HOST, appearanceId, "{\"rating\": null}")
                .then().statusCode(400)
                .body("errors.field", hasItem("rating"));
    }

    @Test
    @DisplayName("로그인 없으면 401, 로그인 역할이 관리자·일반 유저면 403 FORBIDDEN, 로그인 뒤 강등(복제본 일반 유저)이면 403 NOT_INFLUENCER")
    void roles() {
        long appearanceId = confirmed();

        RestAssured.given().contentType(ContentType.JSON).body("{\"rating\": 5}").post(reviewPath(appearanceId))
                .then().statusCode(401);
        writeAs(ADMIN, Role.ADMIN, appearanceId, "{\"rating\": 5}")
                .then().statusCode(403)
                .body("code", equalTo("FORBIDDEN"));

        writeAs(GUEST, Role.USER, appearanceId, "{\"rating\": 5}")
                .then().statusCode(403)
                .body("code", equalTo("FORBIDDEN"));

        appearanceUserFacade.syncModified(AppearanceUserFixture.user(GUEST, Role.USER, UserStatus.ACTIVE));
        writeAs(GUEST, Role.INFLUENCER, appearanceId, "{\"rating\": 5}")
                .then().statusCode(403)
                .body("code", equalTo("NOT_INFLUENCER"));
        assertThat(jdbcTemplate.queryForObject("select count(*) from appearances.reviews where appearance_id = ?",
                Integer.class, appearanceId)).isZero();
    }

    @Test
    @DisplayName("오류 코드: 없는 출연·다른 회원의 출연 404 APPEARANCE_NOT_FOUND(같은 응답), 확인 전 409 APPEARANCE_NOT_REVIEWABLE")
    void appearanceErrors() {
        long established = establish();
        long confirmed = confirmed();

        write(HOST, 999_999L, "{\"rating\": 5}")
                .then().statusCode(404)
                .body("code", equalTo("APPEARANCE_NOT_FOUND"));
        write(OTHER, confirmed, "{\"rating\": 5}")
                .then().statusCode(404)
                .body("code", equalTo("APPEARANCE_NOT_FOUND"))
                .body("detail", equalTo("출연을 찾을 수 없습니다."));
        write(HOST, established, "{\"rating\": 5}")
                .then().statusCode(409)
                .body("code", equalTo("APPEARANCE_NOT_REVIEWABLE"))
                .body("detail", equalTo("출연 확인이 끝난 출연에만 리뷰를 쓸 수 있습니다."));
    }

    @Test
    @DisplayName("오류 코드: 409 ALREADY_REVIEWED, 상대 정지 409 REVIEWEE_NOT_ACTIVE, 작성자 정지 403 APPEARANCE_USER_NOT_ACTIVE")
    void userErrors() {
        long appearanceId = confirmed();
        write(HOST, appearanceId, "{\"rating\": 5}").then().statusCode(201);

        write(HOST, appearanceId, "{\"rating\": 3}")
                .then().statusCode(409)
                .body("code", equalTo("ALREADY_REVIEWED"))
                .body("detail", equalTo("이미 이 출연에 리뷰를 썼습니다."));

        appearanceUserFacade.syncModified(AppearanceUserFixture.user(HOST, Role.INFLUENCER, UserStatus.SUSPENDED));
        write(GUEST, appearanceId, "{\"rating\": 5}")
                .then().statusCode(409)
                .body("code", equalTo("REVIEWEE_NOT_ACTIVE"))
                .body("detail", equalTo("상대가 현재 이용할 수 없는 회원이라 리뷰를 쓸 수 없습니다."));
        write(HOST, appearanceId, "{\"rating\": 5}")
                .then().statusCode(403)
                .body("code", equalTo("APPEARANCE_USER_NOT_ACTIVE"));
    }

    @Test
    @DisplayName("출연 ID가 숫자가 아니면 리뷰 경로에 맞지 않는다 (404)")
    void nonNumericId() {
        E2ELogin.as(HOST, Role.INFLUENCER).body("{\"rating\": 5}").post(BASE + "/abc/reviews")
                .then().statusCode(404);
    }

    @Test
    @DisplayName("리뷰 ID 응답은 숫자다")
    void idIsNumber() {
        long appearanceId = confirmed();

        write(GUEST, appearanceId, "{\"rating\": 2, \"content\": \"   \"}")
                .then().statusCode(201)
                .body("data.id", number(jdbcTemplate.queryForObject(
                        "select id from appearances.reviews where appearance_id = ?", Long.class, appearanceId)));
    }
}
