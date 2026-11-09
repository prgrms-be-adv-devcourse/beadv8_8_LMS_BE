package com.hapbang.coupon.app;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.hapbang.coupon.app.port.LectureCatalog.LectureSnapshot;
import com.hapbang.coupon.config.CouponClockConfig;
import com.hapbang.coupon.domain.usage.AppliedCoupon;
import com.hapbang.coupon.domain.usage.CalculationResult;
import com.hapbang.coupon.domain.usage.CouponUsageViolationException;
import com.hapbang.coupon.domain.usage.ItemResult;
import com.hapbang.coupon.domain.usage.Selection;
import com.hapbang.coupon.domain.usage.UsableCoupon;
import com.hapbang.coupon.domain.usage.UsageItem;
import com.hapbang.coupon.global.exception.CouponErrorCode;
import com.hapbang.coupon.global.exception.CouponUsageException;
import com.hapbang.shared.coupon.api.CouponCalculateRequest;
import com.hapbang.shared.coupon.api.CouponCalculateResponse;

/**
 * 쿠폰 계산(강의 → 쿠폰 내부 API)과 회원용 계산 미리보기({@link #quote}). 주문 전 계산과 재계산이 같은 경로다. 상태를 바꾸지 않는다.
 * <ul>
 *     <li>강의마다 강의 조회로 가격·판매자를 다시 확인한다(트랜잭션 밖). 다르면 409 {@code COUPON_LECTURE_PRICE_MISMATCH}, 강의가 없으면
 *     404, 강의 앱에 닿지 못하면 503이다.</li>
 *     <li>매핑이 {@code null}이면 회원의 AVAILABLE 발급 쿠폰 전부를 후보로 추천하고, 빈 목록이면 쿠폰을 쓰지 않으며, 값이 있으면 그 매핑의
 *     발급 쿠폰만 읽어 검증·계산한다. 판정은 {@code CouponCalculator}가 한다.</li>
 * </ul>
 * 사용 경로라 Redis를 쓰지 않는다.
 */
@Service
public class CouponCalculateUseCase {

    private final CouponUsageSupport usageSupport;
    private final CouponSupport couponSupport;
    private final Clock clock;

    public CouponCalculateUseCase(CouponUsageSupport usageSupport, CouponSupport couponSupport,
                                  @Qualifier(CouponClockConfig.CLOCK) Clock clock) {
        this.usageSupport = usageSupport;
        this.couponSupport = couponSupport;
        this.clock = clock;
    }

    public CouponCalculateResponse calculate(CouponCalculateRequest request) {
        usageSupport.requireItems(request.items().stream().map(CouponCalculateRequest.Item::lectureId).toList());
        List<UsageItem> items = new ArrayList<>();
        for (CouponCalculateRequest.Item item : request.items()) {
            requireSameAsCatalog(item);
            items.add(new UsageItem(null, item.lectureId(), item.sellerId(), item.price()));
        }
        List<Selection> selections = request.selections() == null ? null : request.selections().stream()
                .map(selection -> new Selection(selection.memberCouponId(), selection.lectureId()))
                .toList();
        return calculate(request.memberId(), items, selections);
    }

    /**
     * 회원용 계산 미리보기. 강의마다 강의 조회로 지금 가격·판매자를 채운 뒤 {@link #calculate(CouponCalculateRequest)}와 같은 계산·응답을 돌려준다. 강의 주문의
     * 쿠폰 계산(0단계)은 이 응답의 구매자 금액 합을 회원이 본 금액({@code expectedBuyerAmountHp})으로 받아 대조한다.
     * <p>
     * 항목 수 상한·같은 강의 중복은 강의 조회 전에 본다(400). 강의가 없으면 404, 강의 앱에 닿지 못하면 503이다.
     *
     * @param selections {@code null}이면 추천, 빈 목록이면 쿠폰을 쓰지 않음, 값이 있으면 그 매핑만 검증·계산한다
     */
    public CouponCalculateResponse quote(long memberId, List<Long> lectureIds, List<Selection> selections) {
        usageSupport.requireItems(lectureIds);
        List<UsageItem> items = new ArrayList<>();
        for (Long lectureId : lectureIds) {
            LectureSnapshot lecture = couponSupport.findLecture(lectureId);
            items.add(new UsageItem(null, lectureId, lecture.sellerId(), lecture.price()));
        }
        return calculate(memberId, items, selections == null ? null : List.copyOf(selections));
    }

    private CouponCalculateResponse calculate(long memberId, List<UsageItem> items, List<Selection> selections) {
        List<UsableCoupon> owned = selections == null
                ? usageSupport.availableCoupons(memberId)
                : List.copyOf(usageSupport.loadCoupons(selections.stream().map(Selection::memberCouponId).toList())
                        .values());
        Instant now = clock.instant();
        CalculationResult result;
        try {
            result = usageSupport.calculator().calculate(items, owned, selections, memberId, now,
                    usageSupport.maxSearchNodes());
        } catch (CouponUsageViolationException violation) {
            throw CouponUsageSupport.toUsageException(violation);
        }
        return toResponse(result);
    }

    private void requireSameAsCatalog(CouponCalculateRequest.Item item) {
        LectureSnapshot lecture = couponSupport.findLecture(item.lectureId());
        if (lecture.price() != item.price() || lecture.sellerId() != item.sellerId()) {
            throw new CouponUsageException(CouponErrorCode.COUPON_LECTURE_PRICE_MISMATCH,
                    "강의 가격 또는 판매자가 바뀌었습니다. 다시 계산해 주세요. lectureId=" + item.lectureId(), null);
        }
    }

    private static CouponCalculateResponse toResponse(CalculationResult result) {
        List<CouponCalculateResponse.Item> items = result.items().stream()
                .map(CouponCalculateUseCase::toItem)
                .toList();
        return new CouponCalculateResponse(result.recommended(), result.optimal(), items, result.totalDiscount(),
                result.totalBuyerAmount());
    }

    private static CouponCalculateResponse.Item toItem(ItemResult item) {
        List<CouponCalculateResponse.Coupon> coupons = item.coupons().stream()
                .map(CouponCalculateUseCase::toCoupon)
                .toList();
        return new CouponCalculateResponse.Item(item.item().lectureId(), item.item().price(), item.discount(),
                item.platformDiscount(), item.sellerDiscount(), item.buyerAmount(), item.sellerPayout(), coupons);
    }

    private static CouponCalculateResponse.Coupon toCoupon(AppliedCoupon coupon) {
        return new CouponCalculateResponse.Coupon(coupon.memberCouponId(), coupon.couponId(), coupon.type().name(),
                coupon.applyOrder(), coupon.baseAmount(), coupon.discount(), coupon.platformAmount(),
                coupon.sellerAmount(), coupon.expectedGeneration(), coupon.policyVersion());
    }
}
