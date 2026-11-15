package com.hapbang.coupon.app;

import static com.hapbang.coupon.app.CouponAppFixture.*;
import static com.hapbang.coupon.app.CouponOpenUseCaseTest.assertCouponError;
import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import com.hapbang.coupon.app.dto.CouponSummaryView;
import com.hapbang.coupon.app.port.CouponIssueStore;
import com.hapbang.coupon.app.port.CouponIssueStore.IssueSnapshot;
import com.hapbang.coupon.app.port.CouponIssueStoreUnavailableException;
import com.hapbang.coupon.app.port.LectureCatalog;
import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.CouponStatus;
import com.hapbang.coupon.global.exception.CouponErrorCode;
import com.hapbang.coupon.out.CouponRepository;
import com.hapbang.coupon.out.MemberCouponRepository;

// DB·Redis 값을 매진·받음 표시로 옮기는지와 Redis 장애 대체, 상세의 상태 제한을 본다. 판정 규칙 자체는 CouponAvailabilityRuleTest가 본다
class CouponSummaryQueryTest {

    private static final long SECOND_COUPON_ID = 2L;

    private final CouponRepository couponRepository = mock();
    private final MemberCouponRepository memberCouponRepository = mock();
    private final CouponIssueStore issueStore = mock();
    private final CouponSummaryQuery query = new CouponSummaryQuery(couponRepository, memberCouponRepository,
            issueStore, new CouponSupport(couponRepository, mock(LectureCatalog.class), issueStore),
            Clock.fixed(ISSUE_START.plusSeconds(1), ZoneOffset.UTC));

    private static Coupon openCoupon(long id, int issuedCount) {
        Coupon coupon = Coupon.createPlatform(ADMIN_ID, anyLectureAmount().quantity(10).build(), NOW);
        ReflectionTestUtils.setField(coupon, "id", id);
        ReflectionTestUtils.setField(coupon, "status", CouponStatus.OPEN);
        ReflectionTestUtils.setField(coupon, "issuedCount", issuedCount);
        return coupon;
    }

    @Test
    void 목록은_Redis_재고와_접수_회원_DB_발급_쿠폰으로_매진과_받음을_표시한다() {
        when(couponRepository.findIssuableFor(eq(LECTURE_ID), any(), any()))
                .thenReturn(List.of(openCoupon(COUPON_ID, 3), openCoupon(SECOND_COUPON_ID, 0)));
        when(memberCouponRepository.findCouponIdsOwnedBy(MEMBER_ID, List.of(COUPON_ID, SECOND_COUPON_ID)))
                .thenReturn(List.of(COUPON_ID));
        when(issueStore.readSnapshots(List.of(COUPON_ID, SECOND_COUPON_ID), MEMBER_ID)).thenReturn(Map.of(
                COUPON_ID, new IssueSnapshot(7L, false),
                SECOND_COUPON_ID, new IssueSnapshot(0L, true)));

        List<CouponSummaryView> views = query.issuableFor(MEMBER_ID, LECTURE_ID);

        assertThat(views).extracting(CouponSummaryView::couponId, CouponSummaryView::soldOut, CouponSummaryView::issued)
                .containsExactly(tuple(COUPON_ID, false, true), tuple(SECOND_COUPON_ID, true, true));
        assertThat(views.getFirst().name()).isEqualTo(COUPON_NAME);
    }

    @Test
    void Redis에_닿지_못하면_DB_발급_수와_발급_쿠폰만으로_표시하고_실패시키지_않는다() {
        when(couponRepository.findIssuableFor(eq(LECTURE_ID), any(), any()))
                .thenReturn(List.of(openCoupon(COUPON_ID, 10), openCoupon(SECOND_COUPON_ID, 3)));
        when(memberCouponRepository.findCouponIdsOwnedBy(anyLong(), anyCollection())).thenReturn(List.of());
        when(issueStore.readSnapshots(anyCollection(), anyLong()))
                .thenThrow(new CouponIssueStoreUnavailableException("연결 실패", null));

        List<CouponSummaryView> views = query.issuableFor(MEMBER_ID, LECTURE_ID);

        assertThat(views).extracting(CouponSummaryView::soldOut, CouponSummaryView::issued)
                .containsExactly(tuple(true, false), tuple(false, false));
    }

    @Test
    void 받을_수_있는_쿠폰이_없으면_발급_쿠폰과_Redis를_읽지_않는다() {
        when(couponRepository.findIssuableFor(anyLong(), any(), any())).thenReturn(List.of());

        assertThat(query.issuableFor(MEMBER_ID, LECTURE_ID)).isEmpty();
        verifyNoInteractions(memberCouponRepository, issueStore);
    }

    @Test
    void 목록은_상한_100건으로_조회한다() {
        when(couponRepository.findIssuableFor(anyLong(), any(), any())).thenReturn(List.of());

        query.issuableFor(MEMBER_ID, LECTURE_ID);

        verify(couponRepository).findIssuableFor(LECTURE_ID, ISSUE_START.plusSeconds(1), CouponSupport.LIST_LIMIT);
        assertThat(CouponSupport.LIST_LIMIT.max()).isEqualTo(100);
    }

    @ParameterizedTest
    @EnumSource(value = CouponStatus.class, names = {"SUSPENDED", "CLOSED"})
    void 상세는_중단_종료된_쿠폰도_보여준다(CouponStatus status) {
        Coupon coupon = openCoupon(COUPON_ID, 0);
        ReflectionTestUtils.setField(coupon, "status", status);
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(coupon));
        when(memberCouponRepository.findCouponIdsOwnedBy(MEMBER_ID, List.of(COUPON_ID))).thenReturn(List.of());
        when(issueStore.readSnapshots(List.of(COUPON_ID), MEMBER_ID)).thenReturn(Map.of());

        CouponSummaryView view = query.summary(MEMBER_ID, COUPON_ID);

        assertThat(view.status()).isEqualTo(status);
        assertThat(view.soldOut()).isFalse();
        assertThat(view.issued()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = CouponStatus.class, names = {"DRAFT", "REVIEW", "REJECTED"})
    void 상세는_발급을_연_적이_없는_쿠폰을_없는_쿠폰처럼_404로_응답한다(CouponStatus status) {
        Coupon coupon = openCoupon(COUPON_ID, 0);
        ReflectionTestUtils.setField(coupon, "status", status);
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(coupon));

        assertCouponError(() -> query.summary(MEMBER_ID, COUPON_ID), CouponErrorCode.COUPON_NOT_FOUND);
        verifyNoInteractions(issueStore);
    }

    @Test
    void 없는_쿠폰의_상세는_404다() {
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.empty());

        assertCouponError(() -> query.summary(MEMBER_ID, COUPON_ID), CouponErrorCode.COUPON_NOT_FOUND);
    }
}
