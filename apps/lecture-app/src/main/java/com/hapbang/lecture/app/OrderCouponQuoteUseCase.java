package com.hapbang.lecture.app;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.hapbang.lecture.app.dto.OrderCouponSelection;
import com.hapbang.lecture.domain.CouponRejectedException;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureErrorCode;
import com.hapbang.lecture.domain.LectureException;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.lecture.out.coupon.CouponCallResult;
import com.hapbang.lecture.out.coupon.CouponApiClient;
import com.hapbang.shared.coupon.api.CouponCalculateRequest;
import com.hapbang.shared.coupon.api.CouponCalculateResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 쿠폰 주문 0단계: 주문을 만들기 전에 서버 가격·판매자로 쿠폰 할인을 다시 계산한다. 트랜잭션 밖에서 부른다(쿠폰 내부 API 호출).
 * <ol>
 *     <li>강의를 읽어 주문할 수 있는지 주문 저장과 같은 규칙으로 먼저 검사한다.</li>
 *     <li>쿠폰 계산을 구매자가 고른 매핑({@code selections})으로 부른다. 거절이면 409 {@code COUPON_CALCULATION_REJECTED}
 *     (쿠폰 오류 코드 보존), 보내지 않음·결과 불명과 인증·권한 거절(401·403, 설정 문제)이면 503 {@code COUPON_UNAVAILABLE}이다.</li>
 *     <li>플랫폼 부담 할인이 있으면 409 {@code COUPON_PLATFORM_FUNDING_UNAVAILABLE}이다. 플랫폼 부담의 재원 처리가 아직 없다.</li>
 *     <li>구매자 금액 합이 구매자가 본 금액과 다르면 409 {@code ORDER_QUOTE_CHANGED}다.</li>
 * </ol>
 * 어느 경우든 실패하면 주문을 만들지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCouponQuoteUseCase {

    /** 쿠폰 내부 API가 인증·권한을 거절했을 때(내부 API 키·설정 문제) 붙이는 alert 표식 */
    static final String AUTH_FAILED_LOG = "[LECTURE_COUPON_AUTH_FAILED]";

    /** 쿠폰 계산 응답의 금액이 할인 스냅샷 규칙에 맞지 않을 때(모듈 간 계약 위반) 붙이는 alert 표식 */
    static final String QUOTE_INVALID_LOG = "[LECTURE_COUPON_QUOTE_INVALID]";

    private final LectureRepository lectureRepository;
    private final LectureOrderRepository lectureOrderRepository;
    private final CouponApiClient couponApiClient;

    OrderCouponQuote quote(Long buyerUserId, List<Long> lectureIds, List<OrderCouponSelection> selections,
                           Long expectedBuyerAmountHp) {
        if (expectedBuyerAmountHp == null) {
            throw new LectureException(LectureErrorCode.INVALID_REQUEST, "쿠폰을 쓰는 주문은 결제 금액을 함께 보내야 합니다.");
        }
        // 주문 저장과 같은 강의 순서로 계산한다
        List<Long> sortedLectureIds = lectureIds.stream().sorted().toList();
        List<Lecture> lectures = findLectures(sortedLectureIds);
        LectureOrder.checkPlaceable(buyerUserId, lectures,
                lectureOrderRepository.findHeldLectureIds(buyerUserId, sortedLectureIds));

        CouponCalculateResponse response = calculate(buyerUserId, lectures, selections);
        OrderCouponQuote quote = toQuote(lectures, response);
        long platformDiscountHp = quote.items().values().stream()
                .mapToLong(OrderCouponQuote.Item::platformDiscountHp).sum();
        if (platformDiscountHp > 0) {
            throw new LectureException(LectureErrorCode.COUPON_PLATFORM_FUNDING_UNAVAILABLE);
        }
        long buyerAmountHp = quote.items().values().stream()
                .mapToLong(item -> item.priceHp() - item.discountHp()).sum();
        if (buyerAmountHp != expectedBuyerAmountHp) {
            log.info("쿠폰 주문의 결제 금액이 구매자가 본 금액과 달라 주문하지 않았다 buyerUserId={}, expectedHp={}, "
                    + "quotedHp={}", buyerUserId, expectedBuyerAmountHp, buyerAmountHp);
            throw new LectureException(LectureErrorCode.ORDER_QUOTE_CHANGED);
        }
        return quote;
    }

    private CouponCalculateResponse calculate(Long buyerUserId, List<Lecture> lectures,
                                              List<OrderCouponSelection> selections) {
        CouponCalculateRequest request = new CouponCalculateRequest(buyerUserId,
                lectures.stream().map(lecture -> new CouponCalculateRequest.Item(lecture.getId(),
                        lecture.getSellerUserId(), lecture.getPriceHp())).toList(),
                selections.stream().map(selection -> new CouponCalculateRequest.Selection(
                        selection.memberCouponId(), selection.lectureId())).toList());
        return switch (couponApiClient.calculate(request)) {
            case CouponCallResult.Succeeded<CouponCalculateResponse> succeeded -> succeeded.data();
            case CouponCallResult.Rejected<CouponCalculateResponse> rejected when isAuthFailure(rejected.status()) -> {
                log.error(AUTH_FAILED_LOG + " 쿠폰 내부 API가 인증·권한을 거절해 주문하지 않았다. 내부 API 키·설정을 확인한다 "
                        + "buyerUserId={}, httpStatus={}", buyerUserId, rejected.status());
                throw new LectureException(LectureErrorCode.COUPON_UNAVAILABLE);
            }
            case CouponCallResult.Rejected<CouponCalculateResponse> rejected -> {
                log.info("쿠폰 할인 계산이 거절되어 주문하지 않았다 buyerUserId={}, httpStatus={}, couponCode={}, "
                        + "memberCouponId={}", buyerUserId, rejected.status(), rejected.code(),
                        rejected.memberCouponId());
                throw new CouponRejectedException(LectureErrorCode.COUPON_CALCULATION_REJECTED, rejected.code(),
                        rejected.memberCouponId(), rejected.detail());
            }
            case CouponCallResult.NotSent<CouponCalculateResponse> notSent -> {
                log.warn("쿠폰 할인 계산을 보내지 못해 주문하지 않았다 buyerUserId={}, reason={}", buyerUserId,
                        notSent.reason());
                throw new LectureException(LectureErrorCode.COUPON_UNAVAILABLE);
            }
            case CouponCallResult.Unknown<CouponCalculateResponse> unknown -> {
                log.warn("쿠폰 할인 계산 결과를 몰라 주문하지 않았다 buyerUserId={}, httpStatus={}, reason={}",
                        buyerUserId, unknown.status(), unknown.reason());
                throw new LectureException(LectureErrorCode.COUPON_UNAVAILABLE);
            }
        };
    }

    /**
     * 쿠폰의 401·403은 구매자 선택이 아니라 강의 앱의 내부 API 키·설정 문제다. 구매자에게는 쿠폰을 확인할 수 없음(503)으로 응답한다.
     * 쿠폰 Client는 4xx를 모두 거절로 나누므로 여기서 상태로 가른다
     */
    static boolean isAuthFailure(int status) {
        return status == 401 || status == 403;
    }

    /**
     * 계산 응답을 강의별로 모은다. 요청한 강의가 응답에 빠졌거나 가격이 다르면 쓸 수 없는 응답이라 결과 불명과 같게 본다.
     * 금액이 맞지 않는 응답(할인 ≠ 플랫폼 + 판매자, 음수 몫, 할인 > 가격, 쿠폰 한 장의 같은 규칙)도 주문 저장의 스냅샷 규칙·DB 제약에
     * 걸려 트랜잭션 안에서 실패하므로, 저장 전에 같은 503으로 막고 쿠폰 응답 불량으로 ERROR를 남긴다
     */
    private static OrderCouponQuote toQuote(List<Lecture> lectures, CouponCalculateResponse response) {
        Map<Long, CouponCalculateResponse.Item> responseItems = response.items().stream()
                .collect(Collectors.toMap(CouponCalculateResponse.Item::lectureId, Function.identity(),
                        (first, second) -> first));
        Map<Long, OrderCouponQuote.Item> items = new HashMap<>();
        for (Lecture lecture : lectures) {
            CouponCalculateResponse.Item item = responseItems.get(lecture.getId());
            if (item == null || item.price() != lecture.getPriceHp()) {
                log.warn("쿠폰 할인 계산 응답이 주문할 강의와 맞지 않아 주문하지 않았다 lectureId={}", lecture.getId());
                throw new LectureException(LectureErrorCode.COUPON_UNAVAILABLE);
            }
            if (!hasConsistentAmounts(item)) {
                log.error(QUOTE_INVALID_LOG + " 쿠폰 할인 계산 응답의 금액이 맞지 않아 주문하지 않았다. 쿠폰 응답 불량이다 lectureId={}, price={}, "
                                + "discount={}, platformDiscount={}, sellerDiscount={}, coupons={}", lecture.getId(),
                        item.price(), item.discount(), item.platformDiscount(), item.sellerDiscount(), item.coupons());
                throw new LectureException(LectureErrorCode.COUPON_UNAVAILABLE);
            }
            items.put(lecture.getId(), new OrderCouponQuote.Item(lecture.getPriceHp(), lecture.getSellerUserId(),
                    item.discount(), item.platformDiscount(), item.sellerDiscount(), item.coupons()));
        }
        return new OrderCouponQuote(items);
    }

    /** 항목·쿠폰 금액이 할인 스냅샷 규칙(할인 = 플랫폼 + 판매자, 두 몫 ≥ 0, 할인 ≤ 기준 금액)을 지키는지 */
    private static boolean hasConsistentAmounts(CouponCalculateResponse.Item item) {
        if (!isSplit(item.discount(), item.platformDiscount(), item.sellerDiscount(), item.price())) {
            return false;
        }
        return item.coupons().stream().allMatch(coupon -> isSplit(coupon.discount(), coupon.platformAmount(),
                coupon.sellerAmount(), coupon.baseAmount()));
    }

    /** 덧셈 대신 범위를 먼저 보고 뺄셈으로 비교해, 큰 값의 합이 넘쳐 우연히 같아지는 응답도 거른다 */
    private static boolean isSplit(long discount, long platform, long seller, long base) {
        return discount >= 0 && discount <= base && platform >= 0 && seller >= 0 && platform <= discount
                && discount - platform == seller;
    }

    /** 받은 순서대로 강의를 읽는다. 없는 강의는 판매 중이 아닌 강의와 같게 본다(주문 저장과 같다) */
    private List<Lecture> findLectures(List<Long> lectureIds) {
        Map<Long, Lecture> lectures = lectureRepository.findAllById(lectureIds).stream()
                .collect(Collectors.toMap(Lecture::getId, Function.identity()));
        if (!lectures.keySet().containsAll(lectureIds)) {
            throw new LectureException(LectureErrorCode.LECTURE_NOT_FOUND);
        }
        return lectureIds.stream().map(lectures::get).toList();
    }
}
