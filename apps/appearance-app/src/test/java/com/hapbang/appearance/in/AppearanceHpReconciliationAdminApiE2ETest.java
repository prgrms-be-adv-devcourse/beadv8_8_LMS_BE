package com.hapbang.appearance.in;

import static com.hapbang.appearance.support.E2EMatchers.number;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.hapbang.appearance.app.AppearanceUserFacade;
import com.hapbang.appearance.fixture.AppearanceUserFixture;
import com.hapbang.appearance.out.HpReconciliationRunRepository;
import com.hapbang.appearance.out.billing.BillingClient;
import com.hapbang.appearance.support.AppearanceE2ETest;
import com.hapbang.appearance.support.E2ELogin;
import com.hapbang.shared.billing.domain.BillingHoldStatus;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.dto.HoldStateDto;
import com.hapbang.shared.billing.dto.HoldStatePageDto;
import com.hapbang.shared.billing.out.BillingCallResult;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;

/**
 * 관리자 서비스 간 HP 대사 API (#581). 실제 서버(보안 필터, Gateway 사용자 헤더 로그인, 메서드 보안, DB, Billing 대역)를 띄우고 RestAssured로 부른다.
 * 비교 규칙은 {@code AppearanceHpReconciliationTest}가 보고, 여기서는 실행·조회 응답 모양과 권한만 본다.
 * 지갑 대역은 지갑에만 있는 보류 하나를 목록으로 돌려준다.
 */
@AppearanceE2ETest
class AppearanceHpReconciliationAdminApiE2ETest {

    private static final String BASE = "/api/v1/appearances/admin/hp-reconciliations";
    private static final long ADMIN = 900L;
    private static final long INFLUENCER = 1L;

    @Autowired
    AppearanceUserFacade appearanceUserFacade;

    @Autowired
    BillingClient billingClient;

    @Autowired
    HpReconciliationRunRepository runRepository;

    @BeforeEach
    void setUp() {
        appearanceUserFacade.syncJoined(AppearanceUserFixture.user(ADMIN, Role.ADMIN, UserStatus.ACTIVE));
        HoldStateDto.Amount amount = new HoldStateDto.Amount(30_000, 0, 30_000);
        HoldStateDto.Amount zero = new HoldStateDto.Amount(0, 0, 0);
        HoldStateDto orphan = new HoldStateDto(BillingReferenceType.APPEARANCE_REQUEST, 777L,
                new HoldStateDto.Hold(BillingHoldStatus.HELD, INFLUENCER, amount, amount, zero, zero, zero), null,
                List.of());
        doAnswer(invocation -> {
            BillingReferenceType type = invocation.getArgument(0);
            List<HoldStateDto> items = type == BillingReferenceType.APPEARANCE_REQUEST ? List.of(orphan) : List.of();
            return new BillingCallResult.Succeeded<>(new HoldStatePageDto(items, invocation.<Instant>getArgument(1),
                    invocation.<Long>getArgument(2), false));
        }).when(billingClient).listHolds(any(), any(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("실행 기록이 없으면 결과 조회 404 HP_RECONCILIATION_NOT_FOUND → 실행 200 COMPLETED·어긋난 건 → 결과 조회 200 같은 실행")
    void runAndLatest() {
        E2ELogin.as(ADMIN, Role.ADMIN).get(BASE + "/latest")
                .then().statusCode(404)
                .body("code", equalTo("HP_RECONCILIATION_NOT_FOUND"));

        long runId = E2ELogin.as(ADMIN, Role.ADMIN).post(BASE)
                .then().statusCode(200)
                .body("message", equalTo("HP 대사를 실행했습니다."))
                .body("data.status", equalTo("COMPLETED"))
                .body("data.triggerType", equalTo("MANUAL"))
                .body("data.triggeredBy", number(ADMIN))
                .body("data.startedAt", notNullValue())
                .body("data.finishedAt", notNullValue())
                .body("data.checkedCount", equalTo(1))
                .body("data.mismatchCount", equalTo(1))
                .body("data.deadCount", equalTo(0))
                .body("data.failureReason", nullValue())
                .body("data.mismatches", hasSize(1))
                .body("data.mismatches[0].rule", equalTo("WALLET_ONLY_HOLD"))
                .body("data.mismatches[0].referenceType", equalTo("APPEARANCE_REQUEST"))
                .body("data.mismatches[0].referenceId", number(777L))
                .body("data.mismatches[0].walletState", notNullValue())
                .extract().jsonPath().getLong("data.id");

        E2ELogin.as(ADMIN, Role.ADMIN).get(BASE + "/latest")
                .then().statusCode(200)
                .body("message", equalTo("마지막 HP 대사 결과를 조회했습니다."))
                .body("data.id", number(runId))
                .body("data.mismatches[0].rule", equalTo("WALLET_ONLY_HOLD"));
    }

    @Test
    @DisplayName("지갑 회로가 열려 있으면 실행은 200이지만 INCOMPLETE와 이유를 돌려준다")
    void incomplete() {
        doReturn(true).when(billingClient).isCircuitOpen();

        E2ELogin.as(ADMIN, Role.ADMIN).post(BASE)
                .then().statusCode(200)
                .body("data.status", equalTo("INCOMPLETE"))
                .body("data.failureReason", equalTo("지갑 회로가 열려 있음"))
                .body("data.mismatches", hasSize(0));
    }

    @Test
    @DisplayName("관리자가 아니면 403, 비로그인 401, 권한을 잃은 관리자(복제본 USER)는 403 NOT_ADMIN. 실행되지 않는다")
    void adminOnly() {
        E2ELogin.as(INFLUENCER, Role.VERIFIED_INFLUENCER).post(BASE)
                .then().statusCode(403);
        E2ELogin.as(INFLUENCER, Role.INFLUENCER).get(BASE + "/latest")
                .then().statusCode(403);
        given().post(BASE)
                .then().statusCode(401);
        appearanceUserFacade.syncModified(AppearanceUserFixture.user(ADMIN, Role.USER, UserStatus.ACTIVE));
        E2ELogin.as(ADMIN, Role.ADMIN).post(BASE)
                .then().statusCode(403)
                .body("code", equalTo("NOT_ADMIN"));
        E2ELogin.as(ADMIN, Role.ADMIN).get(BASE + "/latest")
                .then().statusCode(403)
                .body("code", equalTo("NOT_ADMIN"));

        assertThat(runRepository.count()).isZero();
    }
}
