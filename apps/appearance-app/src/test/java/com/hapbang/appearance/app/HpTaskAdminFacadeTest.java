package com.hapbang.appearance.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

import com.hapbang.appearance.app.dto.HpTaskAdminResDto;
import com.hapbang.appearance.config.AppearanceBatchProperties;
import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.domain.HpTask;
import com.hapbang.appearance.domain.HpTaskReason;
import com.hapbang.appearance.domain.HpTaskRefType;
import com.hapbang.appearance.domain.HpTaskStatus;
import com.hapbang.appearance.domain.HpTaskType;
import com.hapbang.appearance.fixture.AppearanceUserFixture;
import com.hapbang.appearance.out.HpTaskRepository;
import com.hapbang.appearance.out.billing.BillingClient;
import com.hapbang.appearance.support.AppearanceIntegrationTest;
import com.hapbang.appearance.support.AppearanceTestClock;
import com.hapbang.appearance.support.BillingCalls;
import com.hapbang.appearance.support.BillingStubs;
import com.hapbang.appearance.support.BillingStubs.Rejection;
import com.hapbang.shared.billing.dto.ReleaseCommand;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;

/**
 * 관리자 HP 작업 조회·다시 시도 (#397). DB와 Billing 대역으로 확인한다.
 * 격리된 작업은 실제 흐름처럼 만든다: 작업 기록 → 실행 → Billing 거절 → {@code DEAD}.
 */
@AppearanceIntegrationTest
@WithMockUser(roles = "ADMIN")
class HpTaskAdminFacadeTest {

    private static final long ADMIN = 900L;
    private static final long HOST = 1L;
    private static final long AMOUNT = 30_000L;

    @Autowired
    HpTaskAdminFacade hpTaskAdminFacade;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    AppearanceUserFacade appearanceUserFacade;

    @Autowired
    HpTaskExecuteUseCase hpTaskExecuteUseCase;

    @Autowired
    HpTaskRetryUseCase hpTaskRetryUseCase;

    @Autowired
    HpTaskManualRetryUseCase hpTaskManualRetryUseCase;

    @Autowired
    AppearanceBatchProperties batchProperties;

    @Autowired
    HpTaskRepository hpTaskRepository;

    @Autowired
    BillingClient billingClient;

    @Autowired
    AppearanceTestClock clock;

    @BeforeEach
    void setUp() {
        appearanceUserFacade.syncJoined(AppearanceUserFixture.user(ADMIN, Role.ADMIN, UserStatus.ACTIVE));
    }

    private LocalDateTime now() {
        return clock.now();
    }

    private static String keyOf(long requestId) {
        return HpTask.requestId(HpTaskRefType.APPEARANCE_REQUEST, requestId, HpTaskType.RELEASE, 1);
    }

    /** 요청서 거절 반환 작업을 기록하고 1회 실행한다 */
    private Long releaseTask(long requestId) {
        HpTask task = hpTaskRepository.saveAndFlush(HpTask.release(HpTaskRefType.APPEARANCE_REQUEST, requestId,
                HOST, AMOUNT, HpTaskReason.REQUEST_REJECTED, keyOf(requestId), now()));
        hpTaskExecuteUseCase.execute(task.getId());
        return task.getId();
    }

    /** Billing이 거절해 격리된 작업 */
    private Long deadTask(long requestId) {
        BillingStubs.reject(billingClient, Rejection.HOLD_NOT_FOUND);
        Long taskId = releaseTask(requestId);
        BillingStubs.available(billingClient);
        assertThat(task(taskId).getStatus()).isEqualTo(HpTaskStatus.DEAD);
        return taskId;
    }

    private HpTask task(Long taskId) {
        return hpTaskRepository.findById(taskId).orElseThrow();
    }

    @Nested
    @DisplayName("목록")
    class TaskList {

        @Test
        @DisplayName("격리 작업만 마지막으로 바뀐 순서로, 작업 종류·대상·금액·시도 횟수·마지막 오류·격리 시각을 담는다")
        void deadTasks() {
            Long first = deadTask(11L);
            Long second = deadTask(12L);
            releaseTask(13L); // 성공 → DONE

            Page<HpTaskAdminResDto> page = hpTaskAdminFacade.getTasks(ADMIN, HpTaskStatus.DEAD, PageRequest.of(0, 20));

            assertThat(page.getTotalElements()).isEqualTo(2);
            assertThat(page.getContent()).extracting(HpTaskAdminResDto::id).containsExactly(second, first);

            // 먼저 만든 작업이 나중에 바뀌면 앞으로 온다 (ID 순이 아니라 마지막으로 바뀐 순)
            jdbcTemplate.update("update appearances.hp_tasks set updated_at = updated_at + interval '1 hour' where id = ?",
                    first);
            assertThat(hpTaskAdminFacade.getTasks(ADMIN, HpTaskStatus.DEAD, PageRequest.of(0, 20)).getContent())
                    .extracting(HpTaskAdminResDto::id).containsExactly(first, second);
            HpTaskAdminResDto dead = page.getContent().getFirst();
            assertThat(dead.taskType()).isEqualTo(HpTaskType.RELEASE);
            assertThat(dead.refType()).isEqualTo(HpTaskRefType.APPEARANCE_REQUEST);
            assertThat(dead.refId()).isEqualTo(12L);
            assertThat(dead.memberId()).isEqualTo(HOST);
            assertThat(dead.amount()).isEqualTo(AMOUNT);
            assertThat(dead.reason()).isEqualTo(HpTaskReason.REQUEST_REJECTED);
            assertThat(dead.idempotencyKey()).isEqualTo(keyOf(12L));
            assertThat(dead.attemptCount()).isEqualTo(1);
            assertThat(dead.lastError()).isEqualTo("HOLD_NOT_FOUND: HP 보류를 찾을 수 없습니다.");
            assertThat(dead.completedAt()).isNotNull();
            assertThat(dead.manualRetryCount()).isZero();
        }

        @Test
        @DisplayName("상태를 바꿔 조회할 수 있다 (DONE)")
        void byStatus() {
            deadTask(11L);
            Long done = releaseTask(12L);

            Page<HpTaskAdminResDto> page = hpTaskAdminFacade.getTasks(ADMIN, HpTaskStatus.DONE, PageRequest.of(0, 20));

            assertThat(page.getContent()).extracting(HpTaskAdminResDto::id).containsExactly(done);
        }
    }

    @Nested
    @DisplayName("다시 시도")
    class Retry {

        @Test
        @DisplayName("성공하면 DONE, 같은 멱등 키로 Billing에 한 번 더 보냄, 다시 시도 기록(관리자·시각·횟수)")
        void succeeds() {
            Long taskId = deadTask(11L);

            HpTaskAdminResDto retried = hpTaskAdminFacade.retry(ADMIN, taskId);

            assertThat(retried.status()).isEqualTo(HpTaskStatus.DONE);
            assertThat(retried.attemptCount()).isEqualTo(1);
            assertThat(retried.lastError()).isNull();
            assertThat(retried.manualRetryCount()).isEqualTo(1);
            assertThat(retried.lastManualRetryBy()).isEqualTo(ADMIN);
            assertThat(retried.lastManualRetryAt()).isNotNull();
            assertThat(BillingCalls.releases(billingClient)).extracting(ReleaseCommand::releaseRequestId)
                    .containsExactly(keyOf(11L));
        }

        @Test
        @DisplayName("결과를 알 수 없게 또 실패하면 PENDING(시도 1회, 다음 시도 예약)으로 남고, 재시도 배치가 이어서 실행해 DONE")
        void unknownFailureFollowsRetryRules() {
            Long taskId = deadTask(11L);
            BillingStubs.unavailable(billingClient);

            HpTaskAdminResDto retried = hpTaskAdminFacade.retry(ADMIN, taskId);

            assertThat(retried.status()).isEqualTo(HpTaskStatus.PENDING);
            assertThat(retried.attemptCount()).isEqualTo(1);
            assertThat(retried.nextAttemptAt()).isAfter(now());
            assertThat(retried.lastError()).contains("Billing 응답 없음");

            BillingStubs.available(billingClient);
            clock.advanceTo(task(taskId).getNextAttemptAt());
            assertThat(hpTaskRetryUseCase.retryDue()).isEqualTo(1);
            assertThat(task(taskId).getStatus()).isEqualTo(HpTaskStatus.DONE);
            assertThat(BillingCalls.releases(billingClient)).extracting(ReleaseCommand::releaseRequestId)
                    .containsExactly(keyOf(11L));
        }

        @Test
        @DisplayName("다시 시도로 대기가 된 뒤 실행 결과를 기록하지 못하면(서버 중단 등) 재시도 배치가 이어받되, 다시 시도한 지 1분이 지나야 가져간다 (관리자 요청 안의 실행과 겹치지 않도록)")
        void batchWaitsGraceAfterManualRetry() {
            Long taskId = deadTask(11L);
            clock.advance(Duration.ofHours(3)); // 만든 지 오래된 작업
            hpTaskManualRetryUseCase.reopen(ADMIN, taskId); // 대기로 되돌린 뒤 실행 전에 끊긴 상황

            assertThat(hpTaskRetryUseCase.retryDue()).isZero();
            assertThat(BillingCalls.none(billingClient)).isTrue();

            clock.advance(batchProperties.pendingGrace().plusSeconds(1));
            assertThat(hpTaskRetryUseCase.retryDue()).isEqualTo(1);
            assertThat(task(taskId).getStatus()).isEqualTo(HpTaskStatus.DONE);
            assertThat(BillingCalls.releases(billingClient)).extracting(ReleaseCommand::releaseRequestId)
                    .containsExactly(keyOf(11L));
        }

        @Test
        @DisplayName("Billing이 다시 거절하면 바로 DEAD로 돌아가고, 다시 시도 기록은 남는다")
        void rejectedAgain() {
            Long taskId = deadTask(11L);
            BillingStubs.reject(billingClient, Rejection.INSUFFICIENT_BALANCE);

            HpTaskAdminResDto retried = hpTaskAdminFacade.retry(ADMIN, taskId);

            assertThat(retried.status()).isEqualTo(HpTaskStatus.DEAD);
            assertThat(retried.attemptCount()).isEqualTo(1);
            assertThat(retried.lastError()).isEqualTo("INSUFFICIENT_BALANCE: HP 잔액이 부족합니다.");
            assertThat(retried.manualRetryCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("DEAD가 아닌 작업은 409 HP_TASK_NOT_DEAD, Billing에 보내지 않음")
        void notDead() {
            Long done = releaseTask(11L);
            BillingStubs.available(billingClient);

            assertThatThrownBy(() -> hpTaskAdminFacade.retry(ADMIN, done))
                    .isInstanceOfSatisfying(AppearanceException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(AppearanceErrorCode.HP_TASK_NOT_DEAD));
            assertThat(BillingCalls.none(billingClient)).isTrue();
            assertThat(task(done).getManualRetryCount()).isZero();
        }

        @Test
        @DisplayName("없는 작업은 404 HP_TASK_NOT_FOUND")
        void notFound() {
            assertThatThrownBy(() -> hpTaskAdminFacade.retry(ADMIN, 999_999L))
                    .isInstanceOfSatisfying(AppearanceException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(AppearanceErrorCode.HP_TASK_NOT_FOUND));
        }

        @Test
        @DisplayName("다시 시도로 DONE이 된 작업은 다시 시도할 수 없다 (두 번 누름 → 409)")
        void secondRetryAfterDone() {
            Long taskId = deadTask(11L);
            hpTaskAdminFacade.retry(ADMIN, taskId);

            assertThatThrownBy(() -> hpTaskAdminFacade.retry(ADMIN, taskId))
                    .isInstanceOfSatisfying(AppearanceException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(AppearanceErrorCode.HP_TASK_NOT_DEAD));
            assertThat(task(taskId).getManualRetryCount()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("로그인 역할은 관리자지만 로그인 뒤 정지됐으면 403 APPEARANCE_USER_NOT_ACTIVE, 관리자 권한을 잃었으면 403 NOT_ADMIN (작업 그대로, Billing 호출 없음)")
    void adminRecheckedFromReplica() {
        Long taskId = deadTask(11L);

        appearanceUserFacade.syncModified(AppearanceUserFixture.user(ADMIN, Role.ADMIN, UserStatus.SUSPENDED));
        assertThatThrownBy(() -> hpTaskAdminFacade.retry(ADMIN, taskId))
                .isInstanceOfSatisfying(AppearanceException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AppearanceErrorCode.APPEARANCE_USER_NOT_ACTIVE));
        assertThatThrownBy(() -> hpTaskAdminFacade.getTasks(ADMIN, HpTaskStatus.DEAD, PageRequest.of(0, 20)))
                .isInstanceOfSatisfying(AppearanceException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AppearanceErrorCode.APPEARANCE_USER_NOT_ACTIVE));

        appearanceUserFacade.syncModified(AppearanceUserFixture.user(ADMIN, Role.USER, UserStatus.ACTIVE));
        assertThatThrownBy(() -> hpTaskAdminFacade.retry(ADMIN, taskId))
                .isInstanceOfSatisfying(AppearanceException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AppearanceErrorCode.NOT_ADMIN));

        assertThat(task(taskId).getStatus()).isEqualTo(HpTaskStatus.DEAD);
        assertThat(task(taskId).getManualRetryCount()).isZero();
        assertThat(BillingCalls.none(billingClient)).isTrue();
    }

    @Test
    @WithMockUser(roles = "INFLUENCER")
    @DisplayName("관리자가 아니면 목록·다시 시도 모두 거절 (AccessDenied)")
    void adminOnly() {
        Long taskId = deadTask(11L);

        assertThatThrownBy(() -> hpTaskAdminFacade.getTasks(ADMIN, HpTaskStatus.DEAD, PageRequest.of(0, 20)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> hpTaskAdminFacade.retry(ADMIN, taskId))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(task(taskId).getStatus()).isEqualTo(HpTaskStatus.DEAD);
        assertThat(task(taskId).getManualRetryCount()).isZero();
    }
}
