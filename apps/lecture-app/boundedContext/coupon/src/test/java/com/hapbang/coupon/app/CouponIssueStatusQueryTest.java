package com.hapbang.coupon.app;

import static com.hapbang.coupon.app.CouponAppFixture.*;
import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.hapbang.coupon.app.port.CouponIssueStore;
import com.hapbang.coupon.app.port.CouponIssueStore.IssueReceipt;
import com.hapbang.coupon.app.port.CouponIssueStoreUnavailableException;
import com.hapbang.coupon.domain.CouponIssueRequest;
import com.hapbang.coupon.domain.IssueStatus;
import com.hapbang.coupon.out.CouponIssueRequestRepository;

// (쿠폰, 요청 ID)로 DB → Redis 순서로 읽는지와 Redis 장애 처리를 본다. 회원 대조 규칙은 IssueStatusRuleTest가 본다
class CouponIssueStatusQueryTest {

    private static final String REQUEST_ID = "req-1";
    private static final long OTHER_COUPON_ID = 2L;

    private final CouponIssueRequestRepository requestRepository = mock();
    private final CouponIssueStore issueStore = mock();
    private final CouponIssueStatusQuery query = new CouponIssueStatusQuery(requestRepository, issueStore);

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
}
