package com.hapbang.appearance.in;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.hapbang.appearance.app.AppearanceUserFacade;
import com.hapbang.appearance.domain.AppearanceUser;
import com.hapbang.appearance.out.AppearanceUserRepository;
import com.hapbang.appearance.support.AppearanceE2ETest;
import com.hapbang.appearance.support.E2ELogin;
import com.hapbang.appearance.support.FakeMemberServer;
import com.hapbang.appearance.support.FakeMemberServer.Response;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;

/**
 * 관리자 기존 회원 Backfill API (#394). 실제 서버(보안 필터, Gateway 사용자 헤더 인증, 메서드 보안, DB)를 띄우고,
 * 회원 내부 API는 {@link FakeMemberServer}로 운영 Client가 실제 HTTP로 부르게 한다.
 */
@AppearanceE2ETest
class AppearanceUserBackfillAdminApiE2ETest {

    private static final String BACKFILL = "/api/v1/appearances/admin/users/backfill";
    private static final long ADMIN = 8000L;
    private static final FakeMemberServer MEMBER_SERVER = new FakeMemberServer();

    @DynamicPropertySource
    static void memberApi(DynamicPropertyRegistry registry) {
        registry.add("hapbang.clients.member.base-url", MEMBER_SERVER::baseUrl);
    }

    @Autowired
    AppearanceUserRepository appearanceUserRepository;

    @Autowired
    AppearanceUserFacade appearanceUserFacade;

    @AfterEach
    void resetMemberServer() {
        MEMBER_SERVER.reset();
    }

    @AfterAll
    static void closeMemberServer() {
        MEMBER_SERVER.close();
    }

    private static UserDto user(long id, String nickname, Role role, UserStatus status, long version) {
        return new UserDto(id, nickname, role, status, version);
    }

    @Test
    @DisplayName("관리자: 출연 회원이 없는 기존 회원은 만들고, 더 높은 버전은 갱신하고, 같거나 낮은 버전은 그대로 둔다. "
            + "응답은 집계·커서만 담고 닉네임은 없다. 관리자 자신의 출연 회원이 없어도 실행된다")
    void backfill() {
        // 이벤트로 이미 반영한 회원: 8002는 v3(스냅샷 v2보다 최신), 8003은 v1(스냅샷 v2가 최신)
        appearanceUserFacade.syncJoined(user(8002L, "이벤트최신", Role.INFLUENCER, UserStatus.ACTIVE, 3L));
        appearanceUserFacade.syncJoined(user(8003L, "이벤트옛것", Role.INFLUENCER, UserStatus.ACTIVE, 1L));
        MEMBER_SERVER.members(List.of(
                user(ADMIN, "관리자", Role.ADMIN, UserStatus.ACTIVE, 0L),
                user(8001L, "기존회원", Role.VERIFIED_INFLUENCER, UserStatus.SUSPENDED, 4L),
                user(8002L, "스냅샷옛것", Role.USER, UserStatus.ACTIVE, 2L),
                user(8003L, "스냅샷최신", Role.VERIFIED_INFLUENCER, UserStatus.ACTIVE, 2L)));

        E2ELogin.as(ADMIN, Role.ADMIN).post(BACKFILL)
                .then().statusCode(200)
                .body("data.scanned", equalTo(4))
                .body("data.created", equalTo(2))
                .body("data.updated", equalTo(1))
                .body("data.ignored", equalTo(1))
                .body("data.failed", equalTo(0))
                .body("data.lastProcessedId", equalTo(8003))
                .body("data.nextAfterId", nullValue())
                .body("data.completed", equalTo(true))
                .body(not(containsString("기존회원")));

        assertThat(MEMBER_SERVER.requests()).containsExactly("afterId=0 size=100");
        assertThat(appearanceUserRepository.findById(8001L)).hasValueSatisfying(replica -> {
            assertThat(replica.getNickname()).isEqualTo("기존회원");
            assertThat(replica.getRole()).isEqualTo(Role.VERIFIED_INFLUENCER);
            assertThat(replica.getStatus()).isEqualTo(UserStatus.SUSPENDED);
            assertThat(replica.getMemberVersion()).isEqualTo(4L);
        });
        assertThat(appearanceUserRepository.findById(8002L)).get()
                .extracting(AppearanceUser::getNickname, AppearanceUser::getMemberVersion)
                .containsExactly("이벤트최신", 3L);
        assertThat(appearanceUserRepository.findById(8003L)).get()
                .extracting(AppearanceUser::getNickname, AppearanceUser::getMemberVersion)
                .containsExactly("스냅샷최신", 2L);
        assertThat(appearanceUserRepository.findById(ADMIN)).isPresent();

        // 다시 실행해도 결과가 같다(모두 그대로)
        E2ELogin.as(ADMIN, Role.ADMIN).post(BACKFILL)
                .then().statusCode(200)
                .body("data.created", equalTo(0))
                .body("data.ignored", equalTo(4));
    }

    @Test
    @DisplayName("Backfill로 만든 출연 회원에는 그 뒤의 회원 변경이 반영된다 (가입 이벤트가 없던 기존 회원도 변경을 처리할 대상이 생김. Kafka·DLT는 회원 이벤트 리스너 테스트가 확인)")
    void modifiedAfterBackfill() {
        MEMBER_SERVER.members(List.of(user(8011L, "기존회원", Role.INFLUENCER, UserStatus.ACTIVE, 5L)));
        E2ELogin.as(ADMIN, Role.ADMIN).post(BACKFILL).then().statusCode(200);

        appearanceUserFacade.syncModified(user(8011L, "바뀐닉네임", Role.INFLUENCER, UserStatus.WITHDRAWN, 6L));

        assertThat(appearanceUserRepository.findById(8011L)).get()
                .extracting(AppearanceUser::getNickname, AppearanceUser::getStatus)
                .containsExactly("바뀐닉네임", UserStatus.WITHDRAWN);
    }

    @Test
    @DisplayName("limit으로 나눠 실행하면 nextAfterId로 이어서 실행한다")
    void resume() {
        MEMBER_SERVER.members(List.of(
                user(8021L, "가", Role.USER, UserStatus.ACTIVE, 0L),
                user(8022L, "나", Role.USER, UserStatus.ACTIVE, 0L),
                user(8023L, "다", Role.USER, UserStatus.ACTIVE, 0L)));

        E2ELogin.as(ADMIN, Role.ADMIN).queryParam("limit", 2).post(BACKFILL)
                .then().statusCode(200)
                .body("data.scanned", equalTo(2))
                .body("data.nextAfterId", equalTo(8022))
                .body("data.completed", equalTo(false));
        E2ELogin.as(ADMIN, Role.ADMIN).queryParam("afterId", 8022).queryParam("limit", 2).post(BACKFILL)
                .then().statusCode(200)
                .body("data.scanned", equalTo(1))
                .body("data.completed", equalTo(true));

        assertThat(appearanceUserRepository.findAllById(List.of(8021L, 8022L, 8023L))).hasSize(3);
    }

    @Test
    @DisplayName("회원 내부 API 5xx면 503 MEMBER_SOURCE_UNAVAILABLE에 집계와 resumeAfterId를 담는다")
    void memberSourceUnavailable() {
        MEMBER_SERVER.respond(query -> new Response(503, null));

        E2ELogin.as(ADMIN, Role.ADMIN).queryParam("afterId", 7).post(BACKFILL)
                .then().statusCode(503)
                .body("code", equalTo("MEMBER_SOURCE_UNAVAILABLE"))
                .body("scanned", equalTo(0))
                .body("resumeAfterId", equalTo(7));
    }

    @Test
    @DisplayName("관리자가 아니면 403, 비로그인(사용자 헤더 없음) 401, limit 범위 밖은 400 INVALID_REQUEST이고 회원 내부 API를 부르지 않는다")
    void guarded() {
        E2ELogin.as(8031L, Role.VERIFIED_INFLUENCER).post(BACKFILL)
                .then().statusCode(403);
        given().post(BACKFILL)
                .then().statusCode(401);
        E2ELogin.as(ADMIN, Role.ADMIN).queryParam("limit", 1_001).post(BACKFILL)
                .then().statusCode(400)
                .body("code", equalTo("INVALID_REQUEST"));
        E2ELogin.as(ADMIN, Role.ADMIN).queryParam("afterId", -1).post(BACKFILL)
                .then().statusCode(400);

        assertThat(MEMBER_SERVER.requests()).isEmpty();
    }
}
