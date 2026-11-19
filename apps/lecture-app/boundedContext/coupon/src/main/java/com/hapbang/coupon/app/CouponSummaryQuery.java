package com.hapbang.coupon.app;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.hapbang.coupon.app.dto.CouponSummaryView;
import com.hapbang.coupon.app.dto.IssuableCouponView;
import com.hapbang.coupon.app.exception.CouponErrorCode;
import com.hapbang.coupon.app.exception.CouponException;
import com.hapbang.coupon.app.port.CouponIssueStore.IssueSnapshot;
import com.hapbang.coupon.app.port.CouponIssueStore;
import com.hapbang.coupon.app.port.CouponIssueStoreUnavailableException;
import com.hapbang.coupon.app.port.CouponRepository;
import com.hapbang.coupon.app.port.LectureCatalog.LectureSummary;
import com.hapbang.coupon.app.port.LectureCatalog;
import com.hapbang.coupon.app.port.MemberCouponRepository;
import com.hapbang.coupon.config.CouponClockConfig;
import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.CouponAvailabilityRule;
import com.hapbang.coupon.domain.LectureSaleRule;

import lombok.extern.slf4j.Slf4j;

/**
 * 회원 쿠폰 조회: 받을 수 있는 쿠폰 목록(강의 화면은 그 강의 것, 강의를 정하지 않으면 전체), 쿠폰 받기 화면 목록(쪽 단위, 강의 제목,
 * 판매 중인 강의만)과 쿠폰 상세. 매진·받음 표시는 DB(발급 수·발급 쿠폰)와 Redis(남은 재고·접수 회원)를 함께 보고
 * {@link CouponAvailabilityRule}이 정한다. Redis는 한 번의 파이프라인으로 읽고, 닿지 못하면 DB 값만으로 표시한다(조회는 실패시키지 않는다).
 */
@Slf4j
@Service
public class CouponSummaryQuery {

    private final CouponRepository couponRepository;
    private final MemberCouponRepository memberCouponRepository;
    private final CouponIssueStore couponIssueStore;
    private final CouponSupport couponSupport;
    private final LectureCatalog lectureCatalog;
    private final Clock clock;

    public CouponSummaryQuery(CouponRepository couponRepository, MemberCouponRepository memberCouponRepository,
                              CouponIssueStore couponIssueStore, CouponSupport couponSupport,
                              LectureCatalog lectureCatalog, @Qualifier(CouponClockConfig.CLOCK) Clock clock) {
        this.couponRepository = couponRepository;
        this.memberCouponRepository = memberCouponRepository;
        this.couponIssueStore = couponIssueStore;
        this.couponSupport = couponSupport;
        this.lectureCatalog = lectureCatalog;
        this.clock = clock;
    }

    /**
     * 지금 받을 수 있는 쿠폰(최신순, 상한 {@link CouponSupport#MAX_LIST_SIZE}건). 매진·이미 받은 쿠폰도 표시와 함께 넣는다.
     *
     * @param lectureId 있으면 그 강의 대상과 모든 강의 대상만, {@code null}이면 대상을 가리지 않는다
     */
    public List<CouponSummaryView> issuableFor(long memberId, Long lectureId) {
        Instant now = clock.instant();
        List<Coupon> coupons = couponRepository.findIssuableFor(lectureId, now, CouponSupport.LIST_LIMIT).stream()
                .filter(coupon -> CouponAvailabilityRule.isListedFor(coupon, lectureId, now))
                .toList();
        return toViews(memberId, coupons, CouponSummaryView::of);
    }

    /**
     * 쿠폰 받기 화면 목록 한 쪽: 지금 받을 수 있는 쿠폰 중 모든 강의 대상과, 대상 강의가 판매 중인 특정 강의 대상(강의 제목과 함께).
     * 최신순이고 쪽 크기는 {@link CouponSupport#MAX_LIST_SIZE}로 자른다. 매진·이미 받은 쿠폰도 표시와 함께 넣는다.
     * <p>
     * 판매 중인 강의를 먼저 고르고 그 조건으로 DB에서 쪽을 읽으므로, 쪽 크기와 전체 수가 화면에 보일 쿠폰과 맞다(읽은 뒤 거르지 않는다).
     * 강의 조회는 트랜잭션 밖에서 하고, 강의 앱에 닿지 못하면 {@link com.hapbang.coupon.app.port.LectureCatalogUnavailableException}이
     * 그대로 나간다(503). 받을 수 있는 특정 강의 대상 쿠폰이 없으면 강의 앱을 부르지 않는다.
     */
    public Page<IssuableCouponView> issuablePage(long memberId, Pageable pageable) {
        Instant now = clock.instant();
        Map<Long, LectureSummary> onSale = onSaleLectures(couponRepository.findIssuableLectureIds(now));
        Pageable page = PageRequest.of(pageable.getPageNumber(),
                Math.min(pageable.getPageSize(), CouponSupport.MAX_LIST_SIZE));
        Page<Coupon> coupons = couponRepository.findIssuablePage(onSale.keySet(), now, page);
        List<IssuableCouponView> views = toViews(memberId, coupons.getContent(), (coupon, soldOut, issued) ->
                IssuableCouponView.of(coupon, lectureTitleOf(coupon, onSale), soldOut, issued));
        return new PageImpl<>(views, coupons.getPageable(), coupons.getTotalElements());
    }

    /** 쿠폰 상세. 회원이 볼 수 없는 상태(DRAFT·REVIEW·REJECTED)는 없는 쿠폰과 같이 404다 */
    public CouponSummaryView summary(long memberId, long couponId) {
        Coupon coupon = couponSupport.findCoupon(couponId);
        if (!CouponAvailabilityRule.isVisibleToMember(coupon.getStatus())) {
            throw new CouponException(CouponErrorCode.COUPON_NOT_FOUND);
        }
        return toViews(memberId, List.of(coupon), CouponSummaryView::of).getFirst();
    }

    /** 강의 중 판매 중인 것(없거나 삭제된 강의, 검수 대기·반려는 뺀다). 강의 ID → 강의 */
    private Map<Long, LectureSummary> onSaleLectures(List<Long> lectureIds) {
        if (lectureIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, LectureSummary> onSale = new HashMap<>();
        lectureCatalog.findLectures(lectureIds).forEach((lectureId, lecture) -> {
            if (LectureSaleRule.isOnSale(lecture.saleStatus())) {
                onSale.put(lectureId, lecture);
            }
        });
        return onSale;
    }

    /** 특정 강의 대상이면 그 강의 제목, 모든 강의 대상이면 {@code null} */
    private static String lectureTitleOf(Coupon coupon, Map<Long, LectureSummary> lectures) {
        LectureSummary lecture = coupon.getLectureId() == null ? null : lectures.get(coupon.getLectureId());
        return lecture == null ? null : lecture.title();
    }

    private <V> List<V> toViews(long memberId, List<Coupon> coupons, ViewFactory<V> viewFactory) {
        if (coupons.isEmpty()) {
            return List.of();
        }
        List<Long> couponIds = coupons.stream().map(Coupon::getId).toList();
        Set<Long> owned = new HashSet<>(memberCouponRepository.findCouponIdsOwnedBy(memberId, couponIds));
        Map<Long, IssueSnapshot> snapshots = readSnapshots(couponIds, memberId);
        return coupons.stream()
                .map(coupon -> {
                    IssueSnapshot snapshot = snapshots.get(coupon.getId());
                    Long stock = snapshot == null ? null : snapshot.stock();
                    boolean accepted = snapshot != null && snapshot.memberAccepted();
                    return viewFactory.of(coupon,
                            CouponAvailabilityRule.isSoldOut(coupon.getQuantity(), coupon.getIssuedCount(), stock),
                            CouponAvailabilityRule.isIssued(owned.contains(coupon.getId()), accepted));
                })
                .toList();
    }

    /** Redis 표시 값. 닿지 못하면 빈 값(모름)이고 DB 값만으로 표시한다 */
    private Map<Long, IssueSnapshot> readSnapshots(List<Long> couponIds, long memberId) {
        try {
            return couponIssueStore.readSnapshots(couponIds, memberId);
        } catch (CouponIssueStoreUnavailableException exception) {
            // Redis 장애 동안 조회마다 몰리므로 요청마다 WARN을 남기지 않는다. 장애는 발급 접수의 503으로 보인다
            log.debug("발급 표시 값을 읽지 못해 DB 값만으로 응답한다 couponCount={}, memberId={}", couponIds.size(), memberId,
                    exception);
            return Map.of();
        }
    }

    /** 쿠폰과 매진·받음 표시로 응답 한 건을 만든다 */
    @FunctionalInterface
    private interface ViewFactory<V> {

        V of(Coupon coupon, boolean soldOut, boolean issued);
    }
}
