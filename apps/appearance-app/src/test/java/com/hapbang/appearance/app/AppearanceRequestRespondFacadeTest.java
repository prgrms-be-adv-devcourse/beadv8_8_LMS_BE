package com.hapbang.appearance.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.appearance.app.dto.AppearanceRequestResDto;
import com.hapbang.appearance.app.dto.AppearanceRequestSummaryResDto;
import com.hapbang.appearance.config.AppearanceConfig;
import com.hapbang.appearance.domain.Appearance;
import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.domain.AppearancePolicy;
import com.hapbang.appearance.domain.AppearanceRequest;
import com.hapbang.appearance.domain.AppearanceRequestStatus;
import com.hapbang.appearance.domain.AppearanceStatus;
import com.hapbang.appearance.domain.HpTask;
import com.hapbang.appearance.domain.HpTaskStatus;
import com.hapbang.appearance.fixture.AppearanceRequestFixture;
import com.hapbang.appearance.fixture.AppearanceUserFixture;
import com.hapbang.appearance.out.AppearanceRepository;
import com.hapbang.appearance.out.AppearanceRequestRepository;
import com.hapbang.appearance.out.HpTaskRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.appearance.out.billing.BillingClient;
import com.hapbang.appearance.support.AppearanceIntegrationTest;
import com.hapbang.appearance.support.BillingCalls;
import com.hapbang.appearance.support.BillingStubs;
import com.hapbang.appearance.support.BillingStubs.Rejection;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;

/**
 * 출연 요청 응답(수락·거절·응답 전 취소) 흐름을 DB와 Billing 대역으로 확인한다.
 * 로그인 역할은 {@code @WithMockUser}로 넣는다 (Facade의 {@code @PreAuthorize} 검사용).
 */
@AppearanceIntegrationTest
@WithMockUser(roles = "INFLUENCER")
class AppearanceRequestRespondFacadeTest {

    private static final Long HOST = 1L;
    private static final Long GUEST = 2L;
    private static final Long OTHER = 3L;
    private static final long FEE = AppearanceRequestFixture.FEE;

    @Autowired
    AppearanceRequestFacade appearanceRequestFacade;

    @Autowired
    AppearanceRequestAcceptUseCase appearanceRequestAcceptUseCase;

    @Autowired
    AppearanceRequestCancelUseCase appearanceRequestCancelUseCase;

    @Autowired
    AppearanceUserFacade appearanceUserFacade;

    @Autowired
    AppearanceRequestRepository appearanceRequestRepository;

    @Autowired
    AppearanceRepository appearanceRepository;

    @Autowired
    HpTaskRepository hpTaskRepository;

    @Autowired
    BillingClient billingClient;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    AppearancePolicy appearancePolicy;

    @Autowired
    @Qualifier(AppearanceConfig.CLOCK)
    Clock clock;

    @BeforeEach
    void setUp() {
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(HOST));
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(GUEST));
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(OTHER));
    }


    private AppearanceRequestResDto send() {
        return appearanceRequestFacade.create(HOST, GUEST, AppearanceRequestFixture.content(LocalDateTime.now(clock)));
    }

    private HpTask onlyTask() {
        List<HpTask> tasks = hpTaskRepository.findAll();
        assertThat(tasks).hasSize(1);
        return tasks.getFirst();
    }

    private static void assertErrorCode(Runnable action, AppearanceErrorCode errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(AppearanceException.class)
                .extracting("errorCode").isEqualTo(errorCode);
    }

    // ---------------------------------------------------------------------------------------------
    // 수락
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("수락하면 요청서 ACCEPTED + 출연 성립(스냅샷), HP는 보류 그대로")
    void accept() {
        AppearanceRequestResDto sent = send();

        AppearanceRequestResDto accepted = appearanceRequestFacade.accept(GUEST, sent.id(), sent.version());

        assertThat(accepted.status()).isEqualTo(AppearanceRequestStatus.ACCEPTED);
        assertThat(accepted.respondedAt()).isNotNull();
        Appearance appearance = appearanceRepository.findById(accepted.appearanceId()).orElseThrow();
        assertThat(appearance.getStatus()).isEqualTo(AppearanceStatus.ESTABLISHED);
        assertThat(appearance.getRequestId()).isEqualTo(sent.id());
        assertThat(appearance.getFee()).isEqualTo(FEE);
        assertThat(appearance.getScheduledAt()).isEqualTo(sent.scheduledAt());
        assertThat(BillingCalls.releases(billingClient)).isEmpty(); // 수락은 HP를 옮기지 않는다 (보류 유지)
        assertThat(hpTaskRepository.count()).isZero();
    }

    @Test
    @DisplayName("게스트가 본 뒤 호스트가 수정했으면 APPEARANCE_REQUEST_MODIFIED, 출연은 생기지 않는다")
    void acceptAfterModified() {
        AppearanceRequestResDto sent = send();
        appearanceRequestFacade.updateBasicInfo(HOST, sent.id(), "바뀐 제목", null, null);

        assertErrorCode(() -> appearanceRequestFacade.accept(GUEST, sent.id(), sent.version()),
                AppearanceErrorCode.APPEARANCE_REQUEST_MODIFIED);
        assertThat(appearanceRepository.count()).isZero();
    }

    @Test
    @DisplayName("호스트가 수락하면 NOT_REQUEST_GUEST, 다른 회원이면 APPEARANCE_REQUEST_NOT_FOUND")
    void acceptByNonGuest() {
        AppearanceRequestResDto sent = send();

        assertErrorCode(() -> appearanceRequestFacade.accept(HOST, sent.id(), sent.version()),
                AppearanceErrorCode.NOT_REQUEST_GUEST);
        assertErrorCode(() -> appearanceRequestFacade.accept(OTHER, sent.id(), sent.version()),
                AppearanceErrorCode.APPEARANCE_REQUEST_NOT_FOUND);
    }

    @Test
    @DisplayName("요청을 보낸 뒤 호스트가 정지됐으면 수락할 수 없다 (호스트 기준 메시지)")
    void acceptWhenHostSuspended() {
        AppearanceRequestResDto sent = send();
        appearanceUserFacade.syncModified(AppearanceUserFixture.user(HOST, Role.INFLUENCER, UserStatus.SUSPENDED));

        assertThatThrownBy(() -> appearanceRequestFacade.accept(GUEST, sent.id(), sent.version()))
                .isInstanceOf(AppearanceException.class)
                .hasMessage("호스트가 현재 출연 거래를 할 수 없는 회원입니다.")
                .extracting("errorCode").isEqualTo(AppearanceErrorCode.APPEARANCE_USER_NOT_ACTIVE);
        assertThat(appearanceRepository.count()).isZero();
    }

    @Test
    @DisplayName("수락 기한이 지났으면 DB 상태가 아직 REQUESTED여도 수락·거절·취소를 막는다")
    void expiredButNotYetProcessed() {
        // 이틀 전에 보낸 요청서: 수락 기한(보낸 시각 + 2일)이 이미 지났지만 만료 처리는 아직 안 됨
        LocalDateTime sentAt = LocalDateTime.now(clock).minusDays(2).minusMinutes(1);
        AppearanceRequest request = AppearanceRequest.create(HOST, GUEST, AppearanceRequestFixture.content(sentAt),
                appearancePolicy, sentAt);
        request.markRequested();
        AppearanceRequest saved = appearanceRequestRepository.saveAndFlush(request);

        assertErrorCode(() -> appearanceRequestFacade.accept(GUEST, saved.getId(), saved.getVersion()),
                AppearanceErrorCode.APPEARANCE_REQUEST_EXPIRED);
        assertErrorCode(() -> appearanceRequestFacade.reject(GUEST, saved.getId(), null),
                AppearanceErrorCode.APPEARANCE_REQUEST_EXPIRED);
        assertErrorCode(() -> appearanceRequestFacade.cancel(HOST, saved.getId()),
                AppearanceErrorCode.APPEARANCE_REQUEST_EXPIRED);
        assertThat(appearanceRequestRepository.findById(saved.getId()).orElseThrow().getStatus())
                .isEqualTo(AppearanceRequestStatus.REQUESTED);
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("로그인 역할이 일반 유저면 Facade의 @PreAuthorize에서 거절한다")
    void userRoleDenied() {
        assertThatThrownBy(() -> appearanceRequestFacade.accept(GUEST, 1L, 0L))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> appearanceRequestFacade.reject(GUEST, 1L, null))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> appearanceRequestFacade.cancel(HOST, 1L))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ---------------------------------------------------------------------------------------------
    // 거절·응답 전 취소와 HP 반환
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("거절하면 REJECTED + 사유 기록, 반환 작업이 바로 실행되어 DONE, Billing에 호스트 반환 요청")
    void reject() {
        AppearanceRequestResDto sent = send();

        AppearanceRequestResDto rejected = appearanceRequestFacade.reject(GUEST, sent.id(), "다른 방송 일정이 있습니다.");

        assertThat(rejected.status()).isEqualTo(AppearanceRequestStatus.REJECTED);
        assertThat(rejected.rejectReason()).isEqualTo("다른 방송 일정이 있습니다.");
        assertThat(rejected.hpReleaseStatus()).isEqualTo(HpTaskStatus.DONE);
        HpTask task = onlyTask();
        assertThat(task.getAmount()).isEqualTo(FEE);
        assertThat(task.getMemberId()).isEqualTo(HOST);
        assertThat(task.getIdempotencyKey()).isEqualTo("APPEARANCE_REQUEST-" + sent.id() + "-RELEASE-1");
        assertThat(task.getAttemptCount()).isEqualTo(1);
        assertReleaseSent(sent.id());
    }

    @Test
    @DisplayName("반환이 실패해도 거절은 성공하고, 작업은 PENDING으로 남아 시도 횟수·오류·다음 시도 시각을 기록한다")
    void rejectWhenReleaseFails() {
        AppearanceRequestResDto sent = send();
        BillingStubs.unavailable(billingClient);
        LocalDateTime before = LocalDateTime.now(clock);

        AppearanceRequestResDto rejected = appearanceRequestFacade.reject(GUEST, sent.id(), null);

        LocalDateTime after = LocalDateTime.now(clock);
        assertThat(rejected.status()).isEqualTo(AppearanceRequestStatus.REJECTED);
        assertThat(rejected.hpReleaseStatus()).isEqualTo(HpTaskStatus.PENDING);
        HpTask task = onlyTask();
        assertThat(task.getAttemptCount()).isEqualTo(1);
        assertThat(task.getLastError()).isEqualTo("UNKNOWN_RESULT: Billing 응답 없음 (테스트)");
        // 첫 재시도 간격 1분, 지터 ±20% (출연 시계 기준)
        assertThat(task.getNextAttemptAt()).isBetween(before.plusSeconds(48), after.plusSeconds(72));
    }

    @Test
    @DisplayName("지갑 호출 중 예상하지 못한 예외가 나도 거절은 성공하고, 작업은 PENDING으로 남아 시도 1회·예외 이름을 기록한다 (재시도 정책)")
    void rejectWhenUnexpectedFailure() {
        AppearanceRequestResDto sent = send();
        doThrow(new IllegalStateException("요청을 만들지 못함")).when(billingClient).release(any());

        AppearanceRequestResDto rejected = appearanceRequestFacade.reject(GUEST, sent.id(), null);

        assertThat(rejected.status()).isEqualTo(AppearanceRequestStatus.REJECTED);
        assertThat(rejected.hpReleaseStatus()).isEqualTo(HpTaskStatus.PENDING);
        HpTask task = onlyTask();
        assertThat(task.getAttemptCount()).isEqualTo(1);
        assertThat(task.getLastError()).isEqualTo("IllegalStateException: 요청을 만들지 못함");
        assertThat(task.getNextAttemptAt()).isNotNull();
    }

    @Test
    @DisplayName("Billing이 반환을 거절하면(보류 없음) 거절은 성공하고, 작업은 재시도 없이 바로 DEAD로 Billing 오류 코드를 기록한다")
    void rejectWhenBillingRejectsRelease() {
        AppearanceRequestResDto sent = send();
        BillingStubs.reject(billingClient, Rejection.HOLD_NOT_FOUND);

        AppearanceRequestResDto rejected = appearanceRequestFacade.reject(GUEST, sent.id(), null);

        assertThat(rejected.status()).isEqualTo(AppearanceRequestStatus.REJECTED);
        assertThat(rejected.hpReleaseStatus()).isEqualTo(HpTaskStatus.DEAD);
        assertThat(onlyTask().getLastError()).isEqualTo("HOLD_NOT_FOUND: HP 보류를 찾을 수 없습니다.");
        assertThat(onlyTask().getAttemptCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("응답 전 취소하면 CANCELED + 반환 완료")
    void cancel() {
        AppearanceRequestResDto sent = send();

        AppearanceRequestResDto canceled = appearanceRequestFacade.cancel(HOST, sent.id());

        assertThat(canceled.status()).isEqualTo(AppearanceRequestStatus.CANCELED);
        assertThat(canceled.canceledAt()).isNotNull();
        assertThat(canceled.hpReleaseStatus()).isEqualTo(HpTaskStatus.DONE);
        assertThat(onlyTask().getReason().name()).isEqualTo("REQUEST_CANCELED");
        assertReleaseSent(sent.id());
    }

    @Test
    @DisplayName("호스트가 거절하면 NOT_REQUEST_GUEST, 다른 회원이면 APPEARANCE_REQUEST_NOT_FOUND. 요청서·반환 작업은 그대로")
    void rejectByNonGuest() {
        AppearanceRequestResDto sent = send();

        assertErrorCode(() -> appearanceRequestFacade.reject(HOST, sent.id(), null), AppearanceErrorCode.NOT_REQUEST_GUEST);
        assertErrorCode(() -> appearanceRequestFacade.reject(OTHER, sent.id(), null),
                AppearanceErrorCode.APPEARANCE_REQUEST_NOT_FOUND);
        assertThat(appearanceRequestFacade.getDetail(GUEST, sent.id()).status()).isEqualTo(AppearanceRequestStatus.REQUESTED);
        assertThat(hpTaskRepository.count()).isZero();
    }

    @Test
    @DisplayName("다른 회원이 취소하면 APPEARANCE_REQUEST_NOT_FOUND, 반환 작업은 생기지 않는다")
    void cancelByOther() {
        AppearanceRequestResDto sent = send();

        assertErrorCode(() -> appearanceRequestFacade.cancel(OTHER, sent.id()),
                AppearanceErrorCode.APPEARANCE_REQUEST_NOT_FOUND);
        assertThat(hpTaskRepository.count()).isZero();
    }

    @Test
    @DisplayName("게스트가 취소하면 NOT_REQUEST_HOST, 반환 작업은 생기지 않는다")
    void cancelByGuest() {
        AppearanceRequestResDto sent = send();

        assertErrorCode(() -> appearanceRequestFacade.cancel(GUEST, sent.id()), AppearanceErrorCode.NOT_REQUEST_HOST);
        assertThat(hpTaskRepository.count()).isZero();
    }

    @Test
    @DisplayName("이미 응답한 요청서에 다시 응답하면 APPEARANCE_REQUEST_NOT_AWAITING_RESPONSE, 반환 작업도 하나뿐")
    void respondTwice() {
        AppearanceRequestResDto sent = send();
        appearanceRequestFacade.reject(GUEST, sent.id(), null);

        assertErrorCode(() -> appearanceRequestFacade.reject(GUEST, sent.id(), null),
                AppearanceErrorCode.APPEARANCE_REQUEST_NOT_AWAITING_RESPONSE);
        assertErrorCode(() -> appearanceRequestFacade.cancel(HOST, sent.id()),
                AppearanceErrorCode.APPEARANCE_REQUEST_NOT_AWAITING_RESPONSE);
        assertThat(hpTaskRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("수락과 응답 전 취소가 동시에 들어오면 하나만 성공한다 (출연 또는 반환 작업 중 하나만 생김)")
    void concurrentAcceptAndCancel() throws Exception {
        AppearanceRequestResDto sent = send();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<String> accept = () -> {
            ready.countDown();
            start.await();
            try {
                transactionTemplate.executeWithoutResult(status ->
                        appearanceRequestAcceptUseCase.accept(GUEST, sent.id(), sent.version()));
                return "ACCEPTED";
            } catch (AppearanceException exception) {
                return exception.getErrorCode().name();
            }
        };
        Callable<String> cancel = () -> {
            ready.countDown();
            start.await();
            try {
                appearanceRequestCancelUseCase.cancel(HOST, sent.id());
                return "CANCELED";
            } catch (AppearanceException exception) {
                return exception.getErrorCode().name();
            }
        };

        List<String> results = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<String> acceptResult = executor.submit(accept);
            Future<String> cancelResult = executor.submit(cancel);
            ready.await();
            start.countDown();
            results.add(acceptResult.get());
            results.add(cancelResult.get());
        }

        long succeeded = results.stream().filter(r -> r.equals("ACCEPTED") || r.equals("CANCELED")).count();
        assertThat(succeeded).isEqualTo(1);
        assertThat(results).anyMatch(r -> r.equals("CONCURRENT_UPDATE")
                || r.equals("APPEARANCE_REQUEST_NOT_AWAITING_RESPONSE"));
        assertThat(appearanceRepository.count() + hpTaskRepository.count()).isEqualTo(1);
    }

    // ---------------------------------------------------------------------------------------------
    // 조회
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("상태 필터: 여러 상태를 골라 조회하고, 생략하면 전체")
    void statusFilter() {
        AppearanceRequestResDto toGuest = send();
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(4L));
        AppearanceRequestResDto toOther = appearanceRequestFacade.create(HOST, 4L,
                AppearanceRequestFixture.content(LocalDateTime.now(clock)));
        appearanceRequestFacade.reject(GUEST, toGuest.id(), null);

        Page<AppearanceRequestSummaryResDto> rejectedOnly = appearanceRequestFacade.getSent(HOST,
                List.of(AppearanceRequestStatus.REJECTED, AppearanceRequestStatus.CANCELED), PageRequest.of(0, 20));
        Page<AppearanceRequestSummaryResDto> all = appearanceRequestFacade.getSent(HOST, null, PageRequest.of(0, 20));

        assertThat(rejectedOnly.getContent()).extracting(AppearanceRequestSummaryResDto::id)
                .containsExactly(toGuest.id());
        assertThat(all.getContent()).extracting(AppearanceRequestSummaryResDto::id)
                .containsExactly(toOther.id(), toGuest.id());
    }

    @Test
    @DisplayName("숨김 상태(HOLD_PENDING·HOLD_FAILED)로 필터하면 INVALID_REQUEST")
    void hiddenStatusFilter() {
        assertErrorCode(() -> appearanceRequestFacade.getSent(HOST, List.of(AppearanceRequestStatus.HOLD_FAILED),
                PageRequest.of(0, 20)), AppearanceErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("상세에 버전이 담기고, 수락 후에는 출연 ID가 담긴다")
    void detailHasVersionAndAppearanceId() {
        AppearanceRequestResDto sent = send();
        assertThat(appearanceRequestFacade.getDetail(GUEST, sent.id()).version()).isEqualTo(sent.version());

        appearanceRequestFacade.accept(GUEST, sent.id(), sent.version());

        AppearanceRequestResDto detail = appearanceRequestFacade.getDetail(HOST, sent.id());
        assertThat(detail.appearanceId()).isNotNull();
        assertThat(detail.hpReleaseStatus()).isNull();
    }

    /** Billing에 요청서 보류 전액 반환을 요청했는지 (반환 요청 ID {@code APPEARANCE_REQUEST-{id}-RELEASE-1}) */
    private void assertReleaseSent(Long requestId) {
        assertThat(BillingCalls.releases(billingClient)).singleElement().satisfies(release -> {
            assertThat(release.userId()).isEqualTo(HOST);
            assertThat(release.referenceType()).isEqualTo(BillingReferenceType.APPEARANCE_REQUEST);
            assertThat(release.referenceId()).isEqualTo(requestId);
            assertThat(release.releaseRequestId()).isEqualTo("APPEARANCE_REQUEST-" + requestId + "-RELEASE-1");
            assertThat(release.amountHp()).isEqualTo(FEE);
        });
    }
}
