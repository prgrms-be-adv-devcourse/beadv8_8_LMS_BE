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
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import com.hapbang.coupon.app.dto.CouponSummaryView;
import com.hapbang.coupon.app.dto.IssuableCouponView;
import com.hapbang.coupon.app.exception.CouponErrorCode;
import com.hapbang.coupon.app.port.CouponIssueStore.IssueSnapshot;
import com.hapbang.coupon.app.port.CouponIssueStore;
import com.hapbang.coupon.app.port.CouponIssueStoreUnavailableException;
import com.hapbang.coupon.app.port.CouponRepository;
import com.hapbang.coupon.app.port.LectureCatalog.LectureSummary;
import com.hapbang.coupon.app.port.LectureCatalog;
import com.hapbang.coupon.app.port.LectureCatalogUnavailableException;
import com.hapbang.coupon.app.port.MemberCouponRepository;
import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.CouponFixture;
import com.hapbang.coupon.domain.CouponStatus;

// DB·Redis 값을 매진·받음 표시로 옮기는지와 Redis 장애 대체, 상세의 상태 제한, 쿠폰 받기 목록의 판매 중 강의 고르기·강의 제목·강의 장애를 본다.
// 판정 규칙 자체는 CouponAvailabilityRuleTest·LectureSaleRuleTest가 본다
class CouponSummaryQueryTest {

    private static final long SECOND_COUPON_ID = 2L;

    private final CouponRepository couponRepository = mock();
    private final MemberCouponRepository memberCouponRepository = mock();
    private final CouponIssueStore issueStore = mock();
    private final LectureCatalog lectureCatalog = mock();
    private final CouponSummaryQuery query = new CouponSummaryQuery(couponRepository, memberCouponRepository,
            issueStore, new CouponSupport(couponRepository, lectureCatalog, issueStore), lectureCatalog,
            Clock.fixed(ISSUE_START.plusSeconds(1), ZoneOffset.UTC));

    private static Coupon openCoupon(long id, int issuedCount) {
        return open(CouponFixture.createPlatform(ADMIN_ID, anyLectureAmount().quantity(10).build(), NOW), id, issuedCount);
    }

    private static Coupon open(Coupon coupon, long id, int issuedCount) {
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
    void 강의_없이_부르면_강의_조건_없이_조회하고_매진과_받음을_표시한다() {
        // 다른 강의 대상 쿠폰도 강의를 정하지 않았으므로 목록에 남는다
        Coupon otherLecture = open(CouponFixture.createSeller(SELLER_ID, lectureRate().lectureId(8L).quantity(10).build(),
                NOW), SECOND_COUPON_ID, 0);
        when(couponRepository.findIssuableFor(isNull(), any(), any()))
                .thenReturn(List.of(openCoupon(COUPON_ID, 10), otherLecture));
        when(memberCouponRepository.findCouponIdsOwnedBy(MEMBER_ID, List.of(COUPON_ID, SECOND_COUPON_ID)))
                .thenReturn(List.of(SECOND_COUPON_ID));
        when(issueStore.readSnapshots(List.of(COUPON_ID, SECOND_COUPON_ID), MEMBER_ID)).thenReturn(Map.of(
                SECOND_COUPON_ID, new IssueSnapshot(4L, false)));

        List<CouponSummaryView> views = query.issuableFor(MEMBER_ID, null);

        verify(couponRepository).findIssuableFor(null, ISSUE_START.plusSeconds(1), CouponSupport.LIST_LIMIT);
        assertThat(views).extracting(CouponSummaryView::couponId, CouponSummaryView::lectureId,
                        CouponSummaryView::soldOut, CouponSummaryView::issued)
                .containsExactly(tuple(COUPON_ID, null, true, false), tuple(SECOND_COUPON_ID, 8L, false, true));
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

    // 쿠폰 받기 화면 목록 ------------------------------------------------------------------------------------

    @Test
    void 쿠폰_받기_목록은_판매_중인_강의만_골라_그_조건으로_쪽을_읽고_특정_강의_대상에_강의_제목을_싣는다() {
        long pendingLecture = 8L;
        long deletedLecture = 9L;
        Coupon lectureCoupon = open(CouponFixture.createSeller(SELLER_ID, lectureRate().quantity(10).build(), NOW),
                COUPON_ID, 10);
        Coupon anyLecture = openCoupon(SECOND_COUPON_ID, 0);
        when(couponRepository.findIssuableLectureIds(ISSUE_START.plusSeconds(1)))
                .thenReturn(List.of(LECTURE_ID, pendingLecture, deletedLecture));
        // 삭제된 강의는 결과에 없다
        when(lectureCatalog.findLectures(List.of(LECTURE_ID, pendingLecture, deletedLecture))).thenReturn(Map.of(
                LECTURE_ID, new LectureSummary(LECTURE_ID, "방송 장비 세팅", "ON_SALE"),
                pendingLecture, new LectureSummary(pendingLecture, "검수 중인 강의", "PENDING_INSPECTION")));
        when(couponRepository.findIssuablePage(Set.of(LECTURE_ID), ISSUE_START.plusSeconds(1), PageRequest.of(1, 2)))
                .thenReturn(new PageImpl<>(List.of(lectureCoupon, anyLecture), PageRequest.of(1, 2), 5));
        when(memberCouponRepository.findCouponIdsOwnedBy(MEMBER_ID, List.of(COUPON_ID, SECOND_COUPON_ID)))
                .thenReturn(List.of(SECOND_COUPON_ID));
        when(issueStore.readSnapshots(List.of(COUPON_ID, SECOND_COUPON_ID), MEMBER_ID)).thenReturn(Map.of(
                SECOND_COUPON_ID, new IssueSnapshot(4L, false)));

        Page<IssuableCouponView> page = query.issuablePage(MEMBER_ID, PageRequest.of(1, 2));

        assertThat(page.getContent()).extracting(IssuableCouponView::couponId, IssuableCouponView::lectureId,
                        IssuableCouponView::lectureTitle, IssuableCouponView::soldOut, IssuableCouponView::issued)
                .containsExactly(tuple(COUPON_ID, LECTURE_ID, "방송 장비 세팅", true, false),
                        tuple(SECOND_COUPON_ID, null, null, false, true));
        assertThat(page.getNumber()).isEqualTo(1);
        assertThat(page.getSize()).isEqualTo(2);
        assertThat(page.getTotalElements()).isEqualTo(5);
    }

    @Test
    void 쿠폰_받기_목록은_받을_수_있는_특정_강의_대상_쿠폰이_없으면_강의_앱을_부르지_않고_모든_강의_대상만_읽는다() {
        when(couponRepository.findIssuableLectureIds(any())).thenReturn(List.of());
        when(couponRepository.findIssuablePage(anyCollection(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        assertThat(query.issuablePage(MEMBER_ID, PageRequest.of(0, 20))).isEmpty();

        verify(couponRepository).findIssuablePage(Set.of(), ISSUE_START.plusSeconds(1), PageRequest.of(0, 20));
        verifyNoInteractions(lectureCatalog, memberCouponRepository, issueStore);
    }

    @Test
    void 쿠폰_받기_목록은_강의_정보를_확인하지_못하면_조회_불가를_그대로_내고_쪽을_읽지_않는다() {
        when(couponRepository.findIssuableLectureIds(any())).thenReturn(List.of(LECTURE_ID));
        when(lectureCatalog.findLectures(anyCollection()))
                .thenThrow(new LectureCatalogUnavailableException("연결 실패", null));

        assertThatThrownBy(() -> query.issuablePage(MEMBER_ID, PageRequest.of(0, 20)))
                .isInstanceOf(LectureCatalogUnavailableException.class);

        verify(couponRepository, never()).findIssuablePage(anyCollection(), any(), any());
        verifyNoInteractions(memberCouponRepository, issueStore);
    }

    @Test
    void 쿠폰_받기_목록의_쪽_크기는_100으로_자르고_요청의_정렬은_쓰지_않는다() {
        when(couponRepository.findIssuableLectureIds(any())).thenReturn(List.of());
        when(couponRepository.findIssuablePage(anyCollection(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 100), 0));

        query.issuablePage(MEMBER_ID, PageRequest.of(2, 500, Sort.by("name")));

        verify(couponRepository).findIssuablePage(Set.of(), ISSUE_START.plusSeconds(1), PageRequest.of(2, 100));
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
