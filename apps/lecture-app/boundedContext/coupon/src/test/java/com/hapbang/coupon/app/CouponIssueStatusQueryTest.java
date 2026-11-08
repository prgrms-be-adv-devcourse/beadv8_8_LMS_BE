package com.hapbang.coupon.app;

import static com.hapbang.coupon.app.CouponAppFixture.*;
import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hapbang.coupon.app.port.CouponIssueStore;
import com.hapbang.coupon.app.port.CouponIssueStore.IssueReceipt;
import com.hapbang.coupon.app.port.CouponIssueStoreUnavailableException;
import com.hapbang.coupon.app.port.CouponRecoveryStore;
import com.hapbang.coupon.app.port.CouponRecoveryStore.IssueControlState;
import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.CouponIssueRequest;
import com.hapbang.coupon.domain.IssueStatus;
import com.hapbang.coupon.domain.RecoveryBoundary;
import com.hapbang.coupon.domain.RecoveryRun;
import com.hapbang.coupon.domain.RecoveryUnresolved;
import com.hapbang.coupon.out.CouponIssueRequestRepository;
import com.hapbang.coupon.out.CouponRepository;
import com.hapbang.coupon.out.RecoveryRunRepository;
import com.hapbang.coupon.out.redis.CouponRedisProperties;

// (쿠폰, 요청 ID)로 DB → Redis 순서로 읽는지와 Redis 장애·유실 처리를 본다. 회원 대조 규칙은 IssueStatusRuleTest가 본다
class CouponIssueStatusQueryTest {

    private static final String REQUEST_ID = "req-1";
    private static final long OTHER_COUPON_ID = 2L;
    private static final Duration KEYS_EXPIRE_MARGIN = Duration.ofDays(7);

    private final CouponIssueRequestRepository requestRepository = mock();
    private final CouponIssueStore issueStore = mock();
    private final CouponRepository couponRepository = mock();
    private final CouponRecoveryStore recoveryStore = mock();
    private final RecoveryRunRepository recoveryRunRepository = mock();
    private final CouponIssueStatusQuery query = new CouponIssueStatusQuery(requestRepository, issueStore,
            couponRepository, recoveryStore, recoveryRunRepository, new CouponRedisProperties(KEYS_EXPIRE_MARGIN), CLOCK);

    private static final IssueControlState READY = new IssueControlState(true, true, null);

    @BeforeEach
    void setUp() {
        when(recoveryStore.issueControlState(anyLong())).thenReturn(READY);
    }

    private IssueStatus statusOf(long memberId) {
        return query.issueStatus(COUPON_ID, memberId, REQUEST_ID).status();
    }

    @Test
    void DB에_결과가_있으면_Redis를_보지_않고_그_결과다() {
        when(requestRepository.findByCouponIdAndIssueRequestId(COUPON_ID, REQUEST_ID)).thenReturn(Optional.of(
                CouponIssueRequest.issued(REQUEST_ID, MEMBER_ID, COUPON_ID, 1L, NOW, 11L, NOW)));

        assertThat(statusOf(MEMBER_ID)).isEqualTo(IssueStatus.ISSUED);
        verifyNoInteractions(issueStore);
    }

    @Test
    void DB에_다른_회원의_결과면_NOT_FOUND다() {
        when(requestRepository.findByCouponIdAndIssueRequestId(COUPON_ID, REQUEST_ID)).thenReturn(Optional.of(
                CouponIssueRequest.rejected(REQUEST_ID, OTHER_MEMBER_ID, COUPON_ID, 1L, NOW, "SOLD_OUT", NOW)));

        assertThat(statusOf(MEMBER_ID)).isEqualTo(IssueStatus.NOT_FOUND);
    }

    @Test
    void DB에_없고_Redis에_내_접수_기록이_있으면_PENDING_다른_회원이면_NOT_FOUND다() {
        when(issueStore.findRequest(COUPON_ID, REQUEST_ID)).thenReturn(Optional.of(new IssueReceipt(MEMBER_ID, NOW)));

        assertThat(statusOf(MEMBER_ID)).isEqualTo(IssueStatus.PENDING);
        assertThat(statusOf(OTHER_MEMBER_ID)).isEqualTo(IssueStatus.NOT_FOUND);
    }

    @Test
    void 다른_쿠폰에_같은_요청_ID의_결과가_있어도_이_쿠폰은_Redis까지_보고_판정한다() {
        // 다른 쿠폰의 결과는 (쿠폰, 요청 ID) 조회에 걸리지 않는다
        when(requestRepository.findByCouponIdAndIssueRequestId(OTHER_COUPON_ID, REQUEST_ID)).thenReturn(Optional.of(
                CouponIssueRequest.issued(REQUEST_ID, MEMBER_ID, OTHER_COUPON_ID, 1L, NOW, 11L, NOW)));
        when(issueStore.findRequest(COUPON_ID, REQUEST_ID)).thenReturn(Optional.of(new IssueReceipt(MEMBER_ID, NOW)));

        assertThat(statusOf(MEMBER_ID)).isEqualTo(IssueStatus.PENDING);
        verify(requestRepository).findByCouponIdAndIssueRequestId(COUPON_ID, REQUEST_ID);
    }

    @Test
    void DB와_Redis_모두_없으면_NOT_FOUND다() {
        when(issueStore.findRequest(COUPON_ID, REQUEST_ID)).thenReturn(Optional.empty());

        assertThat(statusOf(MEMBER_ID)).isEqualTo(IssueStatus.NOT_FOUND);
    }

    @Test
    void DB에_없고_Redis에_닿지_못하면_UNKNOWN이다() {
        when(issueStore.findRequest(anyLong(), anyString()))
                .thenThrow(new CouponIssueStoreUnavailableException("연결 실패", null));

        assertThat(statusOf(MEMBER_ID)).isEqualTo(IssueStatus.UNKNOWN);
    }

    @Test
    void 열린_쿠폰의_issue_control이_Redis에_없으면_유실이라_UNKNOWN이다() {
        Coupon open = openSellerCoupon();
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(open));
        when(issueStore.findRequest(COUPON_ID, REQUEST_ID)).thenReturn(Optional.empty());
        when(recoveryStore.issueControlState(COUPON_ID)).thenReturn(IssueControlState.missing());

        assertThat(statusOf(MEMBER_ID)).isEqualTo(IssueStatus.UNKNOWN);

        when(recoveryStore.issueControlState(COUPON_ID)).thenReturn(READY);
        assertThat(statusOf(MEMBER_ID)).isEqualTo(IssueStatus.NOT_FOUND);
    }

    @Test
    void 복구_실행이_없어도_issue_control에_복구_차단이_있으면_UNKNOWN이고_다른_사유의_보류면_NOT_FOUND다() {
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(openSellerCoupon()));
        when(issueStore.findRequest(COUPON_ID, REQUEST_ID)).thenReturn(Optional.empty());
        when(recoveryStore.issueControlState(COUPON_ID)).thenReturn(new IssueControlState(true, false, "RECOVERY"));

        assertThat(statusOf(MEMBER_ID)).isEqualTo(IssueStatus.UNKNOWN);

        when(recoveryStore.issueControlState(COUPON_ID)).thenReturn(new IssueControlState(true, true, "BACKLOG"));
        assertThat(statusOf(MEMBER_ID)).isEqualTo(IssueStatus.NOT_FOUND);
    }

    @Test
    void 중단한_쿠폰도_issue_control이_없거나_ready가_0이면_UNKNOWN이다() {
        Coupon suspended = openSellerCoupon();
        suspended.suspend(NOW);
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(suspended));
        when(issueStore.findRequest(COUPON_ID, REQUEST_ID)).thenReturn(Optional.empty());

        when(recoveryStore.issueControlState(COUPON_ID)).thenReturn(IssueControlState.missing());
        assertThat(statusOf(MEMBER_ID)).isEqualTo(IssueStatus.UNKNOWN);

        when(recoveryStore.issueControlState(COUPON_ID)).thenReturn(new IssueControlState(true, false, null));
        assertThat(statusOf(MEMBER_ID)).isEqualTo(IssueStatus.UNKNOWN);

        when(recoveryStore.issueControlState(COUPON_ID)).thenReturn(READY);
        assertThat(statusOf(MEMBER_ID)).isEqualTo(IssueStatus.NOT_FOUND);
    }

    @Test
    void 열리기_전_쿠폰은_issue_control이_없어도_NOT_FOUND다() {
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(reviewSellerCoupon()));
        when(issueStore.findRequest(COUPON_ID, REQUEST_ID)).thenReturn(Optional.empty());
        when(recoveryStore.issueControlState(COUPON_ID)).thenReturn(IssueControlState.missing());

        assertThat(statusOf(MEMBER_ID)).isEqualTo(IssueStatus.NOT_FOUND);
    }

    @Test
    void 접수_기록_유실이_의심되는_복구가_진행_중이면_Redis에_없어도_UNKNOWN이다() {
        when(issueStore.findRequest(COUPON_ID, REQUEST_ID)).thenReturn(Optional.empty());
        givenLatestLostRecovery(RecoveryRun.start(COUPON_ID, 1L, ADMIN_ID, true, NOW));

        assertThat(statusOf(MEMBER_ID)).isEqualTo(IssueStatus.UNKNOWN);
    }

    @Test
    void 유실_의심인_채_취소된_복구만_있으면_Redis와_DB에_없는_요청은_UNKNOWN이다() {
        when(issueStore.findRequest(COUPON_ID, REQUEST_ID)).thenReturn(Optional.empty());
        RecoveryRun aborted = RecoveryRun.start(COUPON_ID, 1L, ADMIN_ID, true, NOW.minus(Duration.ofDays(3)));
        aborted.abort(ADMIN_ID, NOW.minus(Duration.ofDays(3)));
        givenLatestLostRecovery(aborted);

        assertThat(statusOf(MEMBER_ID)).isEqualTo(IssueStatus.UNKNOWN);
    }

    @Test
    void 유실_의심_복구를_재개한_뒤_issue_control의_key_만료_시각_전이면_UNKNOWN_지나면_NOT_FOUND다() {
        when(issueStore.findRequest(COUPON_ID, REQUEST_ID)).thenReturn(Optional.empty());
        // CLOCK은 NOW에 멈춰 있다. 보관 기간 끝은 issue-control의 keysExpireAt이다
        givenLatestLostRecovery(resumedLostRecovery(NOW.minus(Duration.ofDays(30))));
        when(recoveryStore.issueControlState(COUPON_ID))
                .thenReturn(new IssueControlState(true, true, null, NOW.plusMillis(1)));
        assertThat(statusOf(MEMBER_ID)).isEqualTo(IssueStatus.UNKNOWN);

        when(recoveryStore.issueControlState(COUPON_ID)).thenReturn(new IssueControlState(true, true, null, NOW));
        assertThat(statusOf(MEMBER_ID)).isEqualTo(IssueStatus.NOT_FOUND);
    }

    @Test
    void 유실_의심_복구를_재개한_뒤_issue_control이_없으면_DB_쿠폰의_발급_종료와_여유로_보관_기간을_계산한다() {
        when(issueStore.findRequest(COUPON_ID, REQUEST_ID)).thenReturn(Optional.empty());
        when(recoveryStore.issueControlState(COUPON_ID)).thenReturn(IssueControlState.missing());
        Coupon closed = openSellerCoupon();
        closed.close(NOW);
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(closed));
        givenLatestLostRecovery(resumedLostRecovery(NOW.minus(Duration.ofDays(30))));
        Instant retainedUntil = closed.getIssueEndAt().plus(KEYS_EXPIRE_MARGIN);

        assertThat(queryAt(retainedUntil.minusMillis(1)).issueStatus(COUPON_ID, MEMBER_ID, REQUEST_ID).status())
                .isEqualTo(IssueStatus.UNKNOWN);
        assertThat(queryAt(retainedUntil).issueStatus(COUPON_ID, MEMBER_ID, REQUEST_ID).status())
                .isEqualTo(IssueStatus.NOT_FOUND);
    }

    @Test
    void 유실_의심_복구가_없는_닫힌_쿠폰은_issue_control이_없어도_NOT_FOUND다() {
        when(issueStore.findRequest(COUPON_ID, REQUEST_ID)).thenReturn(Optional.empty());
        when(recoveryStore.issueControlState(COUPON_ID)).thenReturn(IssueControlState.missing());
        Coupon closed = openSellerCoupon();
        closed.close(NOW);
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(closed));

        assertThat(statusOf(MEMBER_ID)).isEqualTo(IssueStatus.NOT_FOUND);
    }

    private CouponIssueStatusQuery queryAt(Instant now) {
        return new CouponIssueStatusQuery(requestRepository, issueStore, couponRepository, recoveryStore,
                recoveryRunRepository, new CouponRedisProperties(KEYS_EXPIRE_MARGIN), Clock.fixed(now, ZoneOffset.UTC));
    }

    private void givenLatestLostRecovery(RecoveryRun run) {
        when(recoveryRunRepository.findFirstByCouponIdAndRequestsLostTrueOrderByIdDesc(COUPON_ID))
                .thenReturn(Optional.of(run));
    }

    private static RecoveryRun resumedLostRecovery(Instant resumedAt) {
        RecoveryRun run = RecoveryRun.start(COUPON_ID, 1L, ADMIN_ID, true, resumedAt);
        run.recordBoundary(new RecoveryBoundary(0, true, "", ""), resumedAt);
        run.recordResults(RecoveryUnresolved.none(), resumedAt);
        run.markRebuilt(run.claimRebuild(resumedAt, Duration.ofMinutes(2)), resumedAt);
        run.markResumed(run.claimResume("확인함", resumedAt, Duration.ofMinutes(2)), "확인함", "evidence", resumedAt);
        return run;
    }
}
