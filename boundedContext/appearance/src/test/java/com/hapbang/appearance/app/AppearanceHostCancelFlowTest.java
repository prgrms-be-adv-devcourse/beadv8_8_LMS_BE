package com.hapbang.appearance.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

import com.hapbang.appearance.app.dto.AppearanceApplicationResDto;
import com.hapbang.appearance.app.dto.AppearanceRecruitResDto;
import com.hapbang.appearance.app.dto.AppearanceRequestResDto;
import com.hapbang.appearance.app.dto.AppearanceResDto;
import com.hapbang.appearance.config.AppearanceConfig;
import com.hapbang.appearance.domain.AppearanceApplicationStatus;
import com.hapbang.appearance.domain.AppearanceCancelRequestStatus;
import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.domain.AppearanceRecruitStatus;
import com.hapbang.appearance.domain.AppearanceRole;
import com.hapbang.appearance.domain.AppearanceStatus;
import com.hapbang.appearance.domain.HpTask;
import com.hapbang.appearance.domain.HpTaskReason;
import com.hapbang.appearance.domain.HpTaskStatus;
import com.hapbang.appearance.domain.HpTaskType;
import com.hapbang.appearance.fixture.AppearanceRecruitFixture;
import com.hapbang.appearance.fixture.AppearanceRequestFixture;
import com.hapbang.appearance.fixture.AppearanceUserFixture;
import com.hapbang.appearance.out.HpTaskRepository;
import com.hapbang.appearance.out.billing.FakeBillingClient;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 호스트 일방 취소·모집 전체 취소를 DB와 Fake Billing으로 확인한다 (ISSUE.md APR-32).
 * 출연료 30,000 HP → 위약금 9,000(게스트) + 반환 21,000(호스트). 잔액은 Fake Billing 기본 잔액에서 본다.
 */
@IntegrationTest
@WithMockUser(roles = "INFLUENCER")
class AppearanceHostCancelFlowTest {

    private static final Long HOST = 1L;
    private static final Long GUEST = 2L;
    private static final Long GUEST2 = 3L;
    private static final Long GUEST3 = 4L;
    private static final Long OTHER = 5L;
    private static final long FEE = 30_000L;
    private static final long PENALTY = 9_000L;
    private static final long BASE = FakeBillingClient.DEFAULT_BALANCE;

    @Autowired
    AppearanceFacade appearanceFacade;

    @Autowired
    AppearanceRequestFacade appearanceRequestFacade;

    @Autowired
    AppearanceRecruitFacade appearanceRecruitFacade;

    @Autowired
    AppearanceApplicationFacade appearanceApplicationFacade;

    @Autowired
    AppearanceUserFacade appearanceUserFacade;

    @Autowired
    HpTaskRepository hpTaskRepository;

    @Autowired
    HpTaskRetryUseCase hpTaskRetryUseCase;

    @Autowired
    HpTaskExecuteUseCase hpTaskExecuteUseCase;

    @Autowired
    FakeBillingClient fakeBillingClient;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    @Qualifier(AppearanceConfig.CLOCK)
    Clock clock;

    @BeforeEach
    void setUp() {
        for (Long id : List.of(HOST, GUEST, GUEST2, GUEST3, OTHER)) {
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

    /** 요청서가 수락돼 성립한 출연 */
    private RequestAppearance establishFromRequest() {
        AppearanceRequestResDto sent = appearanceRequestFacade.create(HOST, GUEST,
                AppearanceRequestFixture.content(now()));
        return new RequestAppearance(sent.id(),
                appearanceRequestFacade.accept(GUEST, sent.id(), sent.version()).appearanceId());
    }

    private Long postRecruit(int recruitCount) {
        return appearanceRecruitFacade.create(HOST, AppearanceRecruitFixture.content(now(), FEE, recruitCount)).id();
    }

    /** 게스트가 지원하고 호스트가 수락해 성립한 출연 ID */
    private Long establishFromRecruit(Long recruitId, Long guestId) {
        Long applicationId = appearanceApplicationFacade.apply(guestId, recruitId, "지원합니다.").id();
        return appearanceApplicationFacade.accept(HOST, applicationId).appearanceId();
    }

    private HpTask onlyTask(HpTaskType type) {
        List<HpTask> tasks = new ArrayList<>();
        for (HpTask task : hpTaskRepository.findAll()) {
            if (task.getTaskType() == type) {
                tasks.add(task);
            }
        }
        assertThat(tasks).hasSize(1);
        return tasks.getFirst();
    }

    private List<HpTask> tasksOf(HpTaskType type) {
        List<HpTask> tasks = new ArrayList<>();
        for (HpTask task : hpTaskRepository.findAll()) {
            if (task.getTaskType() == type) {
                tasks.add(task);
            }
        }
        return tasks;
    }

    private static void assertErrorCode(Runnable action, AppearanceErrorCode errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(AppearanceException.class)
                .extracting("errorCode").isEqualTo(errorCode);
    }

    private record RequestAppearance(Long requestId, Long appearanceId) {
    }

    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("출연 일방 취소")
    class HostCancel {

        @Test
        @DisplayName("요청서 출처: CANCELED_BY_HOST, 위약금 9,000 게스트 지급 + 21,000 호스트 반환(PENALTY·HOST_CANCELED·DONE), 보류 0")
        void fromRequest() {
            RequestAppearance established = establishFromRequest();

            AppearanceResDto canceled = appearanceFacade.hostCancel(HOST, established.appearanceId(), false,
                    "방송이 취소됐습니다.");

            assertThat(canceled.status()).isEqualTo(AppearanceStatus.CANCELED_BY_HOST);
            assertThat(canceled.penaltyAmount()).isEqualTo(PENALTY);
            assertThat(canceled.hostCancelReason()).isEqualTo("방송이 취소됐습니다.");
            assertThat(canceled.hpPenaltyStatus()).isEqualTo(HpTaskStatus.DONE);
            HpTask task = onlyTask(HpTaskType.PENALTY);
            assertThat(task.getReason()).isEqualTo(HpTaskReason.HOST_CANCELED);
            assertThat(task.getIdempotencyKey()).isEqualTo("APPEARANCE_REQUEST-%d-PENALTY-APPEARANCE-%d"
                    .formatted(established.requestId(), established.appearanceId()));
            assertThat(fakeBillingClient.heldAmount(BillingReferenceType.APPEARANCE_REQUEST, established.requestId()))
                    .isZero();
            assertThat(fakeBillingClient.balanceOf(HOST)).isEqualTo(BASE - PENALTY);
            assertThat(fakeBillingClient.balanceOf(GUEST)).isEqualTo(BASE + PENALTY);
        }

        @Test
        @DisplayName("처리 중인 취소 요청: 확인 없으면 CANCEL_REQUEST_CONFIRM_REQUIRED(아무것도 안 바뀜), 확인하면 취소 + 요청 CLOSED_BY_HOST_CANCEL + 위약금 지급")
        void pendingCancelRequest() {
            Long appearanceId = establishFromRequest().appearanceId();
            appearanceFacade.requestCancel(GUEST, appearanceId, "일정이 생겼습니다.");

            assertErrorCode(() -> appearanceFacade.hostCancel(HOST, appearanceId, false, null),
                    AppearanceErrorCode.CANCEL_REQUEST_CONFIRM_REQUIRED);
            assertThat(appearanceFacade.getDetail(HOST, appearanceId).status())
                    .isEqualTo(AppearanceStatus.CANCEL_REQUESTED);
            assertThat(hpTaskRepository.count()).isZero();

            AppearanceResDto canceled = appearanceFacade.hostCancel(HOST, appearanceId, true, null);

            assertThat(canceled.status()).isEqualTo(AppearanceStatus.CANCELED_BY_HOST);
            assertThat(canceled.cancelRequests().getFirst().status())
                    .isEqualTo(AppearanceCancelRequestStatus.CLOSED_BY_HOST_CANCEL);
            assertThat(fakeBillingClient.balanceOf(GUEST)).isEqualTo(BASE + PENALTY);
        }

        @Test
        @DisplayName("거부: 게스트 NOT_APPEARANCE_HOST, 다른 회원 APPEARANCE_NOT_FOUND, 방송 예정 일시 지남 APPEARANCE_SCHEDULE_PASSED, 합의 취소된 출연·다시 취소 APPEARANCE_NOT_CANCELABLE")
        void rejected() {
            Long appearanceId = establishFromRequest().appearanceId();
            assertErrorCode(() -> appearanceFacade.hostCancel(GUEST, appearanceId, false, null),
                    AppearanceErrorCode.NOT_APPEARANCE_HOST);
            assertErrorCode(() -> appearanceFacade.hostCancel(OTHER, appearanceId, false, null),
                    AppearanceErrorCode.APPEARANCE_NOT_FOUND);
            appearanceFacade.hostCancel(HOST, appearanceId, false, null);
            assertErrorCode(() -> appearanceFacade.hostCancel(HOST, appearanceId, false, null),
                    AppearanceErrorCode.APPEARANCE_NOT_CANCELABLE);

            Long agreed = establishFromRecruit(postRecruit(1), GUEST2);
            appearanceFacade.requestCancel(GUEST2, agreed, "사유");
            appearanceFacade.acceptCancel(HOST, agreed);
            assertErrorCode(() -> appearanceFacade.hostCancel(HOST, agreed, true, null),
                    AppearanceErrorCode.APPEARANCE_NOT_CANCELABLE);

            Long passed = establishFromRecruit(postRecruit(1), GUEST3);
            jdbcTemplate.update("update appearances.appearances set scheduled_at = ? where id = ?",
                    now().minusMinutes(1), passed);
            assertErrorCode(() -> appearanceFacade.hostCancel(HOST, passed, false, null),
                    AppearanceErrorCode.APPEARANCE_SCHEDULE_PASSED);
        }

        @Test
        @DisplayName("모집 출처: 그 자리 보류(30,000)가 위약금·반환으로 정산되고, 성립 인원은 그대로라 자리가 다시 열리지 않는다")
        void fromRecruitSeatNotReopened() {
            Long recruitId = postRecruit(1);
            Long appearanceId = establishFromRecruit(recruitId, GUEST);
            Long waiting = appearanceApplicationFacade.apply(GUEST2, recruitId, "저도요").id();

            appearanceFacade.hostCancel(HOST, appearanceId, false, null);

            assertThat(fakeBillingClient.heldAmount(BillingReferenceType.APPEARANCE_RECRUIT, recruitId)).isZero();
            assertThat(onlyTask(HpTaskType.PENALTY).getIdempotencyKey())
                    .isEqualTo("APPEARANCE_RECRUIT-%d-PENALTY-APPEARANCE-%d".formatted(recruitId, appearanceId));
            assertThat(appearanceRecruitFacade.getDetail(HOST, recruitId).selectedCount()).isEqualTo(1);
            assertErrorCode(() -> appearanceApplicationFacade.accept(HOST, waiting), AppearanceErrorCode.RECRUIT_FULL);
        }

        @Test
        @DisplayName("정산 결과를 모르면 취소는 성공하고 작업은 PENDING → 재시도 배치가 DONE으로 처리하며, 다시 실행해도 위약금은 한 번만 지급된다")
        void penaltyRetried() {
            Long appearanceId = establishFromRequest().appearanceId();
            fakeBillingClient.setUnavailable(true);

            AppearanceResDto canceled = appearanceFacade.hostCancel(HOST, appearanceId, false, null);

            assertThat(canceled.status()).isEqualTo(AppearanceStatus.CANCELED_BY_HOST);
            assertThat(canceled.hpPenaltyStatus()).isEqualTo(HpTaskStatus.PENDING);
            assertThat(fakeBillingClient.balanceOf(GUEST)).isEqualTo(BASE);

            fakeBillingClient.setUnavailable(false);
            HpTask task = onlyTask(HpTaskType.PENALTY);
            jdbcTemplate.update("update appearances.hp_tasks set next_attempt_at = ? where id = ?",
                    now().minusSeconds(1), task.getId());
            hpTaskRetryUseCase.retryDue();
            hpTaskExecuteUseCase.execute(task.getId()); // 이미 DONE이면 다시 실행하지 않는다

            assertThat(appearanceFacade.getDetail(HOST, appearanceId).hpPenaltyStatus()).isEqualTo(HpTaskStatus.DONE);
            assertThat(fakeBillingClient.balanceOf(GUEST)).isEqualTo(BASE + PENALTY);
            assertThat(fakeBillingClient.balanceOf(HOST)).isEqualTo(BASE - PENALTY);
        }

        @Test
        @DisplayName("Billing이 위약금 정산을 거절하면(게스트 지갑 없음) 재시도 없이 DEAD, Billing 오류 코드를 기록한다. 취소는 그대로")
        void penaltyRejected() {
            Long appearanceId = establishFromRequest().appearanceId();
            fakeBillingClient.removeWallet(GUEST);

            AppearanceResDto canceled = appearanceFacade.hostCancel(HOST, appearanceId, false, null);

            assertThat(canceled.status()).isEqualTo(AppearanceStatus.CANCELED_BY_HOST);
            assertThat(canceled.hpPenaltyStatus()).isEqualTo(HpTaskStatus.DEAD);
            HpTask task = onlyTask(HpTaskType.PENALTY);
            assertThat(task.getAttemptCount()).isEqualTo(1);
            assertThat(task.getLastError()).startsWith("WALLET_NOT_FOUND");
        }
    }

    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("모집 전체 취소")
    class RecruitCancel {

        @Test
        @DisplayName("모집 중, 3명 중 2명 성립·1명 대기: CANCELED, 출연 2건 위약금 9,000씩, 대기 지원 NOT_SELECTED, 빈자리 30,000 반환, 보류 0")
        void recruiting() {
            Long recruitId = postRecruit(3);
            Long first = establishFromRecruit(recruitId, GUEST);
            Long second = establishFromRecruit(recruitId, GUEST2);
            appearanceApplicationFacade.apply(GUEST3, recruitId, "지원합니다.");

            AppearanceRecruitResDto canceled = appearanceRecruitFacade.cancel(HOST, recruitId, false, "방송 취소");

            assertThat(canceled.status()).isEqualTo(AppearanceRecruitStatus.CANCELED);
            assertThat(canceled.cancelReason()).isEqualTo("방송 취소");
            assertThat(canceled.canceledAt()).isNotNull();
            assertThat(canceled.hpReleaseStatus()).isEqualTo(HpTaskStatus.DONE);
            for (Long appearanceId : List.of(first, second)) {
                AppearanceResDto detail = appearanceFacade.getDetail(HOST, appearanceId);
                assertThat(detail.status()).isEqualTo(AppearanceStatus.CANCELED_BY_HOST);
                assertThat(detail.penaltyAmount()).isEqualTo(PENALTY);
                assertThat(detail.hostCancelReason()).isEqualTo("방송 취소");
                assertThat(detail.hpPenaltyStatus()).isEqualTo(HpTaskStatus.DONE);
            }
            assertThat(appearanceApplicationFacade.getMine(GUEST3, null, PageRequest.of(0, 20)).getContent()
                    .getFirst().status()).isEqualTo(AppearanceApplicationStatus.NOT_SELECTED);
            HpTask vacancy = onlyTask(HpTaskType.RELEASE);
            assertThat(vacancy.getReason()).isEqualTo(HpTaskReason.RECRUIT_CANCELED);
            assertThat(vacancy.getAmount()).isEqualTo(FEE);
            assertThat(fakeBillingClient.heldAmount(BillingReferenceType.APPEARANCE_RECRUIT, recruitId)).isZero();
            assertThat(fakeBillingClient.balanceOf(HOST)).isEqualTo(BASE - 2 * PENALTY);
            assertThat(fakeBillingClient.balanceOf(GUEST)).isEqualTo(BASE + PENALTY);
            assertThat(fakeBillingClient.balanceOf(GUEST2)).isEqualTo(BASE + PENALTY);
        }

        @Test
        @DisplayName("성립 0명: 위약금 없이 전액 90,000 반환")
        void noneSelected() {
            Long recruitId = postRecruit(3);

            appearanceRecruitFacade.cancel(HOST, recruitId, false, null);

            assertThat(tasksOf(HpTaskType.PENALTY)).isEmpty();
            assertThat(onlyTask(HpTaskType.RELEASE).getAmount()).isEqualTo(3 * FEE);
            assertThat(fakeBillingClient.balanceOf(HOST)).isEqualTo(BASE);
        }

        @Test
        @DisplayName("마감 뒤 취소: 빈자리는 마감 때 이미 반환돼 위약금 정산만, 보류 0")
        void afterClose() {
            Long recruitId = postRecruit(2);
            establishFromRecruit(recruitId, GUEST);
            appearanceRecruitFacade.closeEarly(HOST, recruitId); // 빈자리 30,000 반환

            appearanceRecruitFacade.cancel(HOST, recruitId, false, null);

            assertThat(tasksOf(HpTaskType.PENALTY)).hasSize(1);
            assertThat(tasksOf(HpTaskType.RELEASE)).extracting(HpTask::getReason)
                    .containsExactly(HpTaskReason.RECRUIT_CLOSED);
            assertThat(fakeBillingClient.heldAmount(BillingReferenceType.APPEARANCE_RECRUIT, recruitId)).isZero();
            assertThat(fakeBillingClient.balanceOf(HOST)).isEqualTo(BASE - PENALTY);
        }

        @Test
        @DisplayName("모집 중 합의 취소로 빈 자리는 빈자리 반환에 들어가고 위약금은 없다 (2명 모집, 1명 성립 후 합의 취소 → 60,000 반환)")
        void afterAgreedCancel() {
            Long recruitId = postRecruit(2);
            Long agreed = establishFromRecruit(recruitId, GUEST);
            appearanceFacade.requestCancel(GUEST, agreed, "사유");
            appearanceFacade.acceptCancel(HOST, agreed);

            appearanceRecruitFacade.cancel(HOST, recruitId, false, null);

            assertThat(tasksOf(HpTaskType.PENALTY)).isEmpty();
            assertThat(onlyTask(HpTaskType.RELEASE).getAmount()).isEqualTo(2 * FEE);
            assertThat(fakeBillingClient.balanceOf(HOST)).isEqualTo(BASE);
        }

        @Test
        @DisplayName("처리 중인 취소 요청이 하나라도 있으면 확인 없이 CANCEL_REQUEST_CONFIRM_REQUIRED (아무것도 안 바뀜), 확인하면 전체 취소")
        void pendingCancelRequest() {
            Long recruitId = postRecruit(2);
            establishFromRecruit(recruitId, GUEST);
            Long requesting = establishFromRecruit(recruitId, GUEST2);
            appearanceFacade.requestCancel(GUEST2, requesting, "사유");

            assertErrorCode(() -> appearanceRecruitFacade.cancel(HOST, recruitId, false, null),
                    AppearanceErrorCode.CANCEL_REQUEST_CONFIRM_REQUIRED);
            assertThat(appearanceRecruitFacade.getDetail(HOST, recruitId).status())
                    .isEqualTo(AppearanceRecruitStatus.RECRUITING);
            assertThat(appearanceFacade.getMine(HOST, AppearanceRole.HOST, List.of(AppearanceStatus.CANCELED_BY_HOST),
                    PageRequest.of(0, 20)).getTotalElements()).isZero();

            appearanceRecruitFacade.cancel(HOST, recruitId, true, null);

            assertThat(appearanceFacade.getDetail(HOST, requesting).cancelRequests().getFirst().status())
                    .isEqualTo(AppearanceCancelRequestStatus.CLOSED_BY_HOST_CANCEL);
            assertThat(tasksOf(HpTaskType.PENALTY)).hasSize(2);
        }

        @Test
        @DisplayName("거부: 다른 회원 NOT_RECRUIT_HOST, 다시 취소 APPEARANCE_RECRUIT_NOT_CANCELABLE, 방송 예정 일시 지남 APPEARANCE_SCHEDULE_PASSED, 보류 처리 중 APPEARANCE_RECRUIT_NOT_FOUND")
        void rejected() {
            Long recruitId = postRecruit(1);
            assertErrorCode(() -> appearanceRecruitFacade.cancel(OTHER, recruitId, false, null),
                    AppearanceErrorCode.NOT_RECRUIT_HOST);
            appearanceRecruitFacade.cancel(HOST, recruitId, false, null);
            assertErrorCode(() -> appearanceRecruitFacade.cancel(HOST, recruitId, false, null),
                    AppearanceErrorCode.APPEARANCE_RECRUIT_NOT_CANCELABLE);

            Long passed = postRecruit(1);
            jdbcTemplate.update("update appearances.recruits set scheduled_at = ?, recruit_deadline = ? where id = ?",
                    now().minusMinutes(1), now().minusMinutes(2), passed);
            assertErrorCode(() -> appearanceRecruitFacade.cancel(HOST, passed, false, null),
                    AppearanceErrorCode.APPEARANCE_SCHEDULE_PASSED);

            fakeBillingClient.setUnavailable(true);
            assertThatThrownBy(() -> postRecruit(1)).isInstanceOf(RuntimeException.class);
            fakeBillingClient.setUnavailable(false);
            Long pendingId = jdbcTemplate.queryForObject(
                    "select id from appearances.recruits where status = 'HOLD_PENDING'", Long.class);
            assertErrorCode(() -> appearanceRecruitFacade.cancel(HOST, pendingId, false, null),
                    AppearanceErrorCode.APPEARANCE_RECRUIT_NOT_FOUND);
        }

        @Test
        @DisplayName("모집 전체 취소와 지원 수락이 동시에 와도 교착 상태가 없고, 취소됐으면 진행 중인 출연이 남지 않으며 보류는 0")
        void concurrentCancelAndAccept() throws Exception {
            Long recruitId = postRecruit(2);
            establishFromRecruit(recruitId, GUEST);
            Long waiting = appearanceApplicationFacade.apply(GUEST2, recruitId, "지원합니다.").id();

            List<Object> results = runConcurrently(List.of(
                    () -> appearanceRecruitFacade.cancel(HOST, recruitId, false, null),
                    () -> appearanceApplicationFacade.accept(HOST, waiting)));

            assertThat(results).allSatisfy(result -> assertThat(result).isInstanceOfAny(
                    AppearanceRecruitResDto.class, AppearanceApplicationResDto.class, AppearanceException.class));
            assertThat(results.getFirst()).isInstanceOf(AppearanceRecruitResDto.class);
            assertThat(appearanceFacade.getMine(HOST, AppearanceRole.HOST,
                    List.of(AppearanceStatus.ESTABLISHED, AppearanceStatus.CANCEL_REQUESTED), PageRequest.of(0, 20))
                    .getTotalElements()).isZero();
            assertThat(fakeBillingClient.heldAmount(BillingReferenceType.APPEARANCE_RECRUIT, recruitId)).isZero();
        }
    }

    // ---------------------------------------------------------------------------------------------
    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("세션 역할이 USER면 일방 취소·모집 전체 취소가 막힌다")
    void userRole() {
        assertThatThrownBy(() -> appearanceFacade.hostCancel(HOST, 1L, false, null))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> appearanceRecruitFacade.cancel(HOST, 1L, false, null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("세션 역할이 ADMIN이어도 막힌다 (인플루언서만)")
    void adminRole() {
        assertThatThrownBy(() -> appearanceFacade.hostCancel(HOST, 1L, false, null))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> appearanceRecruitFacade.cancel(HOST, 1L, false, null))
                .isInstanceOf(AccessDeniedException.class);
    }

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
