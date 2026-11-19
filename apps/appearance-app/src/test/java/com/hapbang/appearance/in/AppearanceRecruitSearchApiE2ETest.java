package com.hapbang.appearance.in;

import static com.hapbang.appearance.support.E2EMatchers.number;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import com.hapbang.appearance.app.AppearanceUserFacade;
import com.hapbang.appearance.config.AppearanceConfig;
import com.hapbang.appearance.fixture.AppearanceUserFixture;
import com.hapbang.appearance.support.AppearanceE2ETest;
import com.hapbang.appearance.support.E2ELogin;
import com.hapbang.shared.member.domain.Role;

import io.restassured.response.ValidatableResponse;

/**
 * 출연 모집 공개 목록의 검색 조건·정렬 API. 실제 서버로 쿼리 파라미터가 조건으로 이어지는지, 잘못된 값이 400인지 확인한다.
 * 조건별 결과와 정렬 순서의 세부 경우는 {@code AppearanceRecruitOpenQueryTest}가 확인한다.
 */
@AppearanceE2ETest
class AppearanceRecruitSearchApiE2ETest {

    private static final String BASE = "/api/v1/appearances/recruits";
    private static final long HOST = 1L;
    private static final long GUEST = 3L;
    private static final long VIEWER = 4L;

    @Autowired
    AppearanceUserFacade appearanceUserFacade;

    @Autowired
    @Qualifier(AppearanceConfig.CLOCK)
    Clock clock;

    @BeforeEach
    void setUp() {
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(HOST));
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(GUEST));
        appearanceUserFacade.syncJoined(AppearanceUserFixture.normalUser(VIEWER));
    }

    /** 방송 예정 일시: 오늘부터 {@code days}일 뒤 20시. 날짜 조건이 시각에 따라 흔들리지 않도록 시각을 고정한다 */
    private LocalDateTime scheduledAt(int days) {
        return LocalDate.now(clock).plusDays(days).atTime(20, 0);
    }

    /** 모집글을 올리고 ID를 돌려준다. 모집 마감 일시는 오늘부터 2일 뒤 0시 */
    private long post(String title, long feePerPerson, int recruitCount, int scheduledDays) {
        return post(title, feePerPerson, recruitCount, scheduledDays, 2);
    }

    /** 모집 마감 일시는 오늘부터 {@code deadlineDays}일 뒤 0시(1 이상이면 언제나 지금 뒤). 올린 시각과 상관없이 날짜로 순서가 정해진다 */
    private long post(String title, long feePerPerson, int recruitCount, int scheduledDays, int deadlineDays) {
        String body = """
                {"title": "%s", "hostChannelUrl": "https://www.youtube.com/@host",
                 "scheduledAt": "%s", "expectedMinutes": 60, "content": "같이 플레이할 게스트를 모집합니다.",
                 "feePerPerson": %d, "recruitCount": %d, "recruitDeadline": "%s"}
                """.formatted(title, scheduledAt(scheduledDays), feePerPerson, recruitCount,
                LocalDate.now(clock).plusDays(deadlineDays).atStartOfDay());
        return E2ELogin.as(HOST, Role.INFLUENCER).body(body).post(BASE)
                .then().statusCode(201)
                .extract().jsonPath().getLong("data.id");
    }

    /** 게스트가 지원하고 호스트가 수락해 자리 하나를 채운다 */
    private void fillOneSeat(long recruitId) {
        long applicationId = E2ELogin.as(GUEST, Role.INFLUENCER).post(BASE + "/" + recruitId + "/applications")
                .then().statusCode(201)
                .extract().jsonPath().getLong("data.id");
        E2ELogin.as(HOST, Role.INFLUENCER).post("/api/v1/appearances/applications/" + applicationId + "/accept")
                .then().statusCode(200);
    }

    private ValidatableResponse search(Map<String, ?> params) {
        return E2ELogin.as(VIEWER, Role.USER).queryParams(params).get(BASE).then();
    }

    @Test
    @DisplayName("조건 없이 부르면 지금처럼 모집 중인 모집글 전체가 최신순이다. 빈 파라미터는 조건 없음이다")
    void noCondition() {
        long first = post("롤 내전 합방", 30_000, 3, 7);
        long second = post("배그 합방", 50_000, 3, 8);

        E2ELogin.as(VIEWER, Role.USER).get(BASE)
                .then().statusCode(200)
                .body("message", equalTo("모집 중인 출연 모집글 목록을 조회했습니다."))
                .body("data.content.id", contains(number(second), number(first)))
                .body("data.page.totalElements", equalTo(2));
        search(Map.of("keyword", "  ", "minFee", "", "scheduledFrom", "", "sort", "", "hasSeats", ""))
                .statusCode(200)
                .body("data.content.id", contains(number(second), number(first)));
    }

    @Test
    @DisplayName("검색어·방송 날짜·출연료·남은 자리 조건을 함께 보내면 모두 만족하는 모집글만, 정렬 이름대로 나온다")
    void combined() {
        long cheap = post("롤 내전 합방", 20_000, 3, 7);
        long match = post("롤 내전 합방", 40_000, 3, 7);
        long full = post("롤 내전 합방", 50_000, 1, 7);
        long later = post("롤 내전 합방", 45_000, 3, 9);
        long otherGame = post("배그 합방", 45_000, 3, 7);
        long matchToo = post("LoL 롤 대회 합방", 30_000, 3, 7);
        fillOneSeat(full);
        LocalDate day = scheduledAt(7).toLocalDate();

        search(Map.of("keyword", "롤", "scheduledFrom", day.toString(), "scheduledTo", day.toString(),
                "minFee", 30_000, "maxFee", 50_000, "hasSeats", true, "sort", "FEE_HIGH"))
                .statusCode(200)
                .body("data.content.id", contains(number(match), number(matchToo)))
                .body("data.page.totalElements", equalTo(2));
        // 조건을 하나씩 빼면 빠졌던 글이 돌아온다
        search(Map.of("keyword", "롤", "sort", "FEE_HIGH"))
                .statusCode(200)
                .body("data.content.id", contains(number(full), number(later), number(match), number(matchToo),
                        number(cheap)));
        search(Map.of("minFee", 45_000, "sort", "SCHEDULED_SOON"))
                .statusCode(200)
                .body("data.content.id", contains(number(otherGame), number(full), number(later)));
    }

    @Test
    @DisplayName("네 가지 정렬 이름과 page·size를 함께 쓴다. sort를 Pageable의 필드 정렬로 해석하지 않는다")
    void sortWithPage() {
        long first = post("합방 A", 30_000, 3, 8, 3);
        long second = post("합방 B", 50_000, 3, 9, 2);
        long third = post("합방 C", 40_000, 3, 7, 4);

        search(Map.of("sort", "FEE_HIGH", "page", 0, "size", 2))
                .statusCode(200)
                .body("data.content.id", contains(number(second), number(third)))
                .body("data.page.size", equalTo(2))
                .body("data.page.totalElements", equalTo(3));
        search(Map.of("sort", "FEE_HIGH", "page", 1, "size", 2))
                .statusCode(200)
                .body("data.content.id", contains(number(first)));
        search(Map.of("sort", "SCHEDULED_SOON"))
                .statusCode(200)
                .body("data.content.id", contains(number(third), number(first), number(second)));
        search(Map.of("sort", "DEADLINE_SOON"))
                .statusCode(200)
                .body("data.content.id", contains(number(second), number(first), number(third)));
        search(Map.of("sort", "LATEST"))
                .statusCode(200)
                .body("data.content.id", contains(number(third), number(second), number(first)));
    }

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource(delimiter = '|', value = {
            "scheduledFrom | 2026/10/21",
            "scheduledFrom | 2026-02-30",
            "scheduledTo   | tomorrow",
            "minFee        | -1",
            "maxFee        | -1",
            "minFee        | 1.5",
            "hasSeats      | maybe",
            "sort          | PRICE",
            "sort          | createdAt,desc",
    })
    @DisplayName("형식이 틀린 값·음수 출연료·모르는 정렬은 400 INVALID_REQUEST와 그 파라미터 이름")
    void invalidParameter(String name, String value) {
        search(Map.of(name, value))
                .statusCode(400)
                .body("code", equalTo("INVALID_REQUEST"))
                .body("errors.field", hasItem(name));
    }

    @Test
    @DisplayName("뒤집힌 날짜·출연료 범위, 50자를 넘는 검색어, 네 자리가 아닌 연도는 400 INVALID_REQUEST와 이유")
    void invalidCondition() {
        search(Map.of("scheduledFrom", "2026-10-22", "scheduledTo", "2026-10-21"))
                .statusCode(400)
                .body("code", equalTo("INVALID_REQUEST"))
                .body("detail", equalTo("방송 날짜 범위의 시작(scheduledFrom)이 끝(scheduledTo)보다 늦습니다."));
        search(Map.of("minFee", 50_000, "maxFee", 30_000))
                .statusCode(400)
                .body("code", equalTo("INVALID_REQUEST"))
                .body("detail", equalTo("최소 출연료(minFee)가 최대 출연료(maxFee)보다 큽니다."));
        search(Map.of("keyword", "가".repeat(51)))
                .statusCode(400)
                .body("code", equalTo("INVALID_REQUEST"))
                .body("detail", equalTo("검색어는 50자 이하여야 합니다."));
        search(Map.of("scheduledTo", "+10000-01-01"))
                .statusCode(400)
                .body("code", equalTo("INVALID_REQUEST"))
                .body("detail", equalTo("방송 날짜는 yyyy-MM-dd 형식(0001~9999년)이어야 합니다."));
    }

    @Test
    @DisplayName("검색 조건이 있어도 로그인하지 않으면 401")
    void anonymous() {
        given().queryParam("keyword", "롤").get(BASE).then().statusCode(401);
    }
}
