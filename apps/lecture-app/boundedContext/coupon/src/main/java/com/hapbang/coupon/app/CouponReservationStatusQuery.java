package com.hapbang.coupon.app;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.hapbang.coupon.domain.CouponUsage;
import com.hapbang.coupon.domain.CouponUsageStatus;
import com.hapbang.coupon.domain.ProcessedOperation;
import com.hapbang.coupon.out.CouponUsageRepository;
import com.hapbang.shared.coupon.api.CouponReservationStatusResponse;

/**
 * 쿠폰 예약 상태 조회(강의 → 쿠폰 내부 API). 강의가 예약 응답을 잃었거나 주문 저장에 실패했을 때 예약 작업의 결과를 확인한다.
 * <ul>
 *     <li>작업 기록이 없거나, 기록의 회원·주문이 요청과 다르면 NOT_FOUND다(작업 ID만 알아서는 다른 회원·주문의 결과를 볼 수 없다).</li>
 *     <li>거절 기록이면 REJECTED와 거절 코드다.</li>
 *     <li>예약 기록이면 사용 시도를 사용 시도별 상태와 함께 준다. 모두 해제됐으면 RELEASED, 아니면 RESERVED다.</li>
 * </ul>
 * 사용 경로라 Redis를 쓰지 않는다.
 */
@Service
public class CouponReservationStatusQuery {

    static final String NOT_FOUND = "NOT_FOUND";

    private final CouponUsageSupport usageSupport;
    private final CouponUsageRepository couponUsageRepository;

    public CouponReservationStatusQuery(CouponUsageSupport usageSupport, CouponUsageRepository couponUsageRepository) {
        this.usageSupport = usageSupport;
        this.couponUsageRepository = couponUsageRepository;
    }

    public CouponReservationStatusResponse status(String workId, long memberId, long orderId) {
        Optional<ProcessedOperation> recorded = usageSupport
                .findOperation(CouponUsageSupport.RESERVE_OPERATION_PREFIX + workId)
                .filter(operation -> operation.getContentKey().startsWith(ownerPrefix(memberId, orderId)));
        if (recorded.isEmpty()) {
            return new CouponReservationStatusResponse(workId, NOT_FOUND, null, List.of());
        }
        if (CouponUsageSupport.RESULT_REJECTED.equals(recorded.get().getResult())) {
            return new CouponReservationStatusResponse(workId, CouponUsageSupport.RESULT_REJECTED,
                    CouponUsageSupport.rejectCodeOf(recorded.get().getDetail()), List.of());
        }
        List<CouponUsage> usages = couponUsageRepository.findByReservationWorkIdOrderByOrderItemIdAscApplyOrderAsc(
                workId);
        boolean allReleased = !usages.isEmpty()
                && usages.stream().allMatch(usage -> usage.getStatus() == CouponUsageStatus.RELEASED);
        String status = allReleased ? CouponUsageSupport.RESULT_RELEASED : CouponUsageSupport.RESULT_RESERVED;
        return new CouponReservationStatusResponse(workId, status, null, toItems(usages));
    }

    /** 내용 키의 소유 문맥 머리({@code m{memberId}|o{orderId}|}) */
    private static String ownerPrefix(long memberId, long orderId) {
        return "m" + memberId + "|o" + orderId + "|";
    }

    private static List<CouponReservationStatusResponse.Item> toItems(List<CouponUsage> usages) {
        return CouponUsageSupport.groupByOrderItem(usages).entrySet().stream()
                .map(entry -> {
                    List<CouponUsage> itemUsages = entry.getValue();
                    List<CouponReservationStatusResponse.Coupon> coupons = itemUsages.stream()
                            .map(usage -> new CouponReservationStatusResponse.Coupon(usage.getMemberCouponId(),
                                    usage.getCouponId(), usage.getGeneration(), usage.getApplyOrder(),
                                    usage.getBaseAmount(), usage.getDiscountAmount(), usage.getPlatformAmount(),
                                    usage.getSellerAmount(), usage.getPolicyVersion(), usage.getStatus().name()))
                            .toList();
                    return new CouponReservationStatusResponse.Item(entry.getKey(),
                            itemUsages.stream().mapToLong(CouponUsage::getDiscountAmount).sum(),
                            itemUsages.stream().mapToLong(CouponUsage::getPlatformAmount).sum(),
                            itemUsages.stream().mapToLong(CouponUsage::getSellerAmount).sum(), coupons);
                })
                .toList();
    }
}
