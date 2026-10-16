package com.hapbang.appearance.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;

import com.hapbang.appearance.app.dto.AppearanceRequestResDto;
import com.hapbang.appearance.config.AppearanceConfig;
import com.hapbang.appearance.domain.AppearancePolicy;
import com.hapbang.appearance.domain.AppearanceRecruitStatus;
import com.hapbang.appearance.domain.AppearanceRequest;
import com.hapbang.appearance.domain.AppearanceRequestStatus;
import com.hapbang.appearance.domain.HpTask;
import com.hapbang.appearance.domain.HpTaskReason;
import com.hapbang.appearance.domain.HpTaskRefType;
import com.hapbang.appearance.domain.HpTaskStatus;
import com.hapbang.appearance.fixture.AppearanceRecruitFixture;
import com.hapbang.appearance.fixture.AppearanceRequestFixture;
import com.hapbang.appearance.fixture.AppearanceUserFixture;
import com.hapbang.appearance.out.AppearanceRecruitRepository;
import com.hapbang.appearance.out.AppearanceRequestRepository;
import com.hapbang.appearance.out.HpTaskRepository;
import com.hapbang.appearance.out.billing.BillingClient;
import com.hapbang.appearance.support.AppearanceIntegrationTest;
import com.hapbang.appearance.support.BillingCalls;
import com.hapbang.appearance.support.BillingStubs;
import com.hapbang.appearance.support.BillingStubs.Rejection;
import com.hapbang.shared.billing.dto.ReleaseCommand;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.dto.HoldCommand;

/**
 * 출연 배치(보류 재확인, 수락 기한 만료, HP 작업 재시도)를 DB와 Billing 대역으로 확인한다.
 * 테스트에서는 배치 스케줄이 꺼져 있으므로 UseCase를 직접 부른다.
 * 시각 조건은 DB의 만든 시각·다음 시도 시각을 과거로 옮겨 만든다.
 */
@AppearanceIntegrationTest
@WithMockUser(roles = "INFLUENCER")
class AppearanceBatchUseCaseTest {

    private static final Long HOST = 1L;
    private static final Long GUEST = 2L;
    private static final long FEE = AppearanceRequestFixture.FEE;

    @Autowired
    AppearanceHoldRecheckUseCase appearanceHoldRecheckUseCase;

    @Autowired
    AppearanceRequestExpireUseCase appearanceRequestExpireUseCase;

    @Autowired
    HpTaskRetryUseCase hpTaskRetryUseCase;

    @Autowired
    HpTaskMetrics hpTaskMetrics;

    @Autowired
    AppearanceRequestFacade appearanceRequestFacade;

    @Autowired
    AppearanceRecruitFacade appearanceRecruitFacade;

    @Autowired
    AppearanceUserFacade appearanceUserFacade;

    @Autowired
    AppearanceRequestRepository appearanceRequestRepository;

    @Autowired
    AppearanceRecruitRepository appearanceRecruitRepository;

    @Autowired
    HpTaskRepository hpTaskRepository;

    @Autowired
    BillingClient billingClient;

    @Autowired
    AppearancePolicy appearancePolicy;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    @Qualifier(AppearanceConfig.CLOCK)
    Clock clock;

    @BeforeEach
    void setUp() {
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(HOST));
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(GUEST));
    }


    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /** Billing 무응답 상태로 보내 HOLD_PENDING 요청서를 만든다 */
    private Long sendHoldPending() {
        BillingStubs.unavailable(billingClient);
        assertThatThrownBy(() -> appearanceRequestFacade.create(HOST, GUEST, AppearanceRequestFixture.content(now())))
                .isInstanceOf(RuntimeException.class);
        BillingStubs.available(billingClient);
        return onlyRequest().getId();
    }

    private AppearanceRequest onlyRequest() {
        List<AppearanceRequest> all = appearanceRequestRepository.findAll();
        assertThat(all).hasSize(1);
        return all.getFirst();
    }

    private AppearanceRequestStatus statusOf(Long requestId) {
        return appearanceRequestRepository.findById(requestId).orElseThrow().getStatus();
    }

    private HpTask onlyTask() {
        List<HpTask> tasks = hpTaskRepository.findAll();
        assertThat(tasks).hasSize(1);
        return tasks.getFirst();
    }

    /** 만든 시각을 과거로 옮긴다 (대기 시간 1분이 지난 것으로) */
    private void ageCreatedAt(String table, Long id) {
        jdbcTemplate.update("update appearances." + table + " set created_at = ? where id = ?",
                now().minusMinutes(2), id);
    }

    /** HP 작업의 다음 시도 시각을 지금 이전으로 옮긴다 */
    private void makeTaskDue(Long taskId) {
        jdbcTemplate.update("update appearances.hp_tasks set next_attempt_at = ? where id = ?",
                now().minusSeconds(1), taskId);
    }

    /** 수락 기한이 이미 지난(이틀 + 1분 전에 보낸) 요청서를 저장한다 */
    private AppearanceRequest saveOverdue(boolean requested) {
        LocalDateTime sentAt = now().minusDays(2).minusMinutes(1);
        AppearanceRequest request = AppearanceRequest.create(HOST, GUEST, AppearanceRequestFixture.content(sentAt),
                appearancePolicy, sentAt);
        if (requested) {
            request.markRequested();
        }
        return appearanceRequestRepository.saveAndFlush(request);
    }

    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("HP 보류 재확인")
    class HoldRecheck {

        @Test
        @DisplayName("보류 처리 중으로 1분 넘게 남은 요청서를 다시 보류해 REQUESTED로 정리한다")
        void recheckToRequested() {
            Long requestId = sendHoldPending();
            ageCreatedAt("requests", requestId);

            int resolved = appearanceHoldRecheckUseCase.recheckDue();

            assertThat(resolved).isEqualTo(1);
            assertThat(statusOf(requestId)).isEqualTo(AppearanceRequestStatus.REQUESTED);
            assertThat(BillingCalls.holds(billingClient)).singleElement().satisfies(hold -> {
                assertThat(hold.referenceId()).isEqualTo(requestId);
                assertThat(hold.amountHp()).isEqualTo(FEE);
            });
        }

        @Test
        @DisplayName("재확인은 처음과 같은 보류 요청(같은 호스트·요청서·금액)을 다시 보낸다. Billing이 이미 처리했으면 처음 보류를 돌려준다(멱등, 실제 확인은 apps 통합 테스트)")
        void alreadyHeldOnce() {
            BillingStubs.unavailable(billingClient);
            assertThatThrownBy(() -> appearanceRequestFacade.create(HOST, GUEST, AppearanceRequestFixture.content(now())))
                    .isInstanceOf(RuntimeException.class);
            HoldCommand first = BillingCalls.holds(billingClient).getFirst();
            BillingStubs.available(billingClient);
            Long requestId = onlyRequest().getId();
            ageCreatedAt("requests", requestId);

            appearanceHoldRecheckUseCase.recheckDue();

            assertThat(statusOf(requestId)).isEqualTo(AppearanceRequestStatus.REQUESTED);
            assertThat(BillingCalls.holds(billingClient)).containsExactly(first);
        }

        @Test
        @DisplayName("Billing이 거절하면(잔액 부족) HOLD_FAILED로 정리한다")
        void recheckToHoldFailed() {
            Long requestId = sendHoldPending();
            BillingStubs.reject(billingClient, Rejection.INSUFFICIENT_BALANCE);
            ageCreatedAt("requests", requestId);

            assertThat(appearanceHoldRecheckUseCase.recheckDue()).isEqualTo(1);

            assertThat(statusOf(requestId)).isEqualTo(AppearanceRequestStatus.HOLD_FAILED);
        }

        @Test
        @DisplayName("여전히 결과를 알 수 없으면 HOLD_PENDING 그대로, 1분이 안 된 요청서는 건드리지 않는다")
        void stillUnknownOrTooFresh() {
            Long requestId = sendHoldPending();

            assertThat(appearanceHoldRecheckUseCase.recheckDue()).isZero();
            assertThat(statusOf(requestId)).isEqualTo(AppearanceRequestStatus.HOLD_PENDING);

            ageCreatedAt("requests", requestId);
            BillingStubs.unavailable(billingClient);
            assertThat(appearanceHoldRecheckUseCase.recheckDue()).isZero();
            assertThat(statusOf(requestId)).isEqualTo(AppearanceRequestStatus.HOLD_PENDING);
        }

        @Test
        @DisplayName("보류 처리 중인 모집글도 다시 보류해 RECRUITING으로 정리한다")
        void recheckRecruit() {
            BillingStubs.unavailable(billingClient);
            assertThatThrownBy(() -> appearanceRecruitFacade.create(HOST, AppearanceRecruitFixture.content(now())))
                    .isInstanceOf(RuntimeException.class);
            BillingStubs.available(billingClient);
            Long recruitId = appearanceRecruitRepository.findAll().getFirst().getId();
            ageCreatedAt("recruits", recruitId);

            appearanceHoldRecheckUseCase.recheckDue();

            assertThat(appearanceRecruitRepository.findById(recruitId).orElseThrow().getStatus())
                    .isEqualTo(AppearanceRecruitStatus.RECRUITING);
            assertThat(BillingCalls.holds(billingClient)).singleElement().satisfies(hold -> {
                assertThat(hold.referenceType()).isEqualTo(BillingReferenceType.APPEARANCE_RECRUIT);
                assertThat(hold.amountHp()).isEqualTo(AppearanceRecruitFixture.TOTAL_FEE);
            });
        }

        @Test
        @DisplayName("수락 기한이 지난 보류 처리 중 요청서는 REQUESTED로 정리한 뒤 만료 배치가 EXPIRED + 반환한다 (S5)")
        void recheckThenExpire() {
            AppearanceRequest overdue = saveOverdue(false);
            ageCreatedAt("requests", overdue.getId());

            appearanceHoldRecheckUseCase.recheckDue();
            assertThat(statusOf(overdue.getId())).isEqualTo(AppearanceRequestStatus.REQUESTED);

            appearanceRequestExpireUseCase.expireDue();
            assertThat(statusOf(overdue.getId())).isEqualTo(AppearanceRequestStatus.EXPIRED);
            assertThat(onlyTask().getStatus()).isEqualTo(HpTaskStatus.DONE);
            assertThat(BillingCalls.releases(billingClient)).extracting(ReleaseCommand::amountHp).containsExactly(FEE);
        }
    }

    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("수락 기한 만료")
    class Expire {

        @Test
        @DisplayName("수락 기한이 지난 응답 대기 요청서를 EXPIRED로 바꾸고 보류 HP를 반환한다 (사유 REQUEST_EXPIRED)")
        void expire() {
            AppearanceRequest overdue = saveOverdue(true);

            int expired = appearanceRequestExpireUseCase.expireDue();

            assertThat(expired).isEqualTo(1);
            assertThat(statusOf(overdue.getId())).isEqualTo(AppearanceRequestStatus.EXPIRED);
            HpTask task = onlyTask();
            assertThat(task.getReason()).isEqualTo(HpTaskReason.REQUEST_EXPIRED);
            assertThat(task.getStatus()).isEqualTo(HpTaskStatus.DONE);
            assertThat(BillingCalls.releases(billingClient)).singleElement().satisfies(release -> {
                assertThat(release.referenceId()).isEqualTo(overdue.getId());
                assertThat(release.releaseRequestId()).isEqualTo(task.getIdempotencyKey());
                assertThat(release.amountHp()).isEqualTo(FEE);
            });
            assertThat(appearanceRequestFacade.getDetail(HOST, overdue.getId()).hpReleaseStatus())
                    .isEqualTo(HpTaskStatus.DONE);
        }

        @Test
        @DisplayName("수락 기한 전인 요청서는 그대로 둔다")
        void notDueAndOnce() {
            AppearanceRequestResDto sent = appearanceRequestFacade.create(HOST, GUEST,
                    AppearanceRequestFixture.content(now()));

            assertThat(appearanceRequestExpireUseCase.expireDue()).isZero();
            assertThat(statusOf(sent.id())).isEqualTo(AppearanceRequestStatus.REQUESTED);
            assertThat(hpTaskRepository.count()).isZero();
        }

        @Test
        @DisplayName("반환이 실패해도 만료는 확정되고 작업은 재시도 대기로 남는다. 다시 실행해도 만료는 한 번뿐이다")
        void releaseFails() {
            AppearanceRequest overdue = saveOverdue(true);
            BillingStubs.unavailable(billingClient);

            appearanceRequestExpireUseCase.expireDue();

            assertThat(statusOf(overdue.getId())).isEqualTo(AppearanceRequestStatus.EXPIRED);
            HpTask task = onlyTask();
            assertThat(task.getStatus()).isEqualTo(HpTaskStatus.PENDING);
            assertThat(task.getAttemptCount()).isEqualTo(1);
            assertThat(appearanceRequestExpireUseCase.expireDue()).isZero();
        }
    }

    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("HP 작업 재시도")
    class Retry {

        /** 반환이 실패한 거절: 작업 1건, 시도 1회, 다음 시도는 1분 뒤 */
        private HpTask failedRelease() {
            AppearanceRequestResDto sent = appearanceRequestFacade.create(HOST, GUEST,
                    AppearanceRequestFixture.content(now()));
            BillingStubs.unavailable(billingClient);
            appearanceRequestFacade.reject(GUEST, sent.id(), null);
            return onlyTask();
        }

        @Test
        @DisplayName("다음 시도 시각 전에는 실행하지 않고, 지나면 다시 실행해 DONE")
        void retryWhenDue() {
            HpTask task = failedRelease();
            BillingStubs.available(billingClient);
            double doneBefore = hpTaskMetrics.executions("done");

            assertThat(hpTaskRetryUseCase.retryDue()).isZero();

            makeTaskDue(task.getId());
            assertThat(hpTaskRetryUseCase.retryDue()).isEqualTo(1);
            HpTask retried = onlyTask();
            assertThat(retried.getStatus()).isEqualTo(HpTaskStatus.DONE);
            assertThat(retried.getAttemptCount()).isEqualTo(2);
            assertThat(BillingCalls.releases(billingClient)).extracting(ReleaseCommand::releaseRequestId)
                    .containsExactly(task.getIdempotencyKey());
            assertThat(hpTaskMetrics.executions("done")).isEqualTo(doneBefore + 1);
        }

        @Test
        @DisplayName("계속 실패하면 간격을 늘려 재시도하고, 10번째 실패에서 DEAD + 지표의 격리 수 1")
        void deadAfterMaxAttempts() {
            HpTask task = failedRelease();
            LocalDateTime previousNext = task.getNextAttemptAt();

            for (int attempt = 2; attempt <= 9; attempt++) {
                makeTaskDue(task.getId());
                hpTaskRetryUseCase.retryDue();
                HpTask retried = onlyTask();
                assertThat(retried.getStatus()).isEqualTo(HpTaskStatus.PENDING);
                assertThat(retried.getAttemptCount()).isEqualTo(attempt);
                previousNext = retried.getNextAttemptAt();
            }
            assertThat(previousNext).isAfter(now().plusMinutes(59));

            makeTaskDue(task.getId());
            hpTaskRetryUseCase.retryDue();

            HpTask dead = onlyTask();
            assertThat(dead.getStatus()).isEqualTo(HpTaskStatus.DEAD);
            assertThat(dead.getAttemptCount()).isEqualTo(10);
            assertThat(dead.getLastError()).isEqualTo("IllegalStateException: Billing 응답 없음 (테스트)");
            assertThat(hpTaskMetrics.deadCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("Billing이 거절하면 재시도하지 않고 바로 DEAD (Billing 오류 코드 기록)")
        void deadOnRejection() {
            HpTask task = failedRelease();
            BillingStubs.available(billingClient);
            BillingStubs.reject(billingClient, Rejection.HOLD_NOT_FOUND);
            makeTaskDue(task.getId());

            hpTaskRetryUseCase.retryDue();

            HpTask dead = onlyTask();
            assertThat(dead.getStatus()).isEqualTo(HpTaskStatus.DEAD);
            assertThat(dead.getAttemptCount()).isEqualTo(2);
            assertThat(dead.getLastError()).isEqualTo("HOLD_NOT_FOUND: HP 보류를 찾을 수 없습니다.");
        }

        @Test
        @DisplayName("처음 시도 전인 작업은 만든 지 1분이 지나야 가져간다 (요청 안의 즉시 실행과 겹치지 않도록)")
        void graceForFreshTask() {
            HpTask fresh = hpTaskRepository.saveAndFlush(HpTask.release(
                    HpTaskRefType.APPEARANCE_REQUEST, 99L, HOST, FEE,
                    HpTaskReason.REQUEST_REJECTED, "APPEARANCE_REQUEST-99-RELEASE-1", now().minusSeconds(1)));

            assertThat(hpTaskRetryUseCase.retryDue()).isZero();
            assertThat(BillingCalls.releases(billingClient)).isEmpty();

            ageCreatedAt("hp_tasks", fresh.getId());
            assertThat(hpTaskRetryUseCase.retryDue()).isEqualTo(1);
            assertThat(onlyTask().getStatus()).isEqualTo(HpTaskStatus.DONE);
            assertThat(BillingCalls.releases(billingClient)).extracting(ReleaseCommand::releaseRequestId)
                    .containsExactly("APPEARANCE_REQUEST-99-RELEASE-1");
        }
    }
}
