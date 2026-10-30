package com.hapbang.appearance.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.hapbang.appearance.app.dto.AppearanceRequestResDto;
import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.domain.AppearanceRecruitStatus;
import com.hapbang.appearance.domain.AppearanceRequestStatus;
import com.hapbang.appearance.domain.HpTask;
import com.hapbang.appearance.domain.HpTaskReason;
import com.hapbang.appearance.domain.HpTaskRefType;
import com.hapbang.appearance.domain.HpTaskStatus;
import com.hapbang.appearance.domain.HpTaskType;
import com.hapbang.appearance.fixture.AppearanceRecruitFixture;
import com.hapbang.appearance.fixture.AppearanceRequestFixture;
import com.hapbang.appearance.fixture.AppearanceUserFixture;
import com.hapbang.appearance.out.AppearanceRecruitRepository;
import com.hapbang.appearance.out.AppearanceRequestRepository;
import com.hapbang.appearance.out.HpTaskRepository;
import com.hapbang.appearance.out.billing.BillingClient;
import com.hapbang.appearance.support.AppearanceTestClock;
import com.hapbang.appearance.support.AppearanceTestClockConfig;
import com.hapbang.appearance.support.MockBilling;
import com.hapbang.appearance.support.MockBilling.Endpoint;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 지갑 회로가 열린 동안 배치가 회차를 건너뛰는지 실제 공용 Client의 Circuit Breaker로 확인한다 (#439).
 * <ul>
 *     <li>{@code MockBillingIntegrationTest}는 회로가 열리지 않게 두므로 이 클래스는 따로 설정한다: 최근 2번 중 절반이 실패하면
 *     열리고, {@value #OPEN_DURATION} 뒤 시험 중(HALF_OPEN)으로 넘어가 한 번 성공하면 닫힌다.
 *     설정이 달라 Spring 테스트 컨텍스트가 따로 뜬다. 끝나면 닫는다({@code @DirtiesContext}): 열어 두면 캐시된 컨텍스트들의
 *     DB 연결이 테스트 DB 연결 한도를 넘는다.</li>
 *     <li>테스트마다 회로를 열었다가 성공 호출로 닫고 끝낸다. 다음 테스트는 닫힌 회로에서 시작한다.</li>
 *     <li>회로 시간은 테스트 시계가 아니라 실제 시간이라, 시험 중으로 넘어가기를 짧게 기다린다.</li>
 * </ul>
 */
@IntegrationTest
@Import(AppearanceTestClockConfig.class)
@ExtendWith(AppearanceTestClock.ResetExtension.class)
@WithMockUser(roles = "INFLUENCER")
@DirtiesContext
class AppearanceBillingCircuitTest {

    private static final Long HOST = 1L;
    private static final Long GUEST = 2L;
    private static final String OPEN_DURATION = "1s";

    @DynamicPropertySource
    static void circuitOpensQuickly(DynamicPropertyRegistry registry) {
        registry.add("hapbang.clients.billing.base-url", MockBilling::baseUrl);
        registry.add("hapbang.clients.billing.read-timeout", () -> "1s");
        registry.add("hapbang.clients.billing.resilience.circuit-breaker.sliding-window-size", () -> 2);
        registry.add("hapbang.clients.billing.resilience.circuit-breaker.minimum-number-of-calls", () -> 2);
        registry.add("hapbang.clients.billing.resilience.circuit-breaker.open-duration", () -> OPEN_DURATION);
        registry.add("hapbang.clients.billing.resilience.circuit-breaker.half-open-calls", () -> 1);
    }

    @Autowired
    AppearanceRequestFacade appearanceRequestFacade;

    @Autowired
    AppearanceRecruitFacade appearanceRecruitFacade;

    @Autowired
    AppearanceFacade appearanceFacade;

    @Autowired
    AppearanceUserFacade appearanceUserFacade;

    @Autowired
    AppearanceHoldRecheckUseCase appearanceHoldRecheckUseCase;

    @Autowired
    HpTaskRetryUseCase hpTaskRetryUseCase;

    @Autowired
    BillingClient billingClient;

    @Autowired
    AppearanceRequestRepository appearanceRequestRepository;

    @Autowired
    AppearanceRecruitRepository appearanceRecruitRepository;

    @Autowired
    HpTaskRepository hpTaskRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    AppearanceTestClock clock;

    @BeforeEach
    void setUp() {
        MockBilling.reset();
        assertThat(billingClient.isCircuitOpen()).as("앞 테스트가 회로를 닫고 끝냈다").isFalse();
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(HOST));
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(GUEST));
    }

    @Test
    @DisplayName("HP 작업 재시도: 회로가 열린 동안 지갑을 부르지 않고 시도 횟수도 그대로, 시험 중으로 넘어가면 같은 요청 ID로 DONE")
    void retrySkippedWhileOpen() {
        // 보류 성공 1번 + 지급 5xx 1번 → 최근 2번 중 절반 실패로 회로가 열린다
        MockBilling.fail(Endpoint.PAYOUT, 500, null, null);
        HpTask task = confirm();
        assertThat(task.getStatus()).isEqualTo(HpTaskStatus.PENDING);
        assertThat(billingClient.isCircuitOpen()).isTrue();

        clock.advanceTo(task.getNextAttemptAt());
        assertThat(hpTaskRetryUseCase.retryDue()).isZero();

        assertThat(reload(task).getAttemptCount()).isEqualTo(1);
        assertThat(MockBilling.requests(Endpoint.PAYOUT)).hasSize(1);

        MockBilling.succeed(Endpoint.PAYOUT);
        waitUntilCircuitNotOpen();
        assertThat(hpTaskRetryUseCase.retryDue()).isEqualTo(1);

        HpTask done = reload(task);
        assertThat(done.getStatus()).isEqualTo(HpTaskStatus.DONE);
        assertThat(done.getAttemptCount()).isEqualTo(2);
        assertThat(MockBilling.requests(Endpoint.PAYOUT)).hasSize(2)
                .extracting(payout -> payout.requestId(Endpoint.PAYOUT)).containsOnly(task.getIdempotencyKey());
        assertThat(billingClient.isCircuitOpen()).isFalse();
    }

    @Test
    @DisplayName("보류 재확인: 회로가 열린 동안 지갑을 부르지 않고 HOLD_PENDING 그대로, 시험 중으로 넘어가면 REQUESTED·RECRUITING")
    void recheckSkippedWhileOpen() {
        // 요청서·모집글 보류 5xx 2번 → 회로가 열린다
        MockBilling.fail(Endpoint.HOLD, 500, null, null);
        assertBillingUnavailable(() -> appearanceRequestFacade.create(HOST, GUEST,
                AppearanceRequestFixture.content(clock.now())));
        assertBillingUnavailable(() -> appearanceRecruitFacade.create(HOST,
                AppearanceRecruitFixture.content(clock.now())));
        assertThat(billingClient.isCircuitOpen()).isTrue();

        clock.advance(Duration.ofMinutes(2));
        assertThat(appearanceHoldRecheckUseCase.recheckDue()).isZero();

        assertThat(MockBilling.requests(Endpoint.HOLD)).hasSize(2);
        assertThat(requestStatus()).isEqualTo(AppearanceRequestStatus.HOLD_PENDING);
        assertThat(recruitStatus()).isEqualTo(AppearanceRecruitStatus.HOLD_PENDING);

        MockBilling.succeed(Endpoint.HOLD);
        waitUntilCircuitNotOpen();
        assertThat(appearanceHoldRecheckUseCase.recheckDue()).isEqualTo(2);

        assertThat(requestStatus()).isEqualTo(AppearanceRequestStatus.REQUESTED);
        assertThat(recruitStatus()).isEqualTo(AppearanceRecruitStatus.RECRUITING);
        assertThat(billingClient.isCircuitOpen()).isFalse();
    }

    @Test
    @DisplayName("회차 도중에 회로가 열리면 남은 작업은 보내지 않는다: 닫힌 회로에서 2건 실패로 열리면 3번째는 그대로, 시험 중 보낸 1건이 또 실패해도 나머지는 시도 횟수 그대로")
    void stopWhenCircuitOpensMidRun() {
        List<HpTask> tasks = new ArrayList<>();
        for (long refId = 101; refId <= 103; refId++) {
            tasks.add(hpTaskRepository.saveAndFlush(HpTask.release(HpTaskRefType.APPEARANCE_REQUEST, refId, HOST, 30_000,
                    HpTaskReason.REQUEST_REJECTED, "APPEARANCE_REQUEST-" + refId + "-RELEASE-1", clock.now())));
        }
        clock.advance(Duration.ofMinutes(2));
        MockBilling.fail(Endpoint.RELEASE, 500, null, null);

        // 닫힌 회로: 2건이 실패해 최근 2번 모두 실패 → 열림. 3번째는 보내지 않는다
        assertThat(hpTaskRetryUseCase.retryDue()).isEqualTo(2);
        assertThat(billingClient.isCircuitOpen()).isTrue();
        assertThat(MockBilling.requests(Endpoint.RELEASE)).hasSize(2);
        assertThat(attempts(tasks)).containsExactly(1, 1, 0);

        // 시험 중: 1건만 보내고 실패해 다시 열림 → 나머지는 시도 횟수 그대로
        waitUntilCircuitNotOpen();
        clock.advance(Duration.ofMinutes(5));
        assertThat(hpTaskRetryUseCase.retryDue()).isEqualTo(1);
        assertThat(billingClient.isCircuitOpen()).isTrue();
        assertThat(MockBilling.requests(Endpoint.RELEASE)).hasSize(3);
        assertThat(attempts(tasks).stream().mapToInt(Integer::intValue).sum()).isEqualTo(3);

        // 지갑 복구 뒤 시험 중: 1건 성공으로 회로가 닫혀 나머지도 실행
        MockBilling.succeed(Endpoint.RELEASE);
        waitUntilCircuitNotOpen();
        clock.advance(Duration.ofMinutes(5));
        assertThat(hpTaskRetryUseCase.retryDue()).isEqualTo(3);
        assertThat(tasks).allSatisfy(task -> assertThat(reload(task).getStatus()).isEqualTo(HpTaskStatus.DONE));
        assertThat(billingClient.isCircuitOpen()).isFalse();
    }

    private List<Integer> attempts(List<HpTask> tasks) {
        return tasks.stream().map(task -> reload(task).getAttemptCount()).toList();
    }

    /** 회로는 열린 시간이 지나면 호출이 없어도 시험 중(HALF_OPEN)으로 넘어간다 (실제 시간) */
    private void waitUntilCircuitNotOpen() {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (billingClient.isCircuitOpen()) {
            assertThat(System.nanoTime()).as("회로가 %s 안에 시험 중으로 넘어가지 않았다", OPEN_DURATION).isLessThan(deadline);
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    /** 요청서를 보내 수락하고 방송이 끝난 뒤 확인해 지급 작업을 만든다 (작업은 확인 직후 한 번 실행된다) */
    private HpTask confirm() {
        AppearanceRequestResDto sent = appearanceRequestFacade.create(HOST, GUEST,
                AppearanceRequestFixture.content(clock.now()));
        Long appearanceId = appearanceRequestFacade.accept(GUEST, sent.id(), sent.version()).appearanceId();
        clock.advanceTo(jdbcTemplate.queryForObject("select scheduled_at from appearances.appearances where id = ?",
                LocalDateTime.class, appearanceId).plusHours(1));
        appearanceFacade.confirm(HOST, appearanceId);
        List<HpTask> payouts = hpTaskRepository.findAll().stream()
                .filter(task -> task.getTaskType() == HpTaskType.PAYOUT).toList();
        assertThat(payouts).hasSize(1);
        return payouts.getFirst();
    }

    private HpTask reload(HpTask task) {
        return hpTaskRepository.findById(task.getId()).orElseThrow();
    }

    private AppearanceRequestStatus requestStatus() {
        return appearanceRequestRepository.findAll().getFirst().getStatus();
    }

    private AppearanceRecruitStatus recruitStatus() {
        return appearanceRecruitRepository.findAll().getFirst().getStatus();
    }

    private static void assertBillingUnavailable(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(AppearanceException.class)
                .extracting("errorCode").isEqualTo(AppearanceErrorCode.BILLING_UNAVAILABLE);
    }
}
