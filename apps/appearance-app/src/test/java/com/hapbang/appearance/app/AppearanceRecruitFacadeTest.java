package com.hapbang.appearance.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

import com.hapbang.appearance.app.dto.AppearanceRecruitResDto;
import com.hapbang.appearance.app.dto.AppearanceRecruitSummaryResDto;
import com.hapbang.appearance.config.AppearanceConfig;
import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceHoldRejectedException;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.domain.AppearanceRecruit;
import com.hapbang.appearance.domain.AppearanceRecruitStatus;
import com.hapbang.appearance.fixture.AppearanceRecruitFixture;
import com.hapbang.appearance.fixture.AppearanceUserFixture;
import com.hapbang.appearance.out.AppearanceRecruitRepository;
import com.hapbang.appearance.out.billing.BillingClient;
import com.hapbang.appearance.support.AppearanceIntegrationTest;
import com.hapbang.appearance.support.BillingCalls;
import com.hapbang.appearance.support.BillingStubs;
import com.hapbang.appearance.support.BillingStubs.Rejection;
import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;

/**
 * 출연 모집글 올리기·조회·수정 흐름을 DB와 Billing 대역으로 확인한다.
 * 세션 역할은 {@code @WithMockUser}로 넣는다 (Facade의 {@code @PreAuthorize} 검사용).
 */
@AppearanceIntegrationTest
@WithMockUser(roles = "INFLUENCER")
class AppearanceRecruitFacadeTest {

    private static final Long HOST = 1L;
    private static final Long OTHER = 3L;
    private static final PageRequest FIRST_PAGE = PageRequest.of(0, 20);

    @Autowired
    AppearanceRecruitFacade appearanceRecruitFacade;

    @Autowired
    AppearanceUserFacade appearanceUserFacade;

    @Autowired
    AppearanceRecruitRepository appearanceRecruitRepository;

    @Autowired
    BillingClient billingClient;

    @Autowired
    @Qualifier(AppearanceConfig.CLOCK)
    Clock clock;

    @BeforeEach
    void setUp() {
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(HOST));
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(OTHER));
    }


    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private AppearanceRecruitResDto post(Long hostId) {
        return appearanceRecruitFacade.create(hostId, AppearanceRecruitFixture.content(now()));
    }

    private AppearanceRecruit findOnly() {
        List<AppearanceRecruit> all = appearanceRecruitRepository.findAll();
        assertThat(all).hasSize(1);
        return all.getFirst();
    }

    private static void assertErrorCode(Runnable action, AppearanceErrorCode errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(AppearanceException.class)
                .extracting("errorCode").isEqualTo(errorCode);
    }

    // ---------------------------------------------------------------------------------------------
    // 올리기
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("올리면 RECRUITING이고, 모집글 단위로 1인당 출연료 × 모집 인원(90,000)을 보류한다")
    void create() {
        AppearanceRecruitResDto response = post(HOST);

        assertThat(response.status()).isEqualTo(AppearanceRecruitStatus.RECRUITING);
        assertThat(response.selectedCount()).isZero();
        assertThat(response.version()).isEqualTo(findOnly().getVersion());
        HoldCommand hold = BillingCalls.holds(billingClient).getFirst();
        assertThat(hold.userId()).isEqualTo(HOST);
        assertThat(hold.referenceType()).isEqualTo(BillingReferenceType.APPEARANCE_RECRUIT);
        assertThat(hold.referenceId()).isEqualTo(response.id());
        assertThat(hold.amountHp()).isEqualTo(AppearanceRecruitFixture.TOTAL_FEE);
    }

    @Test
    @DisplayName("같은 호스트가 모집글을 여러 개 올릴 수 있다 (중복 제한 없음)")
    void createMany() {
        post(HOST);
        post(HOST);

        assertThat(appearanceRecruitRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("지갑이 보류를 거절하면(잔액 부족) 409 INSUFFICIENT_BALANCE(출연 문구), 모집글은 HOLD_FAILED로 남고 목록에 보이지 않는다")
    void insufficientBalance() {
        BillingStubs.reject(billingClient, Rejection.INSUFFICIENT_BALANCE);

        assertThatThrownBy(() -> post(HOST))
                .isInstanceOf(AppearanceHoldRejectedException.class)
                .extracting("errorCode").isEqualTo(AppearanceErrorCode.INSUFFICIENT_BALANCE);

        assertThat(findOnly().getStatus()).isEqualTo(AppearanceRecruitStatus.HOLD_FAILED);
        assertThat(appearanceRecruitFacade.getMine(HOST, null, FIRST_PAGE).getTotalElements()).isZero();
        assertThat(appearanceRecruitFacade.getOpen(FIRST_PAGE).getTotalElements()).isZero();
    }

    @Test
    @DisplayName("Billing 결과를 알 수 없으면 BILLING_UNAVAILABLE(내 모집글 목록 안내), 모집글은 HOLD_PENDING")
    void billingUnavailable() {
        BillingStubs.unavailable(billingClient);

        assertThatThrownBy(() -> post(HOST))
                .isInstanceOf(AppearanceException.class)
                .hasMessage("HP 보류 처리가 지연되고 있습니다. 잠시 후 내 모집글 목록에서 결과를 확인해 주세요.")
                .extracting("errorCode").isEqualTo(AppearanceErrorCode.BILLING_UNAVAILABLE);

        assertThat(findOnly().getStatus()).isEqualTo(AppearanceRecruitStatus.HOLD_PENDING);
    }

    @Test
    @DisplayName("값이 틀리면 저장하지 않고 보류도 요청하지 않는다 (모집 인원 11명)")
    void invalidContent() {
        assertErrorCode(() -> appearanceRecruitFacade.create(HOST,
                AppearanceRecruitFixture.content(now(), 30_000, 11)), AppearanceErrorCode.RECRUIT_COUNT_OUT_OF_RANGE);

        assertThat(appearanceRecruitRepository.count()).isZero();
        assertThat(BillingCalls.none(billingClient)).isTrue();
    }

    @Test
    @DisplayName("정지된 호스트는 올릴 수 없다 (APPEARANCE_USER_NOT_ACTIVE)")
    void suspendedHost() {
        appearanceUserFacade.syncModified(AppearanceUserFixture.user(HOST, Role.INFLUENCER, UserStatus.SUSPENDED));

        assertErrorCode(() -> post(HOST), AppearanceErrorCode.APPEARANCE_USER_NOT_ACTIVE);
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("세션 역할이 USER면 올리기·수정·내 목록은 막히고, 공개 목록·상세는 볼 수 있다")
    void userRole() {
        assertThatThrownBy(() -> post(HOST)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> appearanceRecruitFacade.getMine(HOST, null, FIRST_PAGE))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> appearanceRecruitFacade.updateBasicInfo(HOST, 1L, "새 제목", null, null))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(appearanceRecruitFacade.getOpen(FIRST_PAGE).getTotalElements()).isZero();
        assertErrorCode(() -> appearanceRecruitFacade.getDetail(HOST, 1L), AppearanceErrorCode.APPEARANCE_RECRUIT_NOT_FOUND);
    }

    // ---------------------------------------------------------------------------------------------
    // 조회
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("공개 목록은 모든 호스트의 모집 중 모집글을 최신순으로 보여 준다")
    void getOpen() {
        AppearanceRecruitResDto first = post(HOST);
        AppearanceRecruitResDto second = post(OTHER);

        Page<AppearanceRecruitSummaryResDto> open = appearanceRecruitFacade.getOpen(FIRST_PAGE);

        assertThat(open.getContent()).extracting(AppearanceRecruitSummaryResDto::id)
                .containsExactly(second.id(), first.id());
        assertThat(open.getContent().getFirst().feePerPerson()).isEqualTo(AppearanceRecruitFixture.FEE_PER_PERSON);
        assertThat(open.getContent().getFirst().recruitCount()).isEqualTo(AppearanceRecruitFixture.RECRUIT_COUNT);
    }

    @Test
    @DisplayName("내 목록은 내가 올린 모집글만, 상태 필터를 적용한다. 숨김 상태 필터는 INVALID_REQUEST")
    void getMine() {
        AppearanceRecruitResDto mine = post(HOST);
        post(OTHER);

        Page<AppearanceRecruitSummaryResDto> all = appearanceRecruitFacade.getMine(HOST, null, FIRST_PAGE);
        Page<AppearanceRecruitSummaryResDto> closed = appearanceRecruitFacade.getMine(HOST,
                List.of(AppearanceRecruitStatus.CLOSED), FIRST_PAGE);

        assertThat(all.getContent()).extracting(AppearanceRecruitSummaryResDto::id).containsExactly(mine.id());
        assertThat(closed.getTotalElements()).isZero();
        assertErrorCode(() -> appearanceRecruitFacade.getMine(HOST, List.of(AppearanceRecruitStatus.HOLD_FAILED),
                FIRST_PAGE), AppearanceErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("상세는 다른 회원도 볼 수 있고, 보류 처리 중인 모집글은 올린 호스트에게도 APPEARANCE_RECRUIT_NOT_FOUND")
    void getDetail() {
        AppearanceRecruitResDto posted = post(HOST);

        AppearanceRecruitResDto detail = appearanceRecruitFacade.getDetail(HOST, posted.id());

        assertThat(detail.content()).isEqualTo("같이 신작 게임을 플레이할 게스트를 모집합니다.");
        assertThat(detail.recruitDeadline()).isEqualTo(posted.recruitDeadline());

        BillingStubs.unavailable(billingClient);
        assertThatThrownBy(() -> post(HOST)).isInstanceOf(AppearanceException.class);
        Long pendingId = appearanceRecruitRepository.findAll().stream()
                .filter(recruit -> recruit.getStatus() == AppearanceRecruitStatus.HOLD_PENDING)
                .findFirst().orElseThrow().getId();
        assertErrorCode(() -> appearanceRecruitFacade.getDetail(HOST, pendingId),
                AppearanceErrorCode.APPEARANCE_RECRUIT_NOT_FOUND);
        assertErrorCode(() -> appearanceRecruitFacade.getDetail(HOST, 999_999L),
                AppearanceErrorCode.APPEARANCE_RECRUIT_NOT_FOUND);
    }

    // ---------------------------------------------------------------------------------------------
    // 수정
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("호스트가 보낸 값만 바꾸고 버전이 오른다. 같은 수정을 다시 하면 결과가 같다")
    void update() {
        AppearanceRecruitResDto posted = post(HOST);

        AppearanceRecruitResDto updated = appearanceRecruitFacade.updateBasicInfo(HOST, posted.id(), "새 제목",
                null, null);
        AppearanceRecruitResDto again = appearanceRecruitFacade.updateBasicInfo(HOST, posted.id(), "새 제목",
                null, null);

        assertThat(updated.title()).isEqualTo("새 제목");
        assertThat(updated.content()).isEqualTo(posted.content());
        assertThat(updated.version()).isGreaterThan(posted.version());
        assertThat(again.version()).isEqualTo(updated.version());
    }

    @Test
    @DisplayName("다른 회원이 수정하면 NOT_RECRUIT_HOST, 없는 모집글은 APPEARANCE_RECRUIT_NOT_FOUND")
    void updateByOther() {
        AppearanceRecruitResDto posted = post(HOST);

        assertErrorCode(() -> appearanceRecruitFacade.updateBasicInfo(OTHER, posted.id(), "새 제목", null, null),
                AppearanceErrorCode.NOT_RECRUIT_HOST);
        assertErrorCode(() -> appearanceRecruitFacade.updateBasicInfo(HOST, 999_999L, "새 제목", null, null),
                AppearanceErrorCode.APPEARANCE_RECRUIT_NOT_FOUND);
    }
}
