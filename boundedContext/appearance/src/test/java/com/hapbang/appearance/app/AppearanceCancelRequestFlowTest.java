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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import com.hapbang.appearance.app.dto.AppearanceApplicationResDto;
import com.hapbang.appearance.app.dto.AppearanceCancelRequestResDto;
import com.hapbang.appearance.app.dto.AppearanceRequestResDto;
import com.hapbang.appearance.app.dto.AppearanceResDto;
import com.hapbang.appearance.app.dto.AppearanceSummaryResDto;
import com.hapbang.appearance.config.AppearanceConfig;
import com.hapbang.appearance.domain.AppearanceCancelRequestStatus;
import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.domain.AppearanceRole;
import com.hapbang.appearance.domain.AppearanceStatus;
import com.hapbang.appearance.domain.HpTask;
import com.hapbang.appearance.domain.HpTaskReason;
import com.hapbang.appearance.domain.HpTaskRefType;
import com.hapbang.appearance.domain.HpTaskStatus;
import com.hapbang.appearance.fixture.AppearanceRecruitFixture;
import com.hapbang.appearance.fixture.AppearanceRequestFixture;
import com.hapbang.appearance.fixture.AppearanceUserFixture;
import com.hapbang.appearance.out.HpTaskRepository;
import com.hapbang.appearance.out.billing.FakeBillingClient;
import com.hapbang.shared.appearance.event.AppearanceCancelRequestAcceptedEvent;
import com.hapbang.shared.appearance.event.AppearanceCancelRequestRejectedEvent;
import com.hapbang.shared.appearance.event.AppearanceCancelRequestedEvent;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 출연 취소 요청(요청·수락·거절·자동 거절)과 출연 조회를 DB와 Fake Billing으로 확인한다 (ISSUE.md APR-31).
 * 출연료는 30,000 HP. 방송 예정 일시는 DB 값을 과거로 옮겨 지난 것으로 만든다.
 */
@IntegrationTest
@RecordApplicationEvents
@WithMockUser(roles = "INFLUENCER")
class AppearanceCancelRequestFlowTest {

    private static final Long HOST = 1L;
    private static final Long GUEST = 2L;
    private static final Long GUEST2 = 3L;
    private static final Long OTHER = 4L;
    private static final long FEE = 30_000L;
    private static final PageRequest FIRST_PAGE = PageRequest.of(0, 20);

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
    AppearanceCancelAutoRejectUseCase appearanceCancelAutoRejectUseCase;

    @Autowired
    HpTaskRepository hpTaskRepository;

    @Autowired
    FakeBillingClient fakeBillingClient;

    @Autowired
    ApplicationEvents applicationEvents;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    @Qualifier(AppearanceConfig.CLOCK)
    Clock clock;

    @BeforeEach
    void setUp() {
        for (Long id : List.of(HOST, GUEST, GUEST2, OTHER)) {
            appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(id));
        }
        applicationEvents.clear();
    }

    @AfterEach
    void tearDown() {
        fakeBillingClient.reset();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /** 호스트가 게스트에게 보낸 요청서가 수락돼 성립한 출연 */
    private RequestAppearance establishFromRequest(Long guestId) {
        AppearanceRequestResDto sent = appearanceRequestFacade.create(HOST, guestId,
                AppearanceRequestFixture.content(now()));
        AppearanceRequestResDto accepted = appearanceRequestFacade.accept(guestId, sent.id(), sent.version());
        return new RequestAppearance(sent.id(), accepted.appearanceId());
    }

    /** 정원 {@code recruitCount}명 모집글에 게스트가 지원하고 호스트가 수락해 성립한 출연 */
    private RecruitAppearance establishFromRecruit(int recruitCount) {
        Long recruitId = appearanceRecruitFacade.create(HOST,
                AppearanceRecruitFixture.content(now(), FEE, recruitCount)).id();
        Long applicationId = appearanceApplicationFacade.apply(GUEST, recruitId, "지원합니다.").id();
        AppearanceApplicationResDto accepted = appearanceApplicationFacade.accept(HOST, applicationId);
        return new RecruitAppearance(recruitId, accepted.appearanceId());
    }

    private AppearanceResDto requestCancel(Long appearanceId) {
        return appearanceFacade.requestCancel(GUEST, appearanceId, "일정이 생겼습니다.");
    }

    private void passSchedule(Long appearanceId) {
        jdbcTemplate.update("update appearances.appearances set scheduled_at = ? where id = ?",
                now().minusMinutes(1), appearanceId);
    }

    private List<HpTask> releaseTasks(HpTaskRefType refType, Long refId) {
        List<HpTask> tasks = new ArrayList<>();
        for (HpTask task : hpTaskRepository.findAll()) {
            if (task.getRefType() == refType && task.getRefId().equals(refId)) {
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

    private record RecruitAppearance(Long recruitId, Long appearanceId) {
    }

    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("요청서에서 성립한 출연")
    class FromRequest {

        @Test
        @DisplayName("취소 요청: 출연 CANCEL_REQUESTED, 이력 1건(REQUESTED·순번 1·사유), 호스트에게 알릴 이벤트 1건")
        void request() {
            RequestAppearance established = establishFromRequest(GUEST);

            AppearanceResDto requested = requestCancel(established.appearanceId());

            assertThat(requested.status()).isEqualTo(AppearanceStatus.CANCEL_REQUESTED);
            AppearanceCancelRequestResDto history = requested.cancelRequests().getFirst();
            assertThat(history.status()).isEqualTo(AppearanceCancelRequestStatus.REQUESTED);
            assertThat(history.sequence()).isEqualTo(1);
            assertThat(history.reason()).isEqualTo("일정이 생겼습니다.");
            assertThat(history.requestedAt()).isNotNull();
            List<AppearanceCancelRequestedEvent> events =
                    applicationEvents.stream(AppearanceCancelRequestedEvent.class).toList();
            assertThat(events).hasSize(1);
            assertThat(events.getFirst().cancelRequest().hostId()).isEqualTo(HOST);
            assertThat(events.getFirst().cancelRequest().appearanceId()).isEqualTo(established.appearanceId());
            assertThat(events.getFirst().cancelRequest().title()).isEqualTo(requested.title());
        }

        @Test
        @DisplayName("수락: 합의 취소, 출연료 30,000 전액 반환(CANCEL_AGREED, 출연별 반환 요청 ID, DONE), 보류 0, 게스트에게 알릴 이벤트")
        void accept() {
            RequestAppearance established = establishFromRequest(GUEST);
            requestCancel(established.appearanceId());

            AppearanceResDto accepted = appearanceFacade.acceptCancel(HOST, established.appearanceId());

            assertThat(accepted.status()).isEqualTo(AppearanceStatus.CANCELED_BY_AGREEMENT);
            assertThat(accepted.canceledAt()).isNotNull();
            assertThat(accepted.cancelRequests().getFirst().status()).isEqualTo(AppearanceCancelRequestStatus.ACCEPTED);
            HpTask task = releaseTasks(HpTaskRefType.APPEARANCE_REQUEST, established.requestId()).getFirst();
            assertThat(task.getReason()).isEqualTo(HpTaskReason.CANCEL_AGREED);
            assertThat(task.getAmount()).isEqualTo(FEE);
            assertThat(task.getIdempotencyKey()).isEqualTo("APPEARANCE_REQUEST-%d-RELEASE-APPEARANCE-%d"
                    .formatted(established.requestId(), established.appearanceId()));
            assertThat(task.getStatus()).isEqualTo(HpTaskStatus.DONE);
            assertThat(fakeBillingClient.heldAmount(BillingReferenceType.APPEARANCE_REQUEST, established.requestId()))
                    .isZero();
            assertThat(fakeBillingClient.balanceOf(HOST)).isEqualTo(FakeBillingClient.DEFAULT_BALANCE);
            assertThat(applicationEvents.stream(AppearanceCancelRequestAcceptedEvent.class)).hasSize(1);
            // 반환 진행 상태는 출연 상세에만 보이고, 요청서 상세(거절·취소·만료 반환용)에는 섞이지 않는다
            assertThat(accepted.hpReleaseStatus()).isEqualTo(HpTaskStatus.DONE);
            assertThat(appearanceRequestFacade.getDetail(HOST, established.requestId()).hpReleaseStatus()).isNull();
        }

        @Test
        @DisplayName("거절 → 출연 유지(ESTABLISHED, 거절 사유) → 1번 더 요청(순번 2) → 거절 → 3번째는 CANCEL_REQUEST_LIMIT_EXCEEDED")
        void rejectAndLimit() {
            Long appearanceId = establishFromRequest(GUEST).appearanceId();
            requestCancel(appearanceId);

            AppearanceResDto rejected = appearanceFacade.rejectCancel(HOST, appearanceId, "이미 홍보 중입니다.");

            assertThat(rejected.status()).isEqualTo(AppearanceStatus.ESTABLISHED);
            assertThat(rejected.cancelRequests().getFirst().status()).isEqualTo(AppearanceCancelRequestStatus.REJECTED);
            assertThat(rejected.cancelRequests().getFirst().rejectReason()).isEqualTo("이미 홍보 중입니다.");
            List<AppearanceCancelRequestRejectedEvent> events =
                    applicationEvents.stream(AppearanceCancelRequestRejectedEvent.class).toList();
            assertThat(events).hasSize(1);
            assertThat(events.getFirst().autoRejected()).isFalse();

            assertThat(requestCancel(appearanceId).cancelRequests()).extracting(AppearanceCancelRequestResDto::sequence)
                    .containsExactly(1, 2);
            appearanceFacade.rejectCancel(HOST, appearanceId, null);
            assertErrorCode(() -> requestCancel(appearanceId), AppearanceErrorCode.CANCEL_REQUEST_LIMIT_EXCEEDED);
            assertThat(hpTaskRepository.count()).isZero();
        }

        @Test
        @DisplayName("거부: 호스트가 요청 NOT_APPEARANCE_GUEST, 다른 회원 APPEARANCE_NOT_FOUND, 처리 중 다시 요청 CANCEL_REQUEST_ALREADY_PENDING, "
                + "게스트가 응답 NOT_APPEARANCE_HOST, 요청 없이 응답 CANCEL_REQUEST_NOT_PENDING, 사유 없음 INVALID_REQUEST")
        void rejected() {
            Long appearanceId = establishFromRequest(GUEST).appearanceId();
            assertErrorCode(() -> appearanceFacade.acceptCancel(HOST, appearanceId),
                    AppearanceErrorCode.CANCEL_REQUEST_NOT_PENDING);
            assertErrorCode(() -> appearanceFacade.requestCancel(GUEST, appearanceId, " "),
                    AppearanceErrorCode.INVALID_REQUEST);
            assertErrorCode(() -> appearanceFacade.requestCancel(HOST, appearanceId, "사유"),
                    AppearanceErrorCode.NOT_APPEARANCE_GUEST);
            assertErrorCode(() -> appearanceFacade.requestCancel(OTHER, appearanceId, "사유"),
                    AppearanceErrorCode.APPEARANCE_NOT_FOUND);

            requestCancel(appearanceId);
            assertErrorCode(() -> requestCancel(appearanceId), AppearanceErrorCode.CANCEL_REQUEST_ALREADY_PENDING);
            assertErrorCode(() -> appearanceFacade.acceptCancel(GUEST, appearanceId),
                    AppearanceErrorCode.NOT_APPEARANCE_HOST);
            assertErrorCode(() -> appearanceFacade.rejectCancel(OTHER, appearanceId, null),
                    AppearanceErrorCode.APPEARANCE_NOT_FOUND);
            assertErrorCode(() -> appearanceFacade.acceptCancel(HOST, 999_999L), AppearanceErrorCode.APPEARANCE_NOT_FOUND);
        }

        @Test
        @DisplayName("방송 예정 일시가 지나면 요청·수락·거절 모두 APPEARANCE_SCHEDULE_PASSED")
        void schedulePassed() {
            Long requestOnly = establishFromRequest(GUEST).appearanceId();
            passSchedule(requestOnly);
            assertErrorCode(() -> requestCancel(requestOnly), AppearanceErrorCode.APPEARANCE_SCHEDULE_PASSED);

            Long pending = establishFromRequest(GUEST2).appearanceId();
            appearanceFacade.requestCancel(GUEST2, pending, "사유");
            passSchedule(pending);
            assertErrorCode(() -> appearanceFacade.acceptCancel(HOST, pending),
                    AppearanceErrorCode.APPEARANCE_SCHEDULE_PASSED);
            assertErrorCode(() -> appearanceFacade.rejectCancel(HOST, pending, null),
                    AppearanceErrorCode.APPEARANCE_SCHEDULE_PASSED);
        }

        @Test
        @DisplayName("반환이 실패해도 합의 취소는 성공하고 반환 작업은 PENDING으로 남는다")
        void releaseFails() {
            RequestAppearance established = establishFromRequest(GUEST);
            requestCancel(established.appearanceId());
            fakeBillingClient.setUnavailable(true);

            AppearanceResDto accepted = appearanceFacade.acceptCancel(HOST, established.appearanceId());

            assertThat(accepted.status()).isEqualTo(AppearanceStatus.CANCELED_BY_AGREEMENT);
            assertThat(releaseTasks(HpTaskRefType.APPEARANCE_REQUEST, established.requestId()).getFirst().getStatus())
                    .isEqualTo(HpTaskStatus.PENDING);
        }
    }

    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("모집에서 성립한 출연")
    class FromRecruit {

        @Test
        @DisplayName("모집글이 마감 전이면 성립 인원만 1 줄고 반환하지 않는다(보류 유지). 빈자리에 다른 지원자를 수락할 수 있다")
        void beforeClose() {
            RecruitAppearance established = establishFromRecruit(1);
            Long secondApplication = appearanceApplicationFacade.apply(GUEST2, established.recruitId(), "저도요").id();
            assertErrorCode(() -> appearanceApplicationFacade.accept(HOST, secondApplication),
                    AppearanceErrorCode.RECRUIT_FULL);
            requestCancel(established.appearanceId());

            AppearanceResDto accepted = appearanceFacade.acceptCancel(HOST, established.appearanceId());

            assertThat(accepted.status()).isEqualTo(AppearanceStatus.CANCELED_BY_AGREEMENT);
            assertThat(appearanceRecruitFacade.getDetail(HOST, established.recruitId()).selectedCount()).isZero();
            assertThat(releaseTasks(HpTaskRefType.APPEARANCE_RECRUIT, established.recruitId())).isEmpty();
            assertThat(accepted.hpReleaseStatus()).isNull(); // 마감 전이라 반환하지 않음 (보류 유지)
            assertThat(fakeBillingClient.heldAmount(BillingReferenceType.APPEARANCE_RECRUIT, established.recruitId()))
                    .isEqualTo(FEE);
            assertThat(appearanceApplicationFacade.accept(HOST, secondApplication).appearanceId()).isNotNull();
        }

        @Test
        @DisplayName("마감 전 합의 취소 뒤 다시 채우지 않고 마감하면, 그 자리 출연료가 마감 반환에 함께 들어간다")
        void releasedOnClose() {
            RecruitAppearance established = establishFromRecruit(1);
            requestCancel(established.appearanceId());
            appearanceFacade.acceptCancel(HOST, established.appearanceId());

            appearanceRecruitFacade.closeEarly(HOST, established.recruitId());

            assertThat(releaseTasks(HpTaskRefType.APPEARANCE_RECRUIT, established.recruitId()))
                    .extracting(HpTask::getReason, HpTask::getAmount)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple(HpTaskReason.RECRUIT_CLOSED, FEE));
            assertThat(fakeBillingClient.heldAmount(BillingReferenceType.APPEARANCE_RECRUIT, established.recruitId()))
                    .isZero();
        }

        @Test
        @DisplayName("모집글이 마감된 뒤면 그 자리 출연료 30,000을 바로 반환한다 (출연별 반환 요청 ID, 마감 반환과 겹치지 않음)")
        void afterClose() {
            RecruitAppearance established = establishFromRecruit(1);
            appearanceRecruitFacade.closeEarly(HOST, established.recruitId()); // 정원이 차서 마감 반환 없음
            requestCancel(established.appearanceId());

            appearanceFacade.acceptCancel(HOST, established.appearanceId());

            HpTask task = releaseTasks(HpTaskRefType.APPEARANCE_RECRUIT, established.recruitId()).getFirst();
            assertThat(task.getAmount()).isEqualTo(FEE);
            assertThat(task.getReason()).isEqualTo(HpTaskReason.CANCEL_AGREED);
            assertThat(task.getIdempotencyKey()).isEqualTo("APPEARANCE_RECRUIT-%d-RELEASE-APPEARANCE-%d"
                    .formatted(established.recruitId(), established.appearanceId()));
            assertThat(task.getStatus()).isEqualTo(HpTaskStatus.DONE);
            assertThat(fakeBillingClient.heldAmount(BillingReferenceType.APPEARANCE_RECRUIT, established.recruitId()))
                    .isZero();
            assertThat(appearanceRecruitFacade.getDetail(HOST, established.recruitId()).selectedCount()).isZero();
        }

        @Test
        @DisplayName("반환 진행 상태가 섞이지 않는다: 마감 반환이 처리 중(PENDING)인데 합의 취소 반환이 끝나도(DONE) 모집글 상세는 마감 반환 상태, 출연 상세는 합의 취소 반환 상태")
        void releaseStatusNotMixed() {
            RecruitAppearance established = establishFromRecruit(3);
            fakeBillingClient.setUnavailable(true);
            appearanceRecruitFacade.closeEarly(HOST, established.recruitId()); // 미달 2명분 반환 실패 → PENDING
            fakeBillingClient.setUnavailable(false);
            requestCancel(established.appearanceId());

            AppearanceResDto accepted = appearanceFacade.acceptCancel(HOST, established.appearanceId());

            assertThat(accepted.hpReleaseStatus()).isEqualTo(HpTaskStatus.DONE);
            assertThat(appearanceRecruitFacade.getDetail(HOST, established.recruitId()).hpReleaseStatus())
                    .isEqualTo(HpTaskStatus.PENDING);
        }

        @Test
        @DisplayName("합의 취소 수락과 빈자리 지원 수락이 동시에 와도 교착 상태 없이 처리되고, 성립 인원 = 유지 중인 출연 수")
        void concurrentCancelAcceptAndApplicationAccept() throws Exception {
            RecruitAppearance established = establishFromRecruit(1);
            Long secondApplication = appearanceApplicationFacade.apply(GUEST2, established.recruitId(), "저도요").id();
            requestCancel(established.appearanceId());

            List<Object> results = runConcurrently(List.of(
                    () -> appearanceFacade.acceptCancel(HOST, established.appearanceId()),
                    () -> appearanceApplicationFacade.accept(HOST, secondApplication)));

            assertThat(results).allSatisfy(result -> assertThat(result).isInstanceOfAny(AppearanceResDto.class,
                    AppearanceApplicationResDto.class, AppearanceException.class));
            assertThat(results.getFirst()).isInstanceOf(AppearanceResDto.class);
            if (results.get(1) instanceof AppearanceException error) {
                assertThat(error.getErrorCode()).isIn(AppearanceErrorCode.RECRUIT_FULL,
                        AppearanceErrorCode.CONCURRENT_UPDATE);
            }
            int selectedCount = appearanceRecruitFacade.getDetail(HOST, established.recruitId()).selectedCount();
            long active = appearanceFacade.getMine(HOST, AppearanceRole.HOST, List.of(AppearanceStatus.ESTABLISHED),
                    FIRST_PAGE).getTotalElements();
            assertThat(selectedCount).isEqualTo((int) active);
        }
    }

    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("자동 거절 배치")
    class AutoReject {

        @Test
        @DisplayName("방송 예정 일시가 지난 처리 중 요청: AUTO_REJECTED, 출연 ESTABLISHED, HP 이동 없음, 자동 거절 이벤트. 다시 실행하면 0건")
        void rejectDue() {
            Long appearanceId = establishFromRequest(GUEST).appearanceId();
            requestCancel(appearanceId);
            passSchedule(appearanceId);

            assertThat(appearanceCancelAutoRejectUseCase.rejectDue()).isEqualTo(1);
            assertThat(appearanceCancelAutoRejectUseCase.rejectDue()).isZero();

            AppearanceResDto detail = appearanceFacade.getDetail(HOST, appearanceId);
            assertThat(detail.status()).isEqualTo(AppearanceStatus.ESTABLISHED);
            assertThat(detail.cancelRequests().getFirst().status())
                    .isEqualTo(AppearanceCancelRequestStatus.AUTO_REJECTED);
            assertThat(detail.cancelRequests().getFirst().respondedAt()).isNotNull();
            assertThat(hpTaskRepository.count()).isZero();
            List<AppearanceCancelRequestRejectedEvent> events =
                    applicationEvents.stream(AppearanceCancelRequestRejectedEvent.class).toList();
            assertThat(events).hasSize(1);
            assertThat(events.getFirst().autoRejected()).isTrue();
        }

        @Test
        @DisplayName("방송 예정 일시 전이면 그대로 둔다")
        void notDue() {
            Long appearanceId = establishFromRequest(GUEST).appearanceId();
            requestCancel(appearanceId);

            assertThat(appearanceCancelAutoRejectUseCase.rejectDue()).isZero();
            assertThat(appearanceFacade.getDetail(HOST, appearanceId).status())
                    .isEqualTo(AppearanceStatus.CANCEL_REQUESTED);
        }
    }

    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("동시 응답")
    class Concurrent {

        @Test
        @DisplayName("같은 요청에 수락과 거절이 동시에 와도 한쪽만 반영된다 (나중 것은 CANCEL_REQUEST_NOT_PENDING 또는 CONCURRENT_UPDATE)")
        void acceptAndReject() throws Exception {
            RequestAppearance established = establishFromRequest(GUEST);
            requestCancel(established.appearanceId());

            List<Object> results = runConcurrently(List.of(
                    () -> appearanceFacade.acceptCancel(HOST, established.appearanceId()),
                    () -> appearanceFacade.rejectCancel(HOST, established.appearanceId(), null)));

            assertThat(results).allSatisfy(result -> assertThat(result)
                    .isInstanceOfAny(AppearanceResDto.class, AppearanceException.class));
            assertThat(results).filteredOn(AppearanceResDto.class::isInstance).hasSize(1);
            assertThat(results).filteredOn(AppearanceException.class::isInstance).singleElement()
                    .satisfies(error -> assertThat(((AppearanceException) error).getErrorCode()).isIn(
                            AppearanceErrorCode.CANCEL_REQUEST_NOT_PENDING, AppearanceErrorCode.CONCURRENT_UPDATE));
            AppearanceResDto detail = appearanceFacade.getDetail(HOST, established.appearanceId());
            long held = fakeBillingClient.heldAmount(BillingReferenceType.APPEARANCE_REQUEST, established.requestId());
            if (detail.status() == AppearanceStatus.CANCELED_BY_AGREEMENT) {
                assertThat(held).isZero();
            } else {
                assertThat(detail.status()).isEqualTo(AppearanceStatus.ESTABLISHED);
                assertThat(held).isEqualTo(FEE);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("출연 조회")
    class Query {

        @Test
        @DisplayName("내 출연 목록: 호스트·게스트별, 최신순, 상태 필터, 제목은 출처(요청서·모집글) 제목. 상세는 호스트·게스트만")
        void mine() {
            Long fromRequest = establishFromRequest(GUEST).appearanceId();
            RecruitAppearance fromRecruit = establishFromRecruit(1);
            requestCancel(fromRequest);

            List<AppearanceSummaryResDto> hosting = appearanceFacade.getMine(HOST, AppearanceRole.HOST, null, FIRST_PAGE)
                    .getContent();
            assertThat(hosting).extracting(AppearanceSummaryResDto::id)
                    .containsExactly(fromRecruit.appearanceId(), fromRequest);
            assertThat(hosting).extracting(AppearanceSummaryResDto::title)
                    .containsExactly(AppearanceRecruitFixture.content(now()).title(),
                            AppearanceRequestFixture.content(now()).title());
            assertThat(appearanceFacade.getMine(GUEST, AppearanceRole.GUEST,
                    List.of(AppearanceStatus.CANCEL_REQUESTED), FIRST_PAGE).getContent())
                    .extracting(AppearanceSummaryResDto::id).containsExactly(fromRequest);
            assertThat(appearanceFacade.getMine(GUEST, AppearanceRole.HOST, null, FIRST_PAGE).getContent()).isEmpty();
            assertErrorCode(() -> appearanceFacade.getDetail(OTHER, fromRequest), AppearanceErrorCode.APPEARANCE_NOT_FOUND);
            assertThat(appearanceFacade.getDetail(GUEST, fromRecruit.appearanceId()).recruitId())
                    .isEqualTo(fromRecruit.recruitId());
        }
    }

    // ---------------------------------------------------------------------------------------------
    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("세션 역할이 USER면 출연 조회·취소 요청·응답 모두 막힌다 (@PreAuthorize)")
    void userRole() {
        assertAllDenied();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("세션 역할이 ADMIN이어도 모두 막힌다 (인플루언서만)")
    void adminRole() {
        assertAllDenied();
    }

    private void assertAllDenied() {
        List<Runnable> calls = List.of(
                () -> appearanceFacade.getMine(GUEST, AppearanceRole.GUEST, null, FIRST_PAGE),
                () -> appearanceFacade.getDetail(GUEST, 1L),
                () -> appearanceFacade.requestCancel(GUEST, 1L, "사유"),
                () -> appearanceFacade.acceptCancel(HOST, 1L),
                () -> appearanceFacade.rejectCancel(HOST, 1L, null));
        for (Runnable call : calls) {
            assertThatThrownBy(call::run).isInstanceOf(AccessDeniedException.class);
        }
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
