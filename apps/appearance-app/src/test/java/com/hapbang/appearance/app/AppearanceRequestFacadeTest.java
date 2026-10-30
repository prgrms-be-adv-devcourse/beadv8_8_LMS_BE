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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

import com.hapbang.appearance.app.dto.AppearanceRequestResDto;
import com.hapbang.appearance.app.dto.AppearanceRequestSummaryResDto;
import com.hapbang.appearance.config.AppearanceConfig;
import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.domain.AppearanceHoldRejectedException;
import com.hapbang.appearance.domain.AppearanceRequest;
import com.hapbang.appearance.domain.AppearanceRequestStatus;
import com.hapbang.appearance.fixture.AppearanceRequestFixture;
import com.hapbang.appearance.fixture.AppearanceUserFixture;
import com.hapbang.appearance.out.AppearanceRequestRepository;
import com.hapbang.appearance.out.billing.BillingClient;
import com.hapbang.appearance.support.AppearanceIntegrationTest;
import com.hapbang.appearance.support.BillingCalls;
import com.hapbang.appearance.support.BillingStubs;
import com.hapbang.appearance.support.BillingStubs.Rejection;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;

/**
 * 출연 요청서 보내기·조회·수정 흐름을 DB와 Billing 대역으로 확인한다.
 * 세션 역할은 {@code @WithMockUser}로 넣는다 (Facade의 {@code @PreAuthorize} 검사용).
 */
@AppearanceIntegrationTest
@WithMockUser(roles = "INFLUENCER")
class AppearanceRequestFacadeTest {

    private static final Long HOST = 1L;
    private static final Long GUEST = 2L;
    private static final Long OTHER = 3L;
    private static final long FEE = AppearanceRequestFixture.FEE;

    @Autowired
    AppearanceRequestFacade appearanceRequestFacade;

    @Autowired
    AppearanceRequestCreateUseCase appearanceRequestCreateUseCase;

    @Autowired
    AppearanceUserFacade appearanceUserFacade;

    @Autowired
    AppearanceUserSupport appearanceUserSupport;

    @Autowired
    AppearanceRequestRepository appearanceRequestRepository;

    @Autowired
    BillingClient billingClient;

    @Autowired
    @Qualifier(AppearanceConfig.CLOCK)
    Clock clock;

    @BeforeEach
    void setUp() {
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(HOST));
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(GUEST));
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(OTHER));
    }


    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private AppearanceRequestResDto send(Long hostId, Long guestId) {
        return appearanceRequestFacade.create(hostId, guestId, AppearanceRequestFixture.content(now()));
    }

    private AppearanceRequest findOnly() {
        List<AppearanceRequest> all = appearanceRequestRepository.findAll();
        assertThat(all).hasSize(1);
        return all.getFirst();
    }

    private static void assertErrorCode(Runnable action, AppearanceErrorCode errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(AppearanceException.class)
                .extracting("errorCode").isEqualTo(errorCode);
    }

    /** 지갑 거절은 출연 코드로 바꿔 응답한다 (사용자에게 알릴 거절만 같은 이름의 코드, 그 밖은 HP_HOLD_FAILED) */
    private static void assertHoldRejected(Runnable action, AppearanceErrorCode errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(AppearanceHoldRejectedException.class)
                .hasMessage(errorCode.getMessage())
                .extracting("errorCode").isEqualTo(errorCode);
    }

    // ---------------------------------------------------------------------------------------------
    // 보내기
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("보내면 Billing에 호스트·요청서·출연료로 보류를 요청하고 REQUESTED가 된다")
    void create() {
        AppearanceRequestResDto response = send(HOST, GUEST);

        assertThat(response.status()).isEqualTo(AppearanceRequestStatus.REQUESTED);
        assertThat(findOnly().getStatus()).isEqualTo(AppearanceRequestStatus.REQUESTED);
        assertThat(BillingCalls.holds(billingClient)).singleElement().satisfies(hold -> {
            assertThat(hold.userId()).isEqualTo(HOST);
            assertThat(hold.referenceType()).isEqualTo(BillingReferenceType.APPEARANCE_REQUEST);
            assertThat(hold.referenceId()).isEqualTo(response.id());
            assertThat(hold.amountHp()).isEqualTo(FEE);
        });
    }

    @Test
    @DisplayName("지갑이 보류를 거절하면(잔액 부족) 409 INSUFFICIENT_BALANCE(출연 문구), 요청서는 HOLD_FAILED로 남고 목록에 보이지 않는다")
    void insufficientBalance() {
        BillingStubs.reject(billingClient, Rejection.INSUFFICIENT_BALANCE);

        assertHoldRejected(() -> send(HOST, GUEST), AppearanceErrorCode.INSUFFICIENT_BALANCE);

        assertThat(findOnly().getStatus()).isEqualTo(AppearanceRequestStatus.HOLD_FAILED);
        assertThat(appearanceRequestFacade.getSent(HOST, null, PageRequest.of(0, 20)).getTotalElements()).isZero();
    }

    @Test
    @DisplayName("보류 거절로 실패한 뒤에는 같은 게스트에게 다시 보낼 수 있다")
    void retryAfterHoldFailed() {
        BillingStubs.reject(billingClient, Rejection.INSUFFICIENT_BALANCE);
        assertHoldRejected(() -> send(HOST, GUEST), AppearanceErrorCode.INSUFFICIENT_BALANCE);
        BillingStubs.available(billingClient);

        AppearanceRequestResDto response = send(HOST, GUEST);

        assertThat(response.status()).isEqualTo(AppearanceRequestStatus.REQUESTED);
    }

    @Test
    @DisplayName("잔액 부족 외의 지갑 거절(지갑 없음)은 지갑 코드·설명을 숨기고 500 HP_HOLD_FAILED, 요청서는 HOLD_FAILED")
    void walletNotFound() {
        BillingStubs.reject(billingClient, Rejection.WALLET_NOT_FOUND);

        assertHoldRejected(() -> send(HOST, GUEST), AppearanceErrorCode.HP_HOLD_FAILED);

        assertThat(findOnly().getStatus()).isEqualTo(AppearanceRequestStatus.HOLD_FAILED);
    }

    @Test
    @DisplayName("Billing이 응답하지 않으면 BILLING_UNAVAILABLE, 요청서는 HOLD_PENDING으로 남고 바로 다시 보내면 중복으로 막힌다")
    void billingUnavailable() {
        BillingStubs.unavailable(billingClient);

        assertErrorCode(() -> send(HOST, GUEST), AppearanceErrorCode.BILLING_UNAVAILABLE);

        assertThat(findOnly().getStatus()).isEqualTo(AppearanceRequestStatus.HOLD_PENDING);
        assertThat(appearanceRequestFacade.getSent(HOST, null, PageRequest.of(0, 20)).getTotalElements()).isZero();

        BillingStubs.available(billingClient);
        assertErrorCode(() -> send(HOST, GUEST), AppearanceErrorCode.DUPLICATE_APPEARANCE_REQUEST);
    }

    @Test
    @DisplayName("응답 대기 중인 요청이 있으면 같은 게스트에게 다시 보낼 수 없다 (다른 게스트는 가능)")
    void duplicate() {
        send(HOST, GUEST);

        assertErrorCode(() -> send(HOST, GUEST), AppearanceErrorCode.DUPLICATE_APPEARANCE_REQUEST);
        assertThat(send(HOST, OTHER).status()).isEqualTo(AppearanceRequestStatus.REQUESTED);
        assertThat(BillingCalls.holds(billingClient)).hasSize(2);
    }

    @Test
    @DisplayName("같은 게스트에게 동시에 보내면 DB 제약으로 1건만 저장되고 나머지는 DUPLICATE_APPEARANCE_REQUEST")
    void concurrentDuplicate() throws Exception {
        int threads = 5;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                ready.countDown();
                start.await();
                try {
                    appearanceRequestCreateUseCase.createPending(HOST, GUEST, AppearanceRequestFixture.content(now()));
                    return "SAVED";
                } catch (AppearanceException exception) {
                    return exception.getErrorCode().name();
                }
            });
        }

        List<String> results = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            List<Future<String>> futures = tasks.stream().map(executor::submit).toList();
            ready.await();
            start.countDown();
            for (Future<String> future : futures) {
                results.add(future.get());
            }
        }

        assertThat(results).filteredOn("SAVED"::equals).hasSize(1);
        assertThat(results).filteredOn("DUPLICATE_APPEARANCE_REQUEST"::equals).hasSize(threads - 1);
        assertThat(appearanceRequestRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("게스트가 일반 유저면 NOT_INFLUENCER (게스트 기준 메시지), 없는 회원이면 APPEARANCE_USER_NOT_FOUND")
    void guestNotEligible() {
        appearanceUserFacade.syncJoined(AppearanceUserFixture.normalUser(10L));

        assertThatThrownBy(() -> send(HOST, 10L))
                .isInstanceOf(AppearanceException.class)
                .hasMessage("인플루언서 이상에게만 출연을 요청할 수 있습니다.")
                .extracting("errorCode").isEqualTo(AppearanceErrorCode.NOT_INFLUENCER);
        assertThatThrownBy(() -> send(HOST, 999L))
                .isInstanceOf(AppearanceException.class)
                .hasMessage("게스트 회원 정보를 찾을 수 없습니다.")
                .extracting("errorCode").isEqualTo(AppearanceErrorCode.APPEARANCE_USER_NOT_FOUND);
        assertThat(appearanceRequestRepository.count()).isZero();
    }

    @Test
    @DisplayName("세션은 인플루언서여도 출연 회원이 정지 상태면 APPEARANCE_USER_NOT_ACTIVE (로그인 후 정지 대응)")
    void suspendedHost() {
        appearanceUserFacade.syncModified(AppearanceUserFixture.user(HOST, Role.INFLUENCER, UserStatus.SUSPENDED));

        assertErrorCode(() -> send(HOST, GUEST), AppearanceErrorCode.APPEARANCE_USER_NOT_ACTIVE);
        assertThat(appearanceRequestRepository.count()).isZero();
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("세션 역할이 일반 유저면 Facade의 @PreAuthorize에서 거절한다")
    void userRoleDenied() {
        assertThatThrownBy(() -> send(HOST, GUEST)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> appearanceRequestFacade.getSent(HOST, null, PageRequest.of(0, 20)))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(appearanceRequestRepository.count()).isZero();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("관리자도 Facade의 @PreAuthorize에서 거절한다 (출연 거래 참여 불가)")
    void adminRoleDenied() {
        assertThatThrownBy(() -> send(HOST, GUEST)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("출연 회원이 일반 유저로 강등돼 있으면 조회도 NOT_INFLUENCER (로그인 후 강등 대응)")
    void demotedUserCannotList() {
        appearanceUserFacade.syncModified(AppearanceUserFixture.user(HOST, Role.USER, UserStatus.ACTIVE));

        assertErrorCode(() -> appearanceRequestFacade.getSent(HOST, null, PageRequest.of(0, 20)),
                AppearanceErrorCode.NOT_INFLUENCER);
    }

    @Test
    @DisplayName("수정 값의 형식은 도메인에서도 확인한다 (요청 DTO 검증을 거치지 않는 호출 방어)")
    void updateInvalidValueInDomain() {
        AppearanceRequestResDto sent = send(HOST, GUEST);

        assertErrorCode(() -> appearanceRequestFacade.updateBasicInfo(HOST, sent.id(), " ", null, null),
                AppearanceErrorCode.INVALID_REQUEST);
        assertErrorCode(() -> appearanceRequestFacade.updateBasicInfo(HOST, sent.id(), null, null, "ftp://a.com"),
                AppearanceErrorCode.INVALID_REQUEST);
        assertThat(appearanceRequestRepository.findById(sent.id()).orElseThrow().getTitle()).isEqualTo(sent.title());
    }

    @Test
    @DisplayName("같은 수정을 두 번 보내도 결과가 같다 (멱등: 두 번째는 바뀐 값이 없어 버전·수정 시각도 그대로)")
    void updateIdempotent() {
        AppearanceRequestResDto sent = send(HOST, GUEST);

        appearanceRequestFacade.updateBasicInfo(HOST, sent.id(), "새 제목", null, null);
        AppearanceRequest afterFirst = appearanceRequestRepository.findById(sent.id()).orElseThrow();
        appearanceRequestFacade.updateBasicInfo(HOST, sent.id(), "새 제목", null, null);
        AppearanceRequest afterSecond = appearanceRequestRepository.findById(sent.id()).orElseThrow();

        assertThat(afterSecond.getTitle()).isEqualTo("새 제목");
        assertThat(afterSecond.getVersion()).isEqualTo(afterFirst.getVersion());
        assertThat(afterSecond.getUpdatedAt()).isEqualTo(afterFirst.getUpdatedAt());
    }

    // ---------------------------------------------------------------------------------------------
    // 조회
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("보낸 목록은 호스트 기준, 받은 목록은 게스트 기준으로 최신순 조회한다")
    void lists() {
        AppearanceRequestResDto toGuest = send(HOST, GUEST);
        AppearanceRequestResDto toOther = send(HOST, OTHER);
        AppearanceRequestResDto fromOther = send(OTHER, HOST);

        Page<AppearanceRequestSummaryResDto> sent = appearanceRequestFacade.getSent(HOST, null, PageRequest.of(0, 20));
        Page<AppearanceRequestSummaryResDto> received =
                appearanceRequestFacade.getReceived(HOST, null, PageRequest.of(0, 20));

        assertThat(sent.getContent()).extracting(AppearanceRequestSummaryResDto::id)
                .containsExactly(toOther.id(), toGuest.id());
        assertThat(received.getContent()).extracting(AppearanceRequestSummaryResDto::id)
                .containsExactly(fromOther.id());
    }

    @Test
    @DisplayName("목록은 page·size로 나눠 조회한다")
    void paging() {
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(4L));
        send(HOST, GUEST);
        send(HOST, OTHER);
        AppearanceRequestResDto latest = send(HOST, 4L);

        Page<AppearanceRequestSummaryResDto> firstPage = appearanceRequestFacade.getSent(HOST, null, PageRequest.of(0, 2));
        Page<AppearanceRequestSummaryResDto> secondPage =
                appearanceRequestFacade.getSent(HOST, null, PageRequest.of(1, 2));

        assertThat(firstPage.getTotalElements()).isEqualTo(3);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
        assertThat(firstPage.getContent().getFirst().id()).isEqualTo(latest.id());
        assertThat(secondPage.getContent()).hasSize(1);
    }

    @Test
    @DisplayName("상세는 호스트·게스트만 조회할 수 있고, 다른 회원은 APPEARANCE_REQUEST_NOT_FOUND")
    void detail() {
        AppearanceRequestResDto sent = send(HOST, GUEST);

        assertThat(appearanceRequestFacade.getDetail(HOST, sent.id()).title()).isEqualTo(sent.title());
        assertThat(appearanceRequestFacade.getDetail(GUEST, sent.id()).id()).isEqualTo(sent.id());
        assertErrorCode(() -> appearanceRequestFacade.getDetail(OTHER, sent.id()),
                AppearanceErrorCode.APPEARANCE_REQUEST_NOT_FOUND);
        assertErrorCode(() -> appearanceRequestFacade.getDetail(HOST, 999_999L),
                AppearanceErrorCode.APPEARANCE_REQUEST_NOT_FOUND);
    }

    // ---------------------------------------------------------------------------------------------
    // 수정
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("호스트는 기본 정보를 수정할 수 있고, 보낸 값만 바뀐다")
    void update() {
        AppearanceRequestResDto sent = send(HOST, GUEST);

        AppearanceRequestResDto updated = appearanceRequestFacade.updateBasicInfo(HOST, sent.id(), "새 제목", null,
                null);

        assertThat(updated.title()).isEqualTo("새 제목");
        assertThat(updated.content()).isEqualTo(sent.content());
        assertThat(appearanceRequestRepository.findById(sent.id()).orElseThrow().getTitle()).isEqualTo("새 제목");
    }

    @Test
    @DisplayName("게스트가 수정하면 NOT_REQUEST_HOST, 다른 회원이면 APPEARANCE_REQUEST_NOT_FOUND")
    void updateByNonHost() {
        AppearanceRequestResDto sent = send(HOST, GUEST);

        assertErrorCode(() -> appearanceRequestFacade.updateBasicInfo(GUEST, sent.id(), "새 제목", null, null),
                AppearanceErrorCode.NOT_REQUEST_HOST);
        assertErrorCode(() -> appearanceRequestFacade.updateBasicInfo(OTHER, sent.id(), "새 제목", null, null),
                AppearanceErrorCode.APPEARANCE_REQUEST_NOT_FOUND);
    }
}
