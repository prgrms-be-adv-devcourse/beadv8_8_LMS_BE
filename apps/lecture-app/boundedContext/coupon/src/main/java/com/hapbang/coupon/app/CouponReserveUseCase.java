package com.hapbang.coupon.app;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.coupon.config.CouponClockConfig;
import com.hapbang.coupon.domain.CouponUsage;
import com.hapbang.coupon.domain.CouponUsageStatus;
import com.hapbang.coupon.domain.MemberCouponStatus;
import com.hapbang.coupon.domain.ProcessedOperation;
import com.hapbang.coupon.domain.usage.AppliedCoupon;
import com.hapbang.coupon.domain.usage.CouponUsageViolationException;
import com.hapbang.coupon.domain.usage.ItemResult;
import com.hapbang.coupon.domain.usage.ReservationBasis;
import com.hapbang.coupon.domain.usage.ReservationBasis.Check;
import com.hapbang.coupon.domain.usage.ReservationBasis.RequestedCoupon;
import com.hapbang.coupon.domain.usage.UsableCoupon;
import com.hapbang.coupon.domain.usage.UsageContentKey;
import com.hapbang.coupon.domain.usage.UsageItem;
import com.hapbang.coupon.global.exception.CouponErrorCode;
import com.hapbang.coupon.global.exception.CouponUsageException;
import com.hapbang.coupon.out.CouponUsageRepository;
import com.hapbang.coupon.out.MemberCouponRepository;
import com.hapbang.coupon.out.MemberCouponState;
import com.hapbang.coupon.out.ProcessedOperationRepository;
import com.hapbang.shared.coupon.api.CouponReserveRequest;
import com.hapbang.shared.coupon.api.CouponReserveResponse;

import lombok.extern.slf4j.Slf4j;

/**
 * 쿠폰 예약(강의 → 쿠폰 내부 API). 주문의 모든 쿠폰을 한 트랜잭션에서 예약하거나 하나도 예약하지 않는다.
 * <ol>
 *     <li>같은 작업 ID의 기록이 있으면 처음 결과를 다시 준다(예약 성공은 사용 시도에서 다시 만들고, 거절은 같은 코드로 거절). 내용이 다르거나
 *     그 예약을 이미 해제했으면 409 {@code COUPON_WORK_CONFLICT}다.</li>
 *     <li>소유 → 세대(계산 뒤 다른 사용 시도가 있었는가) → 기한·상태·적용 대상·중첩·순서를 다시 검증하고 금액을 다시 계산해 요청
 *     근거(정책 버전·적용 순서·금액)와 대조한다. 가격은 요청의 주문 스냅샷 가격을 쓴다(강의 조회는 계산에서만 한다).</li>
 *     <li>발급 쿠폰 ID 오름차순으로 generation 조건부 갱신을 하고(교착 회피), 0행이면 다시 읽어 원인을 가른다. 그 뒤 사용 시도와 작업
 *     기록을 쓴다.</li>
 *     <li>업무 거절이면 롤백한 뒤 별도 트랜잭션으로 거절 코드를 기록한다. 같은 작업 ID의 재시도는 같은 거절을 받는다.</li>
 * </ol>
 * 사용 경로라 Redis를 쓰지 않는다. 동시성은 {@code @Version}·분산락이 아니라 generation 조건부 갱신과 UNIQUE가 가린다.
 */
@Slf4j
@Service
public class CouponReserveUseCase {

    /** 기록해 같은 작업 ID 재시도에 같은 거절을 주는 업무 거절 */
    private static final Set<CouponErrorCode> RECORDED_REJECTIONS = EnumSet.of(
            CouponErrorCode.COUPON_NOT_APPLICABLE,
            CouponErrorCode.COUPON_NOT_USABLE,
            CouponErrorCode.COUPON_STACKING_VIOLATION,
            CouponErrorCode.COUPON_GENERATION_MISMATCH,
            CouponErrorCode.COUPON_POLICY_VERSION_CHANGED,
            CouponErrorCode.COUPON_AMOUNT_MISMATCH);

    private final CouponUsageSupport usageSupport;
    private final MemberCouponRepository memberCouponRepository;
    private final CouponUsageRepository couponUsageRepository;
    private final ProcessedOperationRepository processedOperationRepository;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public CouponReserveUseCase(CouponUsageSupport usageSupport, MemberCouponRepository memberCouponRepository,
                                CouponUsageRepository couponUsageRepository,
                                ProcessedOperationRepository processedOperationRepository,
                                PlatformTransactionManager transactionManager,
                                @Qualifier(CouponClockConfig.CLOCK) Clock clock) {
        this.usageSupport = usageSupport;
        this.memberCouponRepository = memberCouponRepository;
        this.couponUsageRepository = couponUsageRepository;
        this.processedOperationRepository = processedOperationRepository;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public CouponReserveResponse reserve(CouponReserveRequest request) {
        usageSupport.requireItems(request.items().stream().map(CouponReserveRequest.Item::lectureId).toList());
        usageSupport.requireDistinctOrderItems(
                request.items().stream().map(CouponReserveRequest.Item::orderItemId).toList());
        String operationId = CouponUsageSupport.RESERVE_OPERATION_PREFIX + request.workId();
        String contentKey = contentKey(request);

        Optional<ProcessedOperation> recorded = usageSupport.findOperation(operationId);
        if (recorded.isPresent()) {
            return replay(recorded.get(), request.workId(), contentKey);
        }
        try {
            return transaction.execute(status -> reserveInTransaction(request, operationId, contentKey));
        } catch (CouponUsageException exception) {
            if (!RECORDED_REJECTIONS.contains(exception.getErrorCode())) {
                throw exception;
            }
            // 같은 작업 ID의 다른 요청이 먼저 끝났으면(동시 재전송) 그 결과를 따른다
            Optional<ProcessedOperation> existing = usageSupport.recordRejection(operationId, contentKey, exception,
                    clock.instant());
            if (existing.isPresent()) {
                return replay(existing.get(), request.workId(), contentKey);
            }
            log.info("쿠폰 예약을 거절했다 workId={}, orderId={}, memberId={}, code={}, memberCouponId={}",
                    request.workId(), request.orderId(), request.memberId(), exception.getErrorCode().getCode(),
                    exception.getMemberCouponId());
            throw exception;
        } catch (DataIntegrityViolationException exception) {
            if (!CouponUsageSupport.isOperationIdViolation(exception)) {
                throw exception;
            }
            // 같은 작업 ID를 동시에 기록했다. 먼저 기록한 결과를 따른다
            ProcessedOperation existing = usageSupport.findOperation(operationId).orElseThrow(() -> exception);
            return replay(existing, request.workId(), contentKey);
        }
    }

    private CouponReserveResponse reserveInTransaction(CouponReserveRequest request, String operationId,
                                                       String contentKey) {
        long memberId = request.memberId();
        Instant now = clock.instant();
        Map<Long, UsableCoupon> coupons = usageSupport.loadCoupons(requireDistinctCoupons(request));

        List<ReservedItem> reservedItems = new ArrayList<>();
        for (CouponReserveRequest.Item item : request.items()) {
            UsageItem usageItem = new UsageItem(item.orderItemId(), item.lectureId(), item.sellerId(), item.price());
            List<UsableCoupon> couponsForItem = new ArrayList<>();
            for (CouponReserveRequest.Coupon coupon : item.coupons()) {
                UsableCoupon found = coupons.get(coupon.memberCouponId());
                if (found == null || found.memberId() != memberId) {
                    throw new CouponUsageException(CouponErrorCode.COUPON_NOT_USABLE, coupon.memberCouponId());
                }
                // 다른 사용 시도가 먼저 예약·해제했으면 세대가 바뀌었다. 상태(RESERVED 등)보다 먼저 세대 불일치로 알린다
                if (found.generation() != coupon.expectedGeneration()) {
                    throw new CouponUsageException(CouponErrorCode.COUPON_GENERATION_MISMATCH,
                            coupon.memberCouponId());
                }
                couponsForItem.add(found);
            }
            ItemResult recomputed;
            try {
                recomputed = usageSupport.calculator().apply(usageItem, couponsForItem, memberId, now);
            } catch (CouponUsageViolationException violation) {
                throw CouponUsageSupport.toUsageException(violation);
            }
            requireBasisMatches(item, recomputed);
            reservedItems.add(new ReservedItem(usageItem, recomputed));
        }

        List<CouponUsage> usages = new ArrayList<>();
        for (ReservedItem reserved : reservedItems) {
            for (AppliedCoupon applied : reserved.result().coupons()) {
                usages.add(CouponUsage.reserve(memberId, request.orderId(), reserved.item(), applied,
                        request.workId(), now));
            }
        }
        // 여러 장을 같은 순서로 잠가 두 예약이 서로를 기다리지 않게 한다
        usages.sort(Comparator.comparingLong(CouponUsage::getMemberCouponId));
        for (CouponUsage usage : usages) {
            casReserve(usage, memberId, now);
        }
        saveUsages(usages);
        processedOperationRepository.saveAndFlush(ProcessedOperation.ofWork(operationId, contentKey,
                CouponUsageSupport.RESULT_RESERVED, null, now));
        log.info("쿠폰을 예약했다 workId={}, orderId={}, memberId={}, memberCouponIds={}", request.workId(),
                request.orderId(), memberId, usages.stream().map(CouponUsage::getMemberCouponId).toList());
        return toResponse(request.workId(), couponUsageRepository
                .findByReservationWorkIdOrderByOrderItemIdAscApplyOrderAsc(request.workId()));
    }

    /**
     * 사용 시도를 쓴다. 같은 발급 쿠폰이 같은 주문 항목에 이미 RESERVED이면(동시 예약 경합) 부분 유일 인덱스에 걸려 사용 불가로
     * 거절한다. 조건부 갱신이 먼저 막으므로 방어다. 해제한 사용 시도는 걸리지 않는다(재예약 허용).
     */
    private void saveUsages(List<CouponUsage> usages) {
        try {
            couponUsageRepository.saveAllAndFlush(usages);
        } catch (DataIntegrityViolationException exception) {
            boolean alreadyReserved = UniqueIndexViolation.constraintOf(exception)
                    .filter(CouponUsageRepository.RESERVED_ORDER_ITEM_MEMBER_COUPON_UNIQUE::equals)
                    .isPresent();
            if (!alreadyReserved) {
                throw exception;
            }
            throw new CouponUsageException(CouponErrorCode.COUPON_NOT_USABLE,
                    "이 주문 항목에 이미 예약 중인 쿠폰입니다.", null);
        }
    }

    /** 요청의 발급 쿠폰 ID. 한 장을 두 번(같은 항목이든 다른 항목이든) 두면 중첩 위반이다 */
    private static List<Long> requireDistinctCoupons(CouponReserveRequest request) {
        Set<Long> seen = new HashSet<>();
        for (CouponReserveRequest.Item item : request.items()) {
            for (CouponReserveRequest.Coupon coupon : item.coupons()) {
                if (!seen.add(coupon.memberCouponId())) {
                    throw new CouponUsageException(CouponErrorCode.COUPON_STACKING_VIOLATION,
                            coupon.memberCouponId());
                }
            }
        }
        return List.copyOf(seen);
    }

    private static void requireBasisMatches(CouponReserveRequest.Item item, ItemResult recomputed) {
        List<RequestedCoupon> requested = item.coupons().stream()
                .map(coupon -> new RequestedCoupon(coupon.memberCouponId(), coupon.expectedGeneration(),
                        coupon.policyVersion(), coupon.applyOrder(), coupon.discount(), coupon.platformAmount(),
                        coupon.sellerAmount()))
                .toList();
        Check check = ReservationBasis.compare(requested, recomputed);
        if (check.matched()) {
            return;
        }
        CouponErrorCode code = switch (check.mismatch()) {
            case GENERATION -> CouponErrorCode.COUPON_GENERATION_MISMATCH;
            case POLICY_VERSION -> CouponErrorCode.COUPON_POLICY_VERSION_CHANGED;
            case AMOUNT -> CouponErrorCode.COUPON_AMOUNT_MISMATCH;
        };
        throw new CouponUsageException(code, check.memberCouponId());
    }

    /** 예약 조건부 갱신. 0행이면 DB를 다시 읽어 원인(사용 불가·세대 불일치)을 가른다. 0행을 성공으로 보지 않는다 */
    private void casReserve(CouponUsage usage, long memberId, Instant now) {
        long expected = usage.getGeneration() - 1;
        int updated = memberCouponRepository.reserve(usage.getMemberCouponId(), memberId, expected,
                usage.getOrderItemId(), now);
        if (updated == 1) {
            return;
        }
        Optional<MemberCouponState> state = memberCouponRepository.findStateById(usage.getMemberCouponId());
        boolean usable = state.isPresent() && state.get().memberId() == memberId
                && state.get().status() == MemberCouponStatus.AVAILABLE && state.get().expiresAt().isAfter(now);
        CouponErrorCode code = usable && state.get().generation() != expected
                ? CouponErrorCode.COUPON_GENERATION_MISMATCH
                : CouponErrorCode.COUPON_NOT_USABLE;
        throw new CouponUsageException(code, usage.getMemberCouponId());
    }

    private CouponReserveResponse replay(ProcessedOperation recorded, String workId, String contentKey) {
        if (!recorded.sameContent(contentKey)) {
            throw new CouponUsageException(CouponErrorCode.COUPON_WORK_CONFLICT);
        }
        if (CouponUsageSupport.RESULT_REJECTED.equals(recorded.getResult())) {
            throw CouponUsageSupport.rejectionOf(recorded.getDetail());
        }
        List<CouponUsage> usages = couponUsageRepository.findByReservationWorkIdOrderByOrderItemIdAscApplyOrderAsc(
                workId);
        // 해제한 예약을 RESERVED로 다시 알려 주면 강의가 쓸 수 없는 할인으로 결제를 이어 갈 수 있다. 재시도는 새 workId로 한다
        if (usages.stream().anyMatch(usage -> usage.getStatus() != CouponUsageStatus.RESERVED)) {
            throw new CouponUsageException(CouponErrorCode.COUPON_WORK_CONFLICT, CouponUsageStatus.RELEASED.name(),
                    null);
        }
        return toResponse(workId, usages);
    }

    /**
     * 예약 응답. 처음 응답과 재응답이 같도록 둘 다 저장한 사용 시도에서 만든다. 그래서 쿠폰이 없는 주문 항목은 응답에 없다.
     */
    static CouponReserveResponse toResponse(String workId, List<CouponUsage> usages) {
        List<CouponReserveResponse.Item> items = CouponUsageSupport.groupByOrderItem(usages).entrySet().stream()
                .map(entry -> {
                    List<CouponUsage> itemUsages = entry.getValue();
                    List<CouponReserveResponse.Coupon> coupons = itemUsages.stream()
                            .map(usage -> new CouponReserveResponse.Coupon(usage.getMemberCouponId(),
                                    usage.getCouponId(), usage.getGeneration(), usage.getApplyOrder(),
                                    usage.getBaseAmount(), usage.getDiscountAmount(), usage.getPlatformAmount(),
                                    usage.getSellerAmount(), usage.getPolicyVersion()))
                            .toList();
                    return new CouponReserveResponse.Item(entry.getKey(),
                            itemUsages.stream().mapToLong(CouponUsage::getDiscountAmount).sum(),
                            itemUsages.stream().mapToLong(CouponUsage::getPlatformAmount).sum(),
                            itemUsages.stream().mapToLong(CouponUsage::getSellerAmount).sum(), coupons);
                })
                .toList();
        return new CouponReserveResponse(workId, CouponUsageSupport.RESULT_RESERVED, items);
    }

    /** 요청 내용 키. 항목·쿠폰의 순서와 무관하다 */
    static String contentKey(CouponReserveRequest request) {
        List<String> parts = request.items().stream()
                .map(item -> UsageContentKey.part(
                        "i" + item.orderItemId() + "|l" + item.lectureId() + "|s" + item.sellerId() + "|p"
                                + item.price(),
                        item.coupons().stream()
                                .map(coupon -> "c" + coupon.memberCouponId() + "|g" + coupon.expectedGeneration()
                                        + "|v" + coupon.policyVersion() + "|a" + coupon.applyOrder() + "|d"
                                        + coupon.discount() + "|f" + coupon.platformAmount() + "|s"
                                        + coupon.sellerAmount())
                                .toList()))
                .toList();
        return UsageContentKey.of(request.memberId(), request.orderId(), parts);
    }

    private record ReservedItem(UsageItem item, ItemResult result) {
    }
}
