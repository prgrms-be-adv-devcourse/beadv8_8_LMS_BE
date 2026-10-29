package com.hapbang.appearance.in;

import static com.hapbang.appearance.support.E2EMatchers.number;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.hapbang.appearance.app.AppearanceUserFacade;
import com.hapbang.appearance.app.HpTaskExecuteUseCase;
import com.hapbang.appearance.domain.HpTask;
import com.hapbang.appearance.domain.HpTaskReason;
import com.hapbang.appearance.domain.HpTaskRefType;
import com.hapbang.appearance.domain.HpTaskType;
import com.hapbang.appearance.fixture.AppearanceUserFixture;
import com.hapbang.appearance.out.HpTaskRepository;
import com.hapbang.appearance.out.billing.BillingClient;
import com.hapbang.appearance.support.AppearanceE2ETest;
import com.hapbang.appearance.support.AppearanceTestClock;
import com.hapbang.appearance.support.BillingStubs;
import com.hapbang.appearance.support.BillingStubs.Rejection;
import com.hapbang.appearance.support.E2ELogin;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;

/**
 * 관리자 HP 작업 API (#397). 실제 서버(보안 필터, 세션 로그인, CSRF, 메서드 보안, DB, Billing 대역)를 띄우고 RestAssured로 부른다.
 * 격리된 작업은 기록 → 실행 → Billing 거절로 만든다.
 */
@AppearanceE2ETest
class AppearanceHpTaskAdminApiE2ETest {

    private static final String BASE = "/api/v1/appearances/admin/hp-tasks";
    private static final long ADMIN = 900L;
    private static final long INFLUENCER = 1L;

    @Autowired
    HpTaskRepository hpTaskRepository;

    @Autowired
    HpTaskExecuteUseCase hpTaskExecuteUseCase;

    @Autowired
    BillingClient billingClient;

    @Autowired
    AppearanceTestClock clock;

    @Autowired
    AppearanceUserFacade appearanceUserFacade;

    @BeforeEach
    void setUp() {
        appearanceUserFacade.syncJoined(AppearanceUserFixture.user(ADMIN, Role.ADMIN, UserStatus.ACTIVE));
    }

    private long deadTask() {
        BillingStubs.reject(billingClient, Rejection.HOLD_NOT_FOUND);
        HpTask task = hpTaskRepository.saveAndFlush(HpTask.release(HpTaskRefType.APPEARANCE_REQUEST, 11L,
                INFLUENCER, 30_000L, HpTaskReason.REQUEST_REJECTED,
                HpTask.requestId(HpTaskRefType.APPEARANCE_REQUEST, 11L, HpTaskType.RELEASE, 1), clock.now()));
        hpTaskExecuteUseCase.execute(task.getId());
        BillingStubs.available(billingClient);
        return task.getId();
    }

    private static String retryPath(long taskId) {
        return BASE + "/" + taskId + "/retry";
    }

    @Test
    @DisplayName("관리자: 목록(기본 DEAD) 200 → 다시 시도 200 DONE·다시 시도 기록 → 한 번 더 409 HP_TASK_NOT_DEAD → 목록 비어 있음")
    void adminFlow() {
        long taskId = deadTask();

        E2ELogin.as(ADMIN, Role.ADMIN).get(BASE)
                .then().statusCode(200)
                .body("message", equalTo("HP 작업 목록을 조회했습니다."))
                .body("data.content[0].id", number(taskId))
                .body("data.content[0].status", equalTo("DEAD"))
                .body("data.content[0].taskType", equalTo("RELEASE"))
                .body("data.content[0].lastError", equalTo("HOLD_NOT_FOUND: HP 보류를 찾을 수 없습니다."))
                .body("data.content[0].completedAt", notNullValue())
                .body("data.page.totalElements", equalTo(1));

        E2ELogin.as(ADMIN, Role.ADMIN).post(retryPath(taskId))
                .then().statusCode(200)
                .body("message", equalTo("HP 작업을 다시 시도했습니다."))
                .body("data.status", equalTo("DONE"))
                .body("data.manualRetryCount", equalTo(1))
                .body("data.lastManualRetryBy", number(ADMIN));

        E2ELogin.as(ADMIN, Role.ADMIN).post(retryPath(taskId))
                .then().statusCode(409)
                .body("code", equalTo("HP_TASK_NOT_DEAD"));

        E2ELogin.as(ADMIN, Role.ADMIN).queryParam("status", "DEAD").get(BASE)
                .then().statusCode(200)
                .body("data.page.totalElements", equalTo(0));
    }

    @Test
    @DisplayName("없는 작업 404 HP_TASK_NOT_FOUND, 없는 상태 값 400")
    void notFoundAndBadStatus() {
        E2ELogin.as(ADMIN, Role.ADMIN).post(retryPath(999_999L))
                .then().statusCode(404)
                .body("code", equalTo("HP_TASK_NOT_FOUND"));
        E2ELogin.as(ADMIN, Role.ADMIN).queryParam("status", "UNKNOWN").get(BASE)
                .then().statusCode(400);
    }

    @Test
    @DisplayName("세션은 관리자지만 관리자 권한을 잃었으면(복제본 USER) 목록·다시 시도 403 NOT_ADMIN")
    void demotedAdmin() {
        long taskId = deadTask();
        appearanceUserFacade.syncModified(AppearanceUserFixture.user(ADMIN, Role.USER, UserStatus.ACTIVE));

        E2ELogin.as(ADMIN, Role.ADMIN).post(retryPath(taskId))
                .then().statusCode(403)
                .body("code", equalTo("NOT_ADMIN"));
        E2ELogin.as(ADMIN, Role.ADMIN).get(BASE)
                .then().statusCode(403)
                .body("code", equalTo("NOT_ADMIN"));
    }

    @Test
    @DisplayName("관리자가 아니면 403, 비로그인 401. 작업은 그대로 DEAD")
    void adminOnly() {
        long taskId = deadTask();

        E2ELogin.as(INFLUENCER, Role.VERIFIED_INFLUENCER).get(BASE)
                .then().statusCode(403);
        E2ELogin.as(INFLUENCER, Role.INFLUENCER).post(retryPath(taskId))
                .then().statusCode(403);
        given().get(BASE)
                .then().statusCode(401);

        E2ELogin.as(ADMIN, Role.ADMIN).get(BASE)
                .then().statusCode(200)
                .body("data.content[0].status", equalTo("DEAD"))
                .body("data.content[0].manualRetryCount", equalTo(0));
    }
}
