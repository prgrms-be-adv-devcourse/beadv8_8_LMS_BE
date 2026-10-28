package com.hapbang.appearance.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;

import com.hapbang.appearance.app.dto.AppearanceApplicationResDto;
import com.hapbang.appearance.app.dto.AppearanceRecruitResDto;
import com.hapbang.appearance.domain.Appearance;
import com.hapbang.appearance.domain.AppearanceApplicationStatus;
import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.domain.AppearanceSourceType;
import com.hapbang.appearance.fixture.AppearanceRecruitFixture;
import com.hapbang.appearance.fixture.AppearanceUserFixture;
import com.hapbang.appearance.out.AppearanceRecruitRepository;
import com.hapbang.appearance.out.AppearanceRepository;
import com.hapbang.appearance.out.billing.BillingClient;
import com.hapbang.appearance.support.AppearanceIntegrationTest;
import com.hapbang.appearance.support.AppearanceTestClock;
import com.hapbang.appearance.support.BillingCalls;
import com.hapbang.appearance.support.BillingStubs;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;

/**
 * 출연 모집 지원·취소·수락·거절·조회 흐름을 DB와 Billing 대역으로 확인한다.
 * 세션 역할은 {@code @WithMockUser}로 넣는다 (Facade의 {@code @PreAuthorize} 검사용).
 */
@AppearanceIntegrationTest
@WithMockUser(roles = "INFLUENCER")
class AppearanceApplicationFacadeTest {

    private static final Long HOST = 1L;
    private static final Long GUEST = 2L;
    private static final Long GUEST2 = 3L;
    private static final Long GUEST3 = 4L;
    private static final PageRequest FIRST_PAGE = PageRequest.of(0, 20);

    @Autowired
    AppearanceApplicationFacade appearanceApplicationFacade;

    @Autowired
    AppearanceRecruitFacade appearanceRecruitFacade;

    @Autowired
    AppearanceUserFacade appearanceUserFacade;

    @Autowired
    AppearanceRecruitRepository appearanceRecruitRepository;

    @Autowired
    AppearanceRepository appearanceRepository;

    @Autowired
    BillingClient billingClient;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    AppearanceTestClock clock;

    @BeforeEach
    void setUp() {
        for (Long id : List.of(HOST, GUEST, GUEST2, GUEST3)) {
            appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(id));
        }
    }


    /** 호스트가 정원 {@code recruitCount}명, 1인당 30,000 HP로 모집글을 올린다 */
    private Long postRecruit(int recruitCount) {
        return appearanceRecruitFacade.create(HOST,
                AppearanceRecruitFixture.content(LocalDateTime.now(clock), 30_000, recruitCount)).id();
    }

    private AppearanceApplicationResDto apply(Long guestId, Long recruitId) {
        return appearanceApplicationFacade.apply(guestId, recruitId, "지원합니다.");
    }

    /** 모집 마감 일시가 지난다 (테스트 시계를 앞으로, 기한 마감 배치는 아직) */
    private void passDeadline(Long recruitId) {
        clock.advanceTo(jdbcTemplate.queryForObject("select recruit_deadline from appearances.recruits where id = ?",
                LocalDateTime.class, recruitId));
    }

    private static void assertErrorCode(Runnable action, AppearanceErrorCode errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(AppearanceException.class)
                .extracting("errorCode").isEqualTo(errorCode);
    }

    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("지원")
    class Apply {

        @Test
        @DisplayName("모집 중인 모집글에 지원하면 APPLIED, 메시지 저장, 모집글의 지원자 수 1")
        void apply() {
            Long recruitId = postRecruit(3);

            AppearanceApplicationResDto applied = AppearanceApplicationFacadeTest.this.apply(GUEST, recruitId);

            assertThat(applied.status()).isEqualTo(AppearanceApplicationStatus.APPLIED);
            assertThat(applied.message()).isEqualTo("지원합니다.");
            assertThat(applied.appearanceId()).isNull();
            assertThat(appearanceRecruitFacade.getDetail(HOST, recruitId).applicantCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("같은 모집글에 다시 지원하면 DUPLICATE_APPLICATION (취소한 뒤에도)")
        void duplicate() {
            Long recruitId = postRecruit(3);
            AppearanceApplicationResDto applied = AppearanceApplicationFacadeTest.this.apply(GUEST, recruitId);

            assertErrorCode(() -> AppearanceApplicationFacadeTest.this.apply(GUEST, recruitId),
                    AppearanceErrorCode.DUPLICATE_APPLICATION);
            appearanceApplicationFacade.withdraw(GUEST, applied.id());
            assertErrorCode(() -> AppearanceApplicationFacadeTest.this.apply(GUEST, recruitId),
                    AppearanceErrorCode.DUPLICATE_APPLICATION);
        }

        @Test
        @DisplayName("같은 게스트가 동시에 5번 지원해도 1건만 저장되고 나머지는 DUPLICATE_APPLICATION")
        void concurrentDuplicate() throws Exception {
            Long recruitId = postRecruit(3);

            List<Object> results = runConcurrently(
                    Collections.nCopies(5, () -> AppearanceApplicationFacadeTest.this.apply(GUEST, recruitId)));

            assertThat(results).filteredOn(AppearanceApplicationResDto.class::isInstance).hasSize(1);
            assertThat(results).filteredOn(AppearanceException.class::isInstance)
                    .allSatisfy(error -> assertThat(((AppearanceException) error).getErrorCode())
                            .isEqualTo(AppearanceErrorCode.DUPLICATE_APPLICATION))
                    .hasSize(4);
            assertThat(appearanceApplicationFacade.getApplicants(HOST, recruitId, null, FIRST_PAGE).getTotalElements())
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("자기 모집글 CANNOT_APPLY_OWN_RECRUIT, 마감 일시 지남 APPEARANCE_RECRUIT_NOT_OPEN, 없는 모집글 NOT_FOUND")
        void notApplicable() {
            Long recruitId = postRecruit(3);

            assertErrorCode(() -> AppearanceApplicationFacadeTest.this.apply(HOST, recruitId),
                    AppearanceErrorCode.CANNOT_APPLY_OWN_RECRUIT);
            assertErrorCode(() -> AppearanceApplicationFacadeTest.this.apply(GUEST, 999_999L),
                    AppearanceErrorCode.APPEARANCE_RECRUIT_NOT_FOUND);
            passDeadline(recruitId);
            assertErrorCode(() -> AppearanceApplicationFacadeTest.this.apply(GUEST, recruitId),
                    AppearanceErrorCode.APPEARANCE_RECRUIT_NOT_OPEN);
        }

        @Test
        @DisplayName("보류 처리 중인 모집글에는 지원할 수 없다 (목록에 없는 모집글이라 APPEARANCE_RECRUIT_NOT_FOUND)")
        void holdPendingRecruit() {
            BillingStubs.unavailable(billingClient);
            assertThatThrownBy(() -> postRecruit(3)).isInstanceOf(AppearanceException.class);
            BillingStubs.available(billingClient);
            Long pendingId = appearanceRecruitRepository.findAll().getFirst().getId();

            assertErrorCode(() -> AppearanceApplicationFacadeTest.this.apply(GUEST, pendingId),
                    AppearanceErrorCode.APPEARANCE_RECRUIT_NOT_FOUND);
        }

        @Test
        @DisplayName("정원이 찼어도 지원은 받는다")
        void fullStillApplicable() {
            Long recruitId = postRecruit(1);
            appearanceApplicationFacade.accept(HOST, AppearanceApplicationFacadeTest.this.apply(GUEST, recruitId).id());

            AppearanceApplicationResDto applied = AppearanceApplicationFacadeTest.this.apply(GUEST2, recruitId);

            assertThat(applied.status()).isEqualTo(AppearanceApplicationStatus.APPLIED);
        }

        @Test
        @DisplayName("정지된 게스트는 지원할 수 없다 (APPEARANCE_USER_NOT_ACTIVE)")
        void suspendedGuest() {
            Long recruitId = postRecruit(3);
            appearanceUserFacade.syncModified(AppearanceUserFixture.user(GUEST, Role.INFLUENCER, UserStatus.SUSPENDED));

            assertErrorCode(() -> AppearanceApplicationFacadeTest.this.apply(GUEST, recruitId),
                    AppearanceErrorCode.APPEARANCE_USER_NOT_ACTIVE);
        }
    }

    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("지원 취소")
    class Withdraw {

        @Test
        @DisplayName("지원한 게스트가 대기 중에 취소하면 WITHDRAWN, 지원자 수에서 빠진다")
        void withdraw() {
            Long recruitId = postRecruit(3);
            AppearanceApplicationResDto applied = apply(GUEST, recruitId);

            AppearanceApplicationResDto withdrawn = appearanceApplicationFacade.withdraw(GUEST, applied.id());

            assertThat(withdrawn.status()).isEqualTo(AppearanceApplicationStatus.WITHDRAWN);
            assertThat(withdrawn.withdrawnAt()).isNotNull();
            assertThat(appearanceRecruitFacade.getDetail(HOST, recruitId).applicantCount()).isZero();
        }

        @Test
        @DisplayName("다른 회원 NOT_APPLICATION_GUEST, 수락된 지원 APPLICATION_NOT_PENDING, 없는 지원 APPLICATION_NOT_FOUND")
        void notWithdrawable() {
            Long recruitId = postRecruit(3);
            AppearanceApplicationResDto applied = apply(GUEST, recruitId);

            assertErrorCode(() -> appearanceApplicationFacade.withdraw(GUEST2, applied.id()),
                    AppearanceErrorCode.NOT_APPLICATION_GUEST);
            appearanceApplicationFacade.accept(HOST, applied.id());
            assertErrorCode(() -> appearanceApplicationFacade.withdraw(GUEST, applied.id()),
                    AppearanceErrorCode.APPLICATION_NOT_PENDING);
            assertErrorCode(() -> appearanceApplicationFacade.withdraw(GUEST, 999_999L),
                    AppearanceErrorCode.APPLICATION_NOT_FOUND);
        }

        @Test
        @DisplayName("게스트 취소와 호스트 수락이 동시에 오면 한쪽만 성공한다. 취소됐으면 출연이 없고, 수락됐으면 출연이 성립한다")
        void concurrentWithdrawAndAccept() throws Exception {
            Long recruitId = postRecruit(3);
            Long applicationId = apply(GUEST, recruitId).id();

            List<Object> results = runConcurrently(List.of(
                    () -> appearanceApplicationFacade.withdraw(GUEST, applicationId),
                    () -> appearanceApplicationFacade.accept(HOST, applicationId)));

            assertThat(results).filteredOn(AppearanceApplicationResDto.class::isInstance).hasSize(1);
            assertThat(results).filteredOn(AppearanceException.class::isInstance)
                    .singleElement()
                    .satisfies(error -> assertThat(((AppearanceException) error).getErrorCode())
                            .isIn(AppearanceErrorCode.CONCURRENT_UPDATE, AppearanceErrorCode.APPLICATION_NOT_PENDING));
            AppearanceApplicationStatus status = appearanceApplicationFacade.getMine(GUEST, null, FIRST_PAGE)
                    .getContent().getFirst().status();
            if (status == AppearanceApplicationStatus.WITHDRAWN) {
                assertThat(appearanceRecruitFacade.getDetail(HOST, recruitId).selectedCount()).isZero();
                assertThat(appearanceRepository.count()).isZero();
            } else {
                assertThat(status).isEqualTo(AppearanceApplicationStatus.SELECTED);
                assertThat(appearanceRecruitFacade.getDetail(HOST, recruitId).selectedCount()).isEqualTo(1);
                assertThat(appearanceRepository.count()).isEqualTo(1);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("수락")
    class Accept {

        @Test
        @DisplayName("수락하면 SELECTED, 성립 인원 +1, 출연 성립(출처 RECRUIT·1인당 출연료), HP는 이동하지 않는다")
        void accept() {
            Long recruitId = postRecruit(3);
            AppearanceApplicationResDto applied = apply(GUEST, recruitId);
            BillingStubs.available(billingClient); // 모집글 보류 요청 기록을 지운다

            AppearanceApplicationResDto accepted = appearanceApplicationFacade.accept(HOST, applied.id());

            assertThat(accepted.status()).isEqualTo(AppearanceApplicationStatus.SELECTED);
            assertThat(accepted.respondedAt()).isNotNull();
            Appearance appearance = appearanceRepository.findById(accepted.appearanceId()).orElseThrow();
            assertThat(appearance.getSourceType()).isEqualTo(AppearanceSourceType.RECRUIT);
            assertThat(appearance.getApplicationId()).isEqualTo(applied.id());
            assertThat(appearance.getRecruitId()).isEqualTo(recruitId);
            assertThat(appearance.getGuestId()).isEqualTo(GUEST);
            assertThat(appearance.getFee()).isEqualTo(30_000);
            AppearanceRecruitResDto recruit = appearanceRecruitFacade.getDetail(HOST, recruitId);
            assertThat(recruit.selectedCount()).isEqualTo(1);
            assertThat(recruit.applicantCount()).isEqualTo(1);
            assertThat(BillingCalls.none(billingClient)).isTrue();
        }

        @Test
        @DisplayName("정원이 찼으면 RECRUIT_FULL, 모집은 RECRUITING 그대로이고 출연은 늘지 않는다")
        void full() {
            Long recruitId = postRecruit(1);
            appearanceApplicationFacade.accept(HOST, apply(GUEST, recruitId).id());
            AppearanceApplicationResDto second = apply(GUEST2, recruitId);

            assertErrorCode(() -> appearanceApplicationFacade.accept(HOST, second.id()), AppearanceErrorCode.RECRUIT_FULL);

            assertThat(appearanceRecruitFacade.getDetail(HOST, recruitId).status().name()).isEqualTo("RECRUITING");
            assertThat(appearanceRepository.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("다른 회원 NOT_RECRUIT_HOST, 마감 일시 지남 APPEARANCE_RECRUIT_NOT_OPEN, 이미 응답한 지원 APPLICATION_NOT_PENDING")
        void notAcceptable() {
            Long recruitId = postRecruit(3);
            AppearanceApplicationResDto applied = apply(GUEST, recruitId);

            assertErrorCode(() -> appearanceApplicationFacade.accept(GUEST2, applied.id()),
                    AppearanceErrorCode.NOT_RECRUIT_HOST);
            appearanceApplicationFacade.reject(HOST, applied.id());
            assertErrorCode(() -> appearanceApplicationFacade.accept(HOST, applied.id()),
                    AppearanceErrorCode.APPLICATION_NOT_PENDING);

            AppearanceApplicationResDto other = apply(GUEST2, recruitId);
            passDeadline(recruitId);
            assertErrorCode(() -> appearanceApplicationFacade.accept(HOST, other.id()),
                    AppearanceErrorCode.APPEARANCE_RECRUIT_NOT_OPEN);
            assertThat(appearanceRepository.count()).isZero();
        }

        @Test
        @DisplayName("지원 뒤 정지된 게스트는 수락할 수 없고, 아무것도 바뀌지 않는다 (APPEARANCE_USER_NOT_ACTIVE)")
        void suspendedGuest() {
            Long recruitId = postRecruit(3);
            AppearanceApplicationResDto applied = apply(GUEST, recruitId);
            appearanceUserFacade.syncModified(AppearanceUserFixture.user(GUEST, Role.INFLUENCER, UserStatus.SUSPENDED));

            assertErrorCode(() -> appearanceApplicationFacade.accept(HOST, applied.id()),
                    AppearanceErrorCode.APPEARANCE_USER_NOT_ACTIVE);

            assertThat(appearanceRecruitFacade.getDetail(HOST, recruitId).selectedCount()).isZero();
            assertThat(appearanceApplicationFacade.getApplicants(HOST, recruitId, null, FIRST_PAGE).getContent()
                    .getFirst().status()).isEqualTo(AppearanceApplicationStatus.APPLIED);
            assertThat(appearanceRepository.count()).isZero();
        }

        @Test
        @DisplayName("마지막 한 자리에 지원 3건을 동시에 수락하면 1건만 성립, 나머지는 CONCURRENT_UPDATE 또는 RECRUIT_FULL")
        void concurrentAcceptForLastSeat() throws Exception {
            Long recruitId = postRecruit(1);
            List<Long> applicationIds = List.of(apply(GUEST, recruitId).id(), apply(GUEST2, recruitId).id(),
                    apply(GUEST3, recruitId).id());

            List<Callable<Object>> accepts = new ArrayList<>();
            for (Long applicationId : applicationIds) {
                accepts.add(() -> appearanceApplicationFacade.accept(HOST, applicationId));
            }
            List<Object> results = runConcurrently(accepts);

            assertThat(results).filteredOn(AppearanceApplicationResDto.class::isInstance).hasSize(1);
            assertThat(results).filteredOn(AppearanceException.class::isInstance)
                    .allSatisfy(error -> assertThat(((AppearanceException) error).getErrorCode())
                            .isIn(AppearanceErrorCode.CONCURRENT_UPDATE, AppearanceErrorCode.RECRUIT_FULL))
                    .hasSize(2);
            assertThat(appearanceRecruitFacade.getDetail(HOST, recruitId).selectedCount()).isEqualTo(1);
            assertThat(appearanceRepository.count()).isEqualTo(1);
        }
    }

    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("거절")
    class Reject {

        @Test
        @DisplayName("거절하면 NOT_SELECTED(사유 없음), 지원자 수에는 남고, 그 게스트는 다시 지원할 수 없다")
        void reject() {
            Long recruitId = postRecruit(3);
            AppearanceApplicationResDto applied = apply(GUEST, recruitId);

            AppearanceApplicationResDto rejected = appearanceApplicationFacade.reject(HOST, applied.id());

            assertThat(rejected.status()).isEqualTo(AppearanceApplicationStatus.NOT_SELECTED);
            assertThat(rejected.respondedAt()).isNotNull();
            assertThat(appearanceRecruitFacade.getDetail(HOST, recruitId).applicantCount()).isEqualTo(1);
            assertErrorCode(() -> apply(GUEST, recruitId), AppearanceErrorCode.DUPLICATE_APPLICATION);
        }

        @Test
        @DisplayName("다른 회원이 거절하면 NOT_RECRUIT_HOST")
        void notHost() {
            Long recruitId = postRecruit(3);
            AppearanceApplicationResDto applied = apply(GUEST, recruitId);

            assertErrorCode(() -> appearanceApplicationFacade.reject(GUEST2, applied.id()),
                    AppearanceErrorCode.NOT_RECRUIT_HOST);
        }
    }

    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("조회")
    class Query {

        @Test
        @DisplayName("지원자 목록은 호스트만, 최신순, 상태 필터, 수락된 지원에는 출연 ID")
        void applicants() {
            Long recruitId = postRecruit(3);
            AppearanceApplicationResDto first = apply(GUEST, recruitId);
            AppearanceApplicationResDto second = apply(GUEST2, recruitId);
            AppearanceApplicationResDto accepted = appearanceApplicationFacade.accept(HOST, first.id());

            List<AppearanceApplicationResDto> all = appearanceApplicationFacade
                    .getApplicants(HOST, recruitId, null, FIRST_PAGE).getContent();
            List<AppearanceApplicationResDto> applied = appearanceApplicationFacade
                    .getApplicants(HOST, recruitId, List.of(AppearanceApplicationStatus.APPLIED), FIRST_PAGE)
                    .getContent();

            assertThat(all).extracting(AppearanceApplicationResDto::id).containsExactly(second.id(), first.id());
            assertThat(all.get(1).appearanceId()).isEqualTo(accepted.appearanceId());
            assertThat(all.get(0).appearanceId()).isNull();
            assertThat(applied).extracting(AppearanceApplicationResDto::id).containsExactly(second.id());
            assertThatThrownBy(() -> appearanceApplicationFacade.getApplicants(GUEST, recruitId, null, FIRST_PAGE))
                    .isInstanceOf(AppearanceException.class)
                    .hasMessage("출연 모집글을 올린 호스트만 지원자 목록을 볼 수 있습니다.");
        }

        @Test
        @DisplayName("내 지원 목록은 내 지원만, 상태 필터")
        void mine() {
            Long recruitA = postRecruit(3);
            Long recruitB = postRecruit(3);
            AppearanceApplicationResDto a = apply(GUEST, recruitA);
            AppearanceApplicationResDto b = apply(GUEST, recruitB);
            apply(GUEST2, recruitA);
            appearanceApplicationFacade.withdraw(GUEST, a.id());

            assertThat(appearanceApplicationFacade.getMine(GUEST, null, FIRST_PAGE).getContent())
                    .extracting(AppearanceApplicationResDto::id).containsExactly(b.id(), a.id());
            assertThat(appearanceApplicationFacade.getMine(GUEST, List.of(AppearanceApplicationStatus.WITHDRAWN),
                    FIRST_PAGE).getContent()).extracting(AppearanceApplicationResDto::id).containsExactly(a.id());
        }

        @Test
        @DisplayName("내 지원 목록 각 행에 모집글의 현재 제목·방송 예정 일시·상태 (제목 수정·모집 취소가 그대로 보인다) (#243)")
        void mineWithRecruit() {
            Long recruitA = postRecruit(3);
            Long recruitB = postRecruit(3);
            apply(GUEST, recruitA);
            apply(GUEST, recruitB);
            appearanceRecruitFacade.updateBasicInfo(HOST, recruitA, "제목 바꿈", null, null);
            appearanceRecruitFacade.cancel(HOST, recruitB, false, null);
            AppearanceRecruitResDto a = appearanceRecruitFacade.getDetail(HOST, recruitA);

            List<AppearanceApplicationResDto> mine = appearanceApplicationFacade.getMine(GUEST, null, FIRST_PAGE)
                    .getContent();

            assertThat(mine).extracting(row -> row.recruitId() + ":" + row.recruitTitle() + ":" + row.recruitStatus())
                    .containsExactly(recruitB + ":주말 게임 합방 게스트 모집:CANCELED",
                            recruitA + ":제목 바꿈:RECRUITING");
            assertThat(mine.get(1).recruitScheduledAt()).isEqualTo(a.scheduledAt());
        }

        @Test
        @DisplayName("지원자 목록·지원·수락 응답에도 모집글 제목이 담긴다 (#243)")
        void recruitTitleInOtherResponses() {
            Long recruitId = postRecruit(3);
            AppearanceApplicationResDto applied = apply(GUEST, recruitId);
            AppearanceApplicationResDto accepted = appearanceApplicationFacade.accept(HOST, applied.id());

            assertThat(applied.recruitTitle()).isEqualTo("주말 게임 합방 게스트 모집");
            assertThat(accepted.recruitTitle()).isEqualTo("주말 게임 합방 게스트 모집");
            assertThat(appearanceApplicationFacade.getApplicants(HOST, recruitId, null, FIRST_PAGE).getContent())
                    .extracting(AppearanceApplicationResDto::recruitTitle).containsExactly("주말 게임 합방 게스트 모집");
        }

        @Test
        @DisplayName("모집글 상세의 내 지원: 지원한 게스트는 상태 그대로(대기·수락+출연 ID·취소), 지원 안 한 회원·호스트는 null (#243)")
        void myApplicationInDetail() {
            Long recruitId = postRecruit(3);
            AppearanceApplicationResDto applied = apply(GUEST, recruitId);
            AppearanceApplicationResDto toWithdraw = apply(GUEST2, recruitId);

            AppearanceApplicationResDto pending = appearanceRecruitFacade.getDetail(GUEST, recruitId).myApplication();
            assertThat(pending.id()).isEqualTo(applied.id());
            assertThat(pending.status()).isEqualTo(AppearanceApplicationStatus.APPLIED);
            assertThat(pending.message()).isEqualTo("지원합니다.");
            assertThat(pending.appearanceId()).isNull();

            AppearanceApplicationResDto accepted = appearanceApplicationFacade.accept(HOST, applied.id());
            appearanceApplicationFacade.withdraw(GUEST2, toWithdraw.id());
            AppearanceApplicationResDto selected = appearanceRecruitFacade.getDetail(GUEST, recruitId).myApplication();
            assertThat(selected.status()).isEqualTo(AppearanceApplicationStatus.SELECTED);
            assertThat(selected.appearanceId()).isEqualTo(accepted.appearanceId());
            assertThat(appearanceRecruitFacade.getDetail(GUEST2, recruitId).myApplication().status())
                    .isEqualTo(AppearanceApplicationStatus.WITHDRAWN);

            assertThat(appearanceRecruitFacade.getDetail(GUEST3, recruitId).myApplication()).isNull();
            assertThat(appearanceRecruitFacade.getDetail(HOST, recruitId).myApplication()).isNull();
        }

        @Test
        @DisplayName("모집글 목록·상세의 지원자 수는 취소만 빼고 센다 (대기·수락·미선정)")
        void applicantCountInRecruit() {
            Long recruitId = postRecruit(3);
            Long emptyRecruitId = postRecruit(3);
            appearanceApplicationFacade.accept(HOST, apply(GUEST, recruitId).id());
            appearanceApplicationFacade.reject(HOST, apply(GUEST2, recruitId).id());
            appearanceApplicationFacade.withdraw(GUEST3, apply(GUEST3, recruitId).id());

            assertThat(appearanceRecruitFacade.getDetail(HOST, recruitId).applicantCount()).isEqualTo(2);
            assertThat(appearanceRecruitFacade.getOpen(FIRST_PAGE).getContent())
                    .extracting(summary -> summary.id() + ":" + summary.applicantCount())
                    .containsExactly(emptyRecruitId + ":0", recruitId + ":2");
            assertThat(appearanceRecruitFacade.getMine(HOST, null, FIRST_PAGE).getContent().get(1).applicantCount())
                    .isEqualTo(2);
        }
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("세션 역할이 USER면 지원·취소·수락·거절·목록 모두 막힌다 (@PreAuthorize)")
    void userRole() {
        assertAllDenied();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("세션 역할이 ADMIN이어도 지원·취소·수락·거절·목록 모두 막힌다 (인플루언서만)")
    void adminRole() {
        assertAllDenied();
    }

    private void assertAllDenied() {
        assertThatThrownBy(() -> appearanceApplicationFacade.apply(GUEST, 1L, null))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> appearanceApplicationFacade.withdraw(GUEST, 1L))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> appearanceApplicationFacade.accept(HOST, 1L))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> appearanceApplicationFacade.reject(HOST, 1L))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> appearanceApplicationFacade.getApplicants(HOST, 1L, null, FIRST_PAGE))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> appearanceApplicationFacade.getMine(GUEST, null, FIRST_PAGE))
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
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken("test", null,
                            List.of(new SimpleGrantedAuthority(
                                    "ROLE_INFLUENCER"))));
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
