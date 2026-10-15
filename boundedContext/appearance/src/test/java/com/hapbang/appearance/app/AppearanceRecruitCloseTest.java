package com.hapbang.appearance.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.hapbang.appearance.app.dto.AppearanceApplicationResDto;
import com.hapbang.appearance.app.dto.AppearanceRecruitResDto;
import com.hapbang.appearance.config.AppearanceConfig;
import com.hapbang.appearance.domain.AppearanceApplicationStatus;
import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.domain.AppearanceRecruitCloseType;
import com.hapbang.appearance.domain.AppearanceRecruitStatus;
import com.hapbang.appearance.domain.HpTask;
import com.hapbang.appearance.domain.HpTaskReason;
import com.hapbang.appearance.domain.HpTaskRefType;
import com.hapbang.appearance.domain.HpTaskStatus;
import com.hapbang.appearance.fixture.AppearanceRecruitFixture;
import com.hapbang.appearance.fixture.AppearanceUserFixture;
import com.hapbang.appearance.out.AppearanceRecruitRepository;
import com.hapbang.appearance.out.AppearanceRepository;
import com.hapbang.appearance.out.HpTaskRepository;
import com.hapbang.appearance.out.billing.FakeBillingClient;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 출연 모집 마감(조기 마감 Facade, 기한 마감 배치 UseCase)을 DB와 Fake Billing으로 확인한다.
 * 모집글은 1인당 30,000 HP × 3명(보류 90,000)이다. 마감 일시는 DB 값을 과거로 옮겨 지난 것으로 만든다.
 */
@IntegrationTest
@WithMockUser(roles = "INFLUENCER")
class AppearanceRecruitCloseTest {

    private static final Long HOST = 1L;
    private static final Long GUEST = 2L;
    private static final Long GUEST2 = 3L;
    private static final Long GUEST3 = 4L;
    private static final long FEE = AppearanceRecruitFixture.FEE_PER_PERSON;
    private static final long TOTAL = AppearanceRecruitFixture.TOTAL_FEE;
    private static final PageRequest FIRST_PAGE = PageRequest.of(0, 20);

    @Autowired
    AppearanceRecruitFacade appearanceRecruitFacade;

    @Autowired
    AppearanceApplicationFacade appearanceApplicationFacade;

    @Autowired
    AppearanceUserFacade appearanceUserFacade;

    @Autowired
    AppearanceRecruitDeadlineCloseUseCase appearanceRecruitDeadlineCloseUseCase;

    @MockitoSpyBean
    AppearanceRecruitCloseRecorder appearanceRecruitCloseRecorder;

    @Autowired
    AppearanceHoldRecheckUseCase appearanceHoldRecheckUseCase;

    @Autowired
    HpTaskRetryUseCase hpTaskRetryUseCase;

    @Autowired
    AppearanceRecruitRepository appearanceRecruitRepository;

    @Autowired
    AppearanceRepository appearanceRepository;

    @Autowired
    HpTaskRepository hpTaskRepository;

    @Autowired
    FakeBillingClient fakeBillingClient;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    @Qualifier(AppearanceConfig.CLOCK)
    Clock clock;

    @BeforeEach
    void setUp() {
        for (Long id : List.of(HOST, GUEST, GUEST2, GUEST3)) {
            appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(id));
        }
    }

    @AfterEach
    void tearDown() {
        fakeBillingClient.reset();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /** 호스트가 1인당 30,000 HP로 정원 {@code recruitCount}명 모집글을 올린다 */
    private Long postRecruit(int recruitCount) {
        return appearanceRecruitFacade.create(HOST,
                AppearanceRecruitFixture.content(now(), FEE, recruitCount)).id();
    }

    private Long apply(Long guestId, Long recruitId) {
        return appearanceApplicationFacade.apply(guestId, recruitId, "지원합니다.").id();
    }

    /** 모집 마감 일시를 1분 전으로 옮기고, 옮긴 값을 돌려준다 */
    private LocalDateTime passDeadline(Long recruitId) {
        LocalDateTime deadline = now().minusMinutes(1).withNano(0);
        jdbcTemplate.update("update appearances.recruits set recruit_deadline = ? where id = ?", deadline, recruitId);
        return deadline;
    }

    private AppearanceApplicationStatus statusOf(Long guestId) {
        return appearanceApplicationFacade.getMine(guestId, null, FIRST_PAGE).getContent().getFirst().status();
    }

    private List<HpTask> releaseTasksOf(Long recruitId) {
        List<HpTask> tasks = new ArrayList<>();
        for (HpTask task : hpTaskRepository.findAll()) {
            if (task.getRefType() == HpTaskRefType.APPEARANCE_RECRUIT && task.getRefId().equals(recruitId)) {
                tasks.add(task);
            }
        }
        return tasks;
    }

    private long heldOf(Long recruitId) {
        return fakeBillingClient.heldAmount(BillingReferenceType.APPEARANCE_RECRUIT, recruitId);
    }

    private static void assertErrorCode(Runnable action, AppearanceErrorCode errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(AppearanceException.class)
                .extracting("errorCode").isEqualTo(errorCode);
    }

    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("조기 마감")
    class CloseEarly {

        @Test
        @DisplayName("성립 0명: CLOSED(EARLY), 대기 지원 NOT_SELECTED, 90,000 전액 반환(RECRUIT_CLOSED, DONE), 호스트 잔액 원래대로")
        void noneSelected() {
            Long recruitId = postRecruit(3);
            apply(GUEST, recruitId);

            AppearanceRecruitResDto closed = appearanceRecruitFacade.closeEarly(HOST, recruitId);

            assertThat(closed.status()).isEqualTo(AppearanceRecruitStatus.CLOSED);
            assertThat(closed.closeType()).isEqualTo(AppearanceRecruitCloseType.EARLY);
            assertThat(closed.closedAt()).isNotNull();
            assertThat(closed.hpReleaseStatus()).isEqualTo(HpTaskStatus.DONE);
            assertThat(statusOf(GUEST)).isEqualTo(AppearanceApplicationStatus.NOT_SELECTED);
            HpTask task = releaseTasksOf(recruitId).getFirst();
            assertThat(task.getReason()).isEqualTo(HpTaskReason.RECRUIT_CLOSED);
            assertThat(task.getAmount()).isEqualTo(TOTAL);
            assertThat(task.getIdempotencyKey()).isEqualTo("APPEARANCE_RECRUIT-" + recruitId + "-RELEASE-1");
            assertThat(heldOf(recruitId)).isZero();
            assertThat(fakeBillingClient.balanceOf(HOST)).isEqualTo(FakeBillingClient.DEFAULT_BALANCE);
        }

        @Test
        @DisplayName("1명 성립: 60,000만 반환, 30,000은 보류로 남음. 수락은 SELECTED, 대기는 NOT_SELECTED, 취소는 WITHDRAWN 그대로, 지원자 수 그대로")
        void partlySelected() {
            Long recruitId = postRecruit(3);
            appearanceApplicationFacade.accept(HOST, apply(GUEST, recruitId));
            apply(GUEST2, recruitId);
            appearanceApplicationFacade.withdraw(GUEST3, apply(GUEST3, recruitId));

            AppearanceRecruitResDto closed = appearanceRecruitFacade.closeEarly(HOST, recruitId);

            assertThat(closed.selectedCount()).isEqualTo(1);
            assertThat(closed.applicantCount()).isEqualTo(2);
            assertThat(statusOf(GUEST)).isEqualTo(AppearanceApplicationStatus.SELECTED);
            assertThat(statusOf(GUEST2)).isEqualTo(AppearanceApplicationStatus.NOT_SELECTED);
            assertThat(statusOf(GUEST3)).isEqualTo(AppearanceApplicationStatus.WITHDRAWN);
            assertThat(releaseTasksOf(recruitId).getFirst().getAmount()).isEqualTo(2 * FEE);
            assertThat(heldOf(recruitId)).isEqualTo(FEE);
            assertThat(fakeBillingClient.balanceOf(HOST)).isEqualTo(FakeBillingClient.DEFAULT_BALANCE - FEE);
            assertThat(appearanceRepository.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("정원이 다 찬 채 마감: 돌려줄 금액이 없어 HP 작업을 만들지 않고, 반환 진행 상태는 null, 보류는 그대로")
        void full() {
            Long recruitId = postRecruit(1);
            appearanceApplicationFacade.accept(HOST, apply(GUEST, recruitId));

            AppearanceRecruitResDto closed = appearanceRecruitFacade.closeEarly(HOST, recruitId);

            assertThat(closed.status()).isEqualTo(AppearanceRecruitStatus.CLOSED);
            assertThat(closed.hpReleaseStatus()).isNull();
            assertThat(releaseTasksOf(recruitId)).isEmpty();
            assertThat(heldOf(recruitId)).isEqualTo(FEE);
        }

        @Test
        @DisplayName("반환 진행 상태는 호스트에게만 담긴다 (다른 회원 상세에서는 null)")
        void releaseStatusHostOnly() {
            Long recruitId = postRecruit(3);
            appearanceRecruitFacade.closeEarly(HOST, recruitId);

            assertThat(appearanceRecruitFacade.getDetail(HOST, recruitId).hpReleaseStatus())
                    .isEqualTo(HpTaskStatus.DONE);
            assertThat(appearanceRecruitFacade.getDetail(GUEST, recruitId).hpReleaseStatus()).isNull();
        }

        @Test
        @DisplayName("반환이 실패해도 마감은 성공하고 작업은 PENDING으로 남는다. 재시도 배치가 처리하면 DONE")
        void releaseFails() {
            Long recruitId = postRecruit(3);
            fakeBillingClient.setUnavailable(true);

            AppearanceRecruitResDto closed = appearanceRecruitFacade.closeEarly(HOST, recruitId);

            assertThat(closed.status()).isEqualTo(AppearanceRecruitStatus.CLOSED);
            assertThat(closed.hpReleaseStatus()).isEqualTo(HpTaskStatus.PENDING);
            assertThat(heldOf(recruitId)).isEqualTo(TOTAL);

            fakeBillingClient.setUnavailable(false);
            HpTask task = releaseTasksOf(recruitId).getFirst();
            jdbcTemplate.update("update appearances.hp_tasks set next_attempt_at = ? where id = ?",
                    now().minusSeconds(1), task.getId());
            hpTaskRetryUseCase.retryDue();

            assertThat(appearanceRecruitFacade.getDetail(HOST, recruitId).hpReleaseStatus())
                    .isEqualTo(HpTaskStatus.DONE);
            assertThat(heldOf(recruitId)).isZero();
        }

        @Test
        @DisplayName("다른 회원 NOT_RECRUIT_HOST, 이미 마감·마감 일시 지남 APPEARANCE_RECRUIT_NOT_OPEN, 보류 처리 중 APPEARANCE_RECRUIT_NOT_FOUND")
        void rejected() {
            Long recruitId = postRecruit(3);
            assertErrorCode(() -> appearanceRecruitFacade.closeEarly(GUEST, recruitId),
                    AppearanceErrorCode.NOT_RECRUIT_HOST);
            appearanceRecruitFacade.closeEarly(HOST, recruitId);
            assertErrorCode(() -> appearanceRecruitFacade.closeEarly(HOST, recruitId),
                    AppearanceErrorCode.APPEARANCE_RECRUIT_NOT_OPEN);

            Long overdueId = postRecruit(3);
            passDeadline(overdueId);
            assertErrorCode(() -> appearanceRecruitFacade.closeEarly(HOST, overdueId),
                    AppearanceErrorCode.APPEARANCE_RECRUIT_NOT_OPEN);

            fakeBillingClient.setUnavailable(true);
            assertThatThrownBy(() -> postRecruit(3)).isInstanceOf(RuntimeException.class);
            fakeBillingClient.setUnavailable(false);
            Long pendingId = appearanceRecruitRepository.findAll().stream()
                    .filter(recruit -> recruit.getStatus() == AppearanceRecruitStatus.HOLD_PENDING)
                    .findFirst().orElseThrow().getId();
            assertErrorCode(() -> appearanceRecruitFacade.closeEarly(HOST, pendingId),
                    AppearanceErrorCode.APPEARANCE_RECRUIT_NOT_FOUND);
            assertThat(releaseTasksOf(overdueId)).isEmpty();
        }

        @Test
        @DisplayName("마감 뒤에는 지원·수락 APPEARANCE_RECRUIT_NOT_OPEN, 미선정된 지원은 취소 APPLICATION_NOT_PENDING")
        void afterClose() {
            Long recruitId = postRecruit(3);
            Long applicationId = apply(GUEST, recruitId);
            appearanceRecruitFacade.closeEarly(HOST, recruitId);

            assertErrorCode(() -> apply(GUEST2, recruitId), AppearanceErrorCode.APPEARANCE_RECRUIT_NOT_OPEN);
            assertErrorCode(() -> appearanceApplicationFacade.accept(HOST, applicationId),
                    AppearanceErrorCode.APPEARANCE_RECRUIT_NOT_OPEN);
            assertErrorCode(() -> appearanceApplicationFacade.withdraw(GUEST, applicationId),
                    AppearanceErrorCode.APPLICATION_NOT_PENDING);
        }

        @Test
        @DisplayName("조기 마감과 수락이 동시에 와도 많아야 한쪽만 막히고, 보류·반환·성립 인원이 서로 맞는다")
        void concurrentCloseAndAccept() throws Exception {
            Long recruitId = postRecruit(3);
            Long applicationId = apply(GUEST, recruitId);

            List<Object> results = runConcurrently(List.of(
                    () -> appearanceRecruitFacade.closeEarly(HOST, recruitId),
                    () -> appearanceApplicationFacade.accept(HOST, applicationId)));

            assertThat(results).allSatisfy(result -> assertThat(result)
                    .isInstanceOfAny(AppearanceRecruitResDto.class, AppearanceApplicationResDto.class,
                            AppearanceException.class));
            // 둘 다 모집글을 배타 잠금으로 읽어 차례로 처리된다 (APR-28 C9).
            // 수락이 먼저면 마감은 잠금을 기다린 뒤 최신 성립 인원으로 마감하고, 마감이 먼저면 수락은 APPEARANCE_RECRUIT_NOT_OPEN.
            // 잠금 전에 읽은 값이 어긋나면 버전 확인이 CONCURRENT_UPDATE로 막는다 (안전장치).
            assertThat(results).filteredOn(AppearanceException.class::isInstance)
                    .allSatisfy(error -> assertThat(((AppearanceException) error).getErrorCode()).isIn(
                            AppearanceErrorCode.CONCURRENT_UPDATE, AppearanceErrorCode.APPEARANCE_RECRUIT_NOT_OPEN))
                    .hasSizeLessThanOrEqualTo(1);
            AppearanceRecruitResDto detail = appearanceRecruitFacade.getDetail(HOST, recruitId);
            assertThat(appearanceRepository.count()).isEqualTo(detail.selectedCount());
            if (detail.status() == AppearanceRecruitStatus.CLOSED) {
                assertThat(heldOf(recruitId)).isEqualTo(detail.selectedCount() * FEE);
            } else {
                assertThat(detail.selectedCount()).isEqualTo(1);
                assertThat(heldOf(recruitId)).isEqualTo(TOTAL);
            }
            assertThat(fakeBillingClient.balanceOf(HOST))
                    .isEqualTo(FakeBillingClient.DEFAULT_BALANCE - heldOf(recruitId));
        }

        @Test
        @DisplayName("조기 마감과 새 지원이 동시에 와도 마감된 모집글에 대기(APPLIED) 지원이 남지 않는다 (C9, 3회 반복)")
        void concurrentCloseAndApply() throws Exception {
            for (int round = 0; round < 3; round++) {
                Long recruitId = postRecruit(3);

                List<Object> results = runConcurrently(List.of(
                        () -> appearanceRecruitFacade.closeEarly(HOST, recruitId),
                        () -> appearanceApplicationFacade.apply(GUEST, recruitId, "지원합니다."),
                        () -> appearanceApplicationFacade.apply(GUEST2, recruitId, "지원합니다.")));

                // 마감은 항상 성공하고, 지원은 마감보다 먼저면 성공(이후 NOT_SELECTED), 나중이면 APPEARANCE_RECRUIT_NOT_OPEN
                assertThat(results.getFirst()).isInstanceOf(AppearanceRecruitResDto.class);
                assertThat(results.subList(1, 3)).allSatisfy(result -> {
                    if (result instanceof AppearanceException error) {
                        assertThat(error.getErrorCode()).isEqualTo(AppearanceErrorCode.APPEARANCE_RECRUIT_NOT_OPEN);
                    } else {
                        assertThat(result).isInstanceOf(AppearanceApplicationResDto.class);
                    }
                });
                assertThat(appearanceApplicationFacade.getApplicants(HOST, recruitId,
                        List.of(AppearanceApplicationStatus.APPLIED), FIRST_PAGE).getContent()).isEmpty();
                assertThat(appearanceRecruitFacade.getDetail(HOST, recruitId).status())
                        .isEqualTo(AppearanceRecruitStatus.CLOSED);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("기한 마감 배치")
    class Deadline {

        @Test
        @DisplayName("마감 일시가 지난 모집글: CLOSED(DEADLINE), 마감 시각 = 모집 마감 일시, 대기 지원 NOT_SELECTED, 미달분 반환")
        void closeDue() {
            Long recruitId = postRecruit(3);
            appearanceApplicationFacade.accept(HOST, apply(GUEST, recruitId));
            apply(GUEST2, recruitId);
            LocalDateTime deadline = passDeadline(recruitId);

            int closed = appearanceRecruitDeadlineCloseUseCase.closeDue();

            assertThat(closed).isEqualTo(1);
            AppearanceRecruitResDto detail = appearanceRecruitFacade.getDetail(HOST, recruitId);
            assertThat(detail.status()).isEqualTo(AppearanceRecruitStatus.CLOSED);
            assertThat(detail.closeType()).isEqualTo(AppearanceRecruitCloseType.DEADLINE);
            assertThat(detail.closedAt()).isEqualTo(deadline);
            assertThat(detail.hpReleaseStatus()).isEqualTo(HpTaskStatus.DONE);
            assertThat(statusOf(GUEST2)).isEqualTo(AppearanceApplicationStatus.NOT_SELECTED);
            assertThat(heldOf(recruitId)).isEqualTo(FEE);
        }

        @Test
        @DisplayName("마감 일시 전인 모집글은 그대로 두고, 한 번 마감한 모집글은 다시 마감하지 않는다")
        void notDueAndOnce() {
            Long openId = postRecruit(3);
            Long dueId = postRecruit(3);
            passDeadline(dueId);

            assertThat(appearanceRecruitDeadlineCloseUseCase.closeDue()).isEqualTo(1);
            assertThat(appearanceRecruitDeadlineCloseUseCase.closeDue()).isZero();
            assertThat(appearanceRecruitFacade.getDetail(HOST, openId).status())
                    .isEqualTo(AppearanceRecruitStatus.RECRUITING);
            assertThat(releaseTasksOf(dueId)).hasSize(1);
        }

        @Test
        @DisplayName("정원이 다 찬 모집글도 마감하지만 반환 작업은 만들지 않는다")
        void fullNoRelease() {
            Long recruitId = postRecruit(1);
            appearanceApplicationFacade.accept(HOST, apply(GUEST, recruitId));
            passDeadline(recruitId);

            assertThat(appearanceRecruitDeadlineCloseUseCase.closeDue()).isEqualTo(1);
            assertThat(releaseTasksOf(recruitId)).isEmpty();
            assertThat(heldOf(recruitId)).isEqualTo(FEE);
        }

        @Test
        @DisplayName("한 모집글 처리 중 충돌이 나면 그 건만 롤백하고 건너뛴 뒤 나머지는 마감하고, 다음 실행에서 마감한다 (C8)")
        void skipConflictAndRetryNextRun() {
            Long conflictId = postRecruit(3);
            Long otherId = postRecruit(3);
            passDeadline(conflictId);
            passDeadline(otherId);
            doThrow(new AppearanceException(AppearanceErrorCode.CONCURRENT_UPDATE)).doCallRealMethod()
                    .when(appearanceRecruitCloseRecorder)
                    .closeApplicationsAndRecordRelease(argThat(recruit -> recruit.getId().equals(conflictId)), any());

            assertThat(appearanceRecruitDeadlineCloseUseCase.closeDue()).isEqualTo(1);
            assertThat(appearanceRecruitFacade.getDetail(HOST, conflictId).status())
                    .isEqualTo(AppearanceRecruitStatus.RECRUITING);
            assertThat(appearanceRecruitFacade.getDetail(HOST, otherId).status())
                    .isEqualTo(AppearanceRecruitStatus.CLOSED);
            assertThat(releaseTasksOf(conflictId)).isEmpty();

            assertThat(appearanceRecruitDeadlineCloseUseCase.closeDue()).isEqualTo(1);
            assertThat(appearanceRecruitFacade.getDetail(HOST, conflictId).status())
                    .isEqualTo(AppearanceRecruitStatus.CLOSED);
            assertThat(releaseTasksOf(conflictId)).hasSize(1);
        }

        @Test
        @DisplayName("마감 일시가 지난 보류 처리 중 모집글: 마감 배치만으로는 건드리지 않고, 보류 재확인 뒤 마감 배치가 CLOSED + 전액 반환 (C5)")
        void holdPendingThenClose() {
            fakeBillingClient.setUnavailable(true);
            assertThatThrownBy(() -> postRecruit(3)).isInstanceOf(RuntimeException.class);
            fakeBillingClient.setUnavailable(false);
            Long recruitId = appearanceRecruitRepository.findAll().getFirst().getId();
            jdbcTemplate.update("update appearances.recruits set created_at = ? where id = ?",
                    now().minusMinutes(2), recruitId);
            passDeadline(recruitId);

            assertThat(appearanceRecruitDeadlineCloseUseCase.closeDue()).isZero();
            assertThat(releaseTasksOf(recruitId)).isEmpty();

            appearanceHoldRecheckUseCase.recheckDue();
            assertThat(appearanceRecruitDeadlineCloseUseCase.closeDue()).isEqualTo(1);

            assertThat(appearanceRecruitRepository.findById(recruitId).orElseThrow().getStatus())
                    .isEqualTo(AppearanceRecruitStatus.CLOSED);
            assertThat(releaseTasksOf(recruitId).getFirst().getAmount()).isEqualTo(TOTAL);
            assertThat(heldOf(recruitId)).isZero();
            assertThat(fakeBillingClient.balanceOf(HOST)).isEqualTo(FakeBillingClient.DEFAULT_BALANCE);
        }
    }

    // ---------------------------------------------------------------------------------------------
    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("세션 역할이 USER면 조기 마감이 막힌다 (@PreAuthorize)")
    void userRole() {
        assertThatThrownBy(() -> appearanceRecruitFacade.closeEarly(HOST, 1L))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("세션 역할이 ADMIN이어도 조기 마감이 막힌다 (인플루언서만)")
    void adminRole() {
        assertThatThrownBy(() -> appearanceRecruitFacade.closeEarly(HOST, 1L))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ---------------------------------------------------------------------------------------------

    /** 작업들을 각자 다른 스레드에서 동시에 실행하고, 결과(응답 또는 예외)를 순서대로 모은다 */
    private List<Object> runConcurrently(List<Callable<Object>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> task : tasks) {
                futures.add(executor.submit(asInfluencer(start, task)));
            }
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            executor.shutdown();
        }
    }

    /** 다른 스레드에서도 인플루언서 세션으로 실행하고, 예외는 결과로 돌려준다 */
    private Callable<Object> asInfluencer(CountDownLatch start, Callable<Object> task) {
        return () -> {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("test", null,
                    List.of(new SimpleGrantedAuthority("ROLE_INFLUENCER"))));
            try {
                start.await();
                return task.call();
            } catch (RuntimeException exception) {
                return exception;
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
    }
}
