package com.hapbang.appearance.in;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.nullValue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;

import com.hapbang.appearance.app.AppearanceApplicationFacade;
import com.hapbang.appearance.app.AppearanceRecruitFacade;
import com.hapbang.appearance.app.AppearanceRequestFacade;
import com.hapbang.appearance.app.AppearanceUserFacade;
import com.hapbang.appearance.app.dto.AppearanceRequestResDto;
import com.hapbang.appearance.fixture.AppearanceRecruitFixture;
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
 * 실제 서버(보안 필터, 내부 API 키 체인, 메서드 보안, DB)를 띄우고 RestAssured로 탈퇴 확인 내부 API를 호출한다 (#624).
 * 회원 앱이 탈퇴 전에 출연 쪽에 정리할 것이 남았는지 확인할 때 쓴다 (docs/api/internal-withdrawals.md 3장).
 * 요청서·모집글·지원·출연은 Facade로 만들고, 만들기 어려운 상태(HP 보류 확인 중, 출연 취소 요청 처리 중, HP 작업 대기·격리)는
 * DB 값을 바꿔 둔다 (테스트 스레드 역할은 {@code @WithMockUser}).
 * 외부 포트로 오면 404인 것은 경로 접두사로 막으므로 {@code InternalApiPortConfigTest}가 맡는다.
 */
@AppearanceE2ETest
@WithMockUser(roles = "INFLUENCER")
class AppearanceWithdrawalInternalApiE2ETest {

    private static final String PATH = "/api/v1/internal/appearances/users/{userId}/withdrawal-blocks";
    /** 탈퇴하려는 회원 */
    private static final long USER = 10L;
    /** 상대 회원들 */
    private static final List<Long> OTHERS = List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
    /** 복제 회원 정보가 없는 회원 */
    private static final long UNKNOWN_USER = 999L;

    @Autowired
    AppearanceUserFacade appearanceUserFacade;

    @Autowired
    AppearanceRequestFacade appearanceRequestFacade;

    @Autowired
    AppearanceRecruitFacade appearanceRecruitFacade;

    @Autowired
    AppearanceApplicationFacade appearanceApplicationFacade;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    AppearanceTestClock clock;

    @BeforeEach
    void setUp() {
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(USER));
        for (long other : OTHERS) {
            appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(other));
        }
    }

    private AppearanceRequestResDto sendRequest(long hostId, long guestId) {
        return appearanceRequestFacade.create(hostId, guestId, AppearanceRequestFixture.content(clock.now()));
    }

    /** 요청서를 보내고 게스트가 수락해 출연을 성립시킨다. 출연 ID를 돌려준다 */
    private long establish(long hostId, long guestId) {
        AppearanceRequestResDto sent = sendRequest(hostId, guestId);
        return appearanceRequestFacade.accept(guestId, sent.id(), sent.version()).appearanceId();
    }

    private long postRecruit(long hostId) {
        return appearanceRecruitFacade.create(hostId, AppearanceRecruitFixture.content(clock.now())).id();
    }

    private static ValidatableResponse blocksOf(long userId) {
        return given().header(InternalApiHeaders.API_KEY, IntegrationTest.INTERNAL_API_KEY)
                .get(PATH, userId)
                .then().statusCode(200)
                .body("statusCode", equalTo(200))
                .body("message", equalTo("탈퇴 확인을 마쳤습니다."));
    }

    @Test
    @DisplayName("막힌 이유: 조건마다 건수를 센 이유 하나씩, 호스트·게스트 양쪽, 정해진 순서. availableAt은 주지 않는다")
    void allReasons() {
        // 호스트로 보낸 요청 응답 대기 2건
        sendRequest(USER, 1L);
        sendRequest(USER, 2L);
        // 게스트로 받은 요청 응답 대기 1건
        sendRequest(3L, USER);
        // 모집 중인 모집글 1건
        postRecruit(USER);
        // 게스트로 결과 대기 지원 1건
        appearanceApplicationFacade.apply(USER, postRecruit(4L), null);
        // 진행 중인 출연 2건: 호스트로 성립 1건, 게스트로 성립 뒤 출연 취소 요청 처리 중 1건
        establish(USER, 5L);
        long cancelRequested = establish(6L, USER);
        jdbcTemplate.update("update appearances.appearances set status = 'CANCEL_REQUESTED' where id = ?",
                cancelRequested);
        // HP 처리 중 3건: 보류 확인 중 요청서·모집글(호스트), 반환 작업 재시도 대기(호스트)
        long holdPendingRequest = sendRequest(USER, 7L).id();
        jdbcTemplate.update("update appearances.requests set status = 'HOLD_PENDING' where id = ?",
                holdPendingRequest);
        long holdPendingRecruit = postRecruit(USER);
        jdbcTemplate.update("update appearances.recruits set status = 'HOLD_PENDING' where id = ?",
                holdPendingRecruit);
        long rejected = sendRequest(USER, 8L).id();
        appearanceRequestFacade.reject(8L, rejected, null);
        jdbcTemplate.update("""
                update appearances.hp_tasks set status = 'PENDING', completed_at = null
                where ref_type = 'APPEARANCE_REQUEST' and ref_id = ?""", rejected);
        // 운영자 확인이 필요한 작업 1건: 게스트로 받을 지급이 격리됨
        long confirmed = establish(8L, USER);
        insertDeadPayout(8L, USER, confirmed);

        blocksOf(USER)
                .body("data.reasons.code", contains(
                        "APPEARANCE_REQUEST_SENT",
                        "APPEARANCE_REQUEST_RECEIVED",
                        "APPEARANCE_RECRUITING",
                        "APPEARANCE_APPLICATION_PENDING",
                        "APPEARANCE_IN_PROGRESS",
                        "APPEARANCE_HP_PROCESSING",
                        "APPEARANCE_HP_CHECK_REQUIRED"))
                .body("data.reasons.message", contains(
                        "응답을 기다리는 출연 요청 2건이 있어요. 요청을 취소한 뒤 탈퇴할 수 있어요.",
                        "받은 출연 요청 1건에 아직 응답하지 않았어요. 요청한 회원이 기다리고 있으니 거절한 뒤 탈퇴할 수 있어요.",
                        "모집 중인 출연 모집 1건이 있어요. 모집을 마감하거나 취소한 뒤 탈퇴할 수 있어요.",
                        "결과를 기다리는 출연 지원 1건이 있어요. 호스트가 선정할 수 있으니 지원을 취소한 뒤 탈퇴할 수 있어요.",
                        // 출연 2건(취소 요청 처리 중 포함) + 지급이 격리된 출연 1건(아직 성립 상태)
                        "성립된 출연 3건이 진행 중이라 탈퇴할 수 없어요. 출연이 끝나거나 취소된 뒤 탈퇴할 수 있어요.",
                        "출연 HP를 처리하고 있어요. 잠시 뒤 다시 시도해 주세요.",
                        "출연 HP 처리에 문제가 있어 확인하고 있어요. 확인이 끝난 뒤 탈퇴할 수 있어요."))
                .body("data.reasons.availableAt", everyItem(nullValue()));

        // 상대 쪽에서 본 이유: 같은 건이 상대 역할로 나온다
        blocksOf(5L).body("data.reasons.code", contains("APPEARANCE_IN_PROGRESS"));
        blocksOf(4L).body("data.reasons.code", contains("APPEARANCE_RECRUITING"));
        blocksOf(1L).body("data.reasons.code", contains("APPEARANCE_REQUEST_RECEIVED"));
    }

    @Test
    @DisplayName("끝난 건만 있는 회원, 출연 기록이 없는 회원, 복제 회원 정보가 없는 회원은 빈 목록")
    void noReasons() {
        // 게스트가 거절(반환 완료), 호스트가 취소한 요청서
        long rejected = sendRequest(USER, 1L).id();
        appearanceRequestFacade.reject(1L, rejected, null);
        long canceled = sendRequest(USER, 2L).id();
        appearanceRequestFacade.cancel(USER, canceled);
        // 보류에 실패한 요청서
        long holdFailed = sendRequest(USER, 3L).id();
        jdbcTemplate.update("update appearances.requests set status = 'HOLD_FAILED' where id = ?", holdFailed);
        // 지원을 취소하고, 조기 마감한 모집글 (남은 인원분 반환 완료)
        long otherRecruit = postRecruit(4L);
        appearanceApplicationFacade.withdraw(USER,
                appearanceApplicationFacade.apply(USER, otherRecruit, null).id());
        appearanceRecruitFacade.closeEarly(USER, postRecruit(USER));
        // 확인된 출연 (지급 완료)
        long confirmed = establish(5L, USER);
        jdbcTemplate.update("update appearances.appearances set status = 'CONFIRMED' where id = ?", confirmed);

        blocksOf(USER).body("data.reasons", empty());
        blocksOf(6L).body("data.reasons", empty());
        blocksOf(UNKNOWN_USER).body("data.reasons", empty());
    }

    @Test
    @DisplayName("회원 ID 1 미만·숫자 아님 400 INVALID_REQUEST")
    void invalidUserId() {
        for (Object userId : List.of(0, -1, "abc")) {
            given().header(InternalApiHeaders.API_KEY, IntegrationTest.INTERNAL_API_KEY)
                    .get(PATH, userId)
                    .then().statusCode(400)
                    .body("code", equalTo("INVALID_REQUEST"));
        }
    }

    @Test
    @DisplayName("내부 키가 없거나 틀리면 본문 없는 401, 회원 로그인(본인)만으로도 401")
    void keyRequired() {
        given().get(PATH, USER)
                .then().statusCode(401)
                .body(equalTo(""));
        given().header(InternalApiHeaders.API_KEY, IntegrationTest.INTERNAL_API_KEY + "x").get(PATH, USER)
                .then().statusCode(401);
        E2ELogin.as(USER, Role.INFLUENCER).get(PATH, USER)
                .then().statusCode(401);
    }

    /** 게스트에게 줄 지급 작업이 격리된(DEAD) 상태를 만든다. 지갑이 거절해 운영자 확인을 기다리는 경우다 */
    private void insertDeadPayout(long hostId, long guestId, long appearanceId) {
        jdbcTemplate.update("""
                insert into appearances.hp_tasks (task_type, ref_type, ref_id, appearance_id, member_id, amount,
                    receiver_id, payout_amount, reason, idempotency_key, status, attempt_count, next_attempt_at,
                    last_error, completed_at, version, created_at, updated_at)
                select 'PAYOUT', 'APPEARANCE_REQUEST', a.request_id, a.id, ?, a.fee, ?, a.fee,
                    'APPEARANCE_CONFIRMED', 'TEST-DEAD-PAYOUT-' || a.id, 'DEAD', 1, ?, 'WALLET_NOT_FOUND: 테스트', ?, 0, ?, ?
                from appearances.appearances a where a.id = ?""",
                hostId, guestId, clock.now(), clock.now(), clock.now(), clock.now(), appearanceId);
    }
}
