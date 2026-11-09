package com.hapbang.coupon.app;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.coupon.config.CouponCalculateProperties;
import com.hapbang.coupon.domain.CouponDomainError;
import com.hapbang.coupon.domain.CouponUsage;
import com.hapbang.coupon.domain.MemberCouponStatus;
import com.hapbang.coupon.domain.ProcessedOperation;
import com.hapbang.coupon.domain.usage.CouponCalculator;
import com.hapbang.coupon.domain.usage.CouponUsageViolationException;
import com.hapbang.coupon.domain.usage.UsableCoupon;
import com.hapbang.coupon.global.exception.CouponErrorCode;
import com.hapbang.coupon.global.exception.CouponUsageException;
import com.hapbang.coupon.out.MemberCouponRepository;
import com.hapbang.coupon.out.OwnedCoupon;
import com.hapbang.coupon.out.ProcessedOperationRepository;

/**
 * 쿠폰 사용(계산·예약·해제·예약 상태 조회)이 함께 쓰는 검사·조회·작업 기록.
 * <p>
 * 작업 기록은 {@code processed_operation}에 둔다. 작업 ID는 예약 {@code coupon-reserve:{workId}}, 해제
 * {@code coupon-release:{workId}}이고 UNIQUE라 같은 작업의 동시 기록은 하나만 남는다.
 */
@Component
public class CouponUsageSupport {

    static final String RESERVE_OPERATION_PREFIX = "coupon-reserve:";
    static final String RELEASE_OPERATION_PREFIX = "coupon-release:";

    /** 작업 기록 결과 값 */
    static final String RESULT_RESERVED = "RESERVED";
    static final String RESULT_REJECTED = "REJECTED";
    static final String RESULT_RELEASED = "RELEASED";

    /** 거절 기록 detail에서 거절 코드와 발급 쿠폰 ID를 가르는 문자 */
    private static final char DETAIL_SEPARATOR = ':';

    private final CouponCalculator calculator = new CouponCalculator();
    private final CouponCalculateProperties properties;
    private final MemberCouponRepository memberCouponRepository;
    private final ProcessedOperationRepository processedOperationRepository;
    private final TransactionTemplate newTransaction;

    public CouponUsageSupport(CouponCalculateProperties properties, MemberCouponRepository memberCouponRepository,
                              ProcessedOperationRepository processedOperationRepository,
                              PlatformTransactionManager transactionManager) {
        this.properties = properties;
        this.memberCouponRepository = memberCouponRepository;
        this.processedOperationRepository = processedOperationRepository;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    CouponCalculator calculator() {
        return calculator;
    }

    int maxSearchNodes() {
        return properties.maxSearchNodes();
    }

    /** 항목 수 상한과 같은 강의 중복을 본다. 넘거나 겹치면 400 */
    void requireItems(Collection<Long> lectureIds) {
        if (lectureIds.size() > properties.maxItems()) {
            throw new CouponUsageException(CouponErrorCode.COUPON_TOO_MANY_ITEMS,
                    "한 번에 %d개 강의까지 처리할 수 있습니다.".formatted(properties.maxItems()), null);
        }
        Set<Long> seen = new HashSet<>();
        for (Long lectureId : lectureIds) {
            if (!seen.add(lectureId)) {
                throw new CouponUsageException(CouponErrorCode.COUPON_DUPLICATE_LECTURE,
                        "같은 강의가 두 번 들어 있습니다: lectureId=" + lectureId, null);
            }
        }
    }

    /**
     * 이 발급 쿠폰들을 쿠폰 정책과 함께 읽는다(소유·상태와 관계없이. 판정은 계산기가 한다). 없는 ID는 결과에 없다.
     * 결과는 발급 쿠폰 ID 순서다.
     */
    Map<Long, UsableCoupon> loadCoupons(Collection<Long> memberCouponIds) {
        if (memberCouponIds.isEmpty()) {
            return Map.of();
        }
        return memberCouponRepository.findOwnedByIds(Set.copyOf(memberCouponIds)).stream()
                .map(owned -> UsableCoupon.of(owned.memberCoupon(), owned.coupon()))
                .sorted((left, right) -> Long.compare(left.memberCouponId(), right.memberCouponId()))
                .collect(Collectors.toMap(UsableCoupon::memberCouponId, Function.identity(), (left, right) -> left,
                        LinkedHashMap::new));
    }

    /** 회원이 가진 AVAILABLE 발급 쿠폰 전부(만료 여부는 계산기가 본다). 추천 후보 */
    List<UsableCoupon> availableCoupons(long memberId) {
        List<UsableCoupon> coupons = new ArrayList<>();
        for (OwnedCoupon owned : memberCouponRepository.findOwnedByStatus(memberId, MemberCouponStatus.AVAILABLE)) {
            coupons.add(UsableCoupon.of(owned.memberCoupon(), owned.coupon()));
        }
        return coupons;
    }

    /** 도메인 사용 규칙 위반을 쿠폰 사용 실패(409, 실패한 발급 쿠폰 ID)로 바꾼다 */
    static CouponUsageException toUsageException(CouponUsageViolationException violation) {
        return new CouponUsageException(errorCodeOf(violation.getError()), violation.getMemberCouponId());
    }

    private static CouponErrorCode errorCodeOf(CouponDomainError error) {
        return switch (error) {
            case COUPON_NOT_APPLICABLE -> CouponErrorCode.COUPON_NOT_APPLICABLE;
            case COUPON_NOT_USABLE -> CouponErrorCode.COUPON_NOT_USABLE;
            case COUPON_STACKING_VIOLATION -> CouponErrorCode.COUPON_STACKING_VIOLATION;
            default -> throw new IllegalStateException("쿠폰 사용 규칙 위반이 아닌 도메인 오류입니다: " + error);
        };
    }

    Optional<ProcessedOperation> findOperation(String operationId) {
        return processedOperationRepository.findByOperationId(operationId);
    }

    /**
     * 예약 거절을 별도 트랜잭션으로 기록한다(예약 트랜잭션은 이미 롤백됐다). 같은 작업 ID가 먼저 기록되어 있으면(동시 요청)
     * 기록하지 않고 그 기록을 돌려준다.
     *
     * @return 이번에 남긴 기록이면 빈 값, 이미 있던 기록이면 그 기록
     */
    Optional<ProcessedOperation> recordRejection(String operationId, String contentKey,
                                                 CouponUsageException rejection, Instant now) {
        Optional<ProcessedOperation> existing = findOperation(operationId);
        if (existing.isPresent()) {
            return existing;
        }
        try {
            newTransaction.executeWithoutResult(status -> processedOperationRepository.saveAndFlush(
                    ProcessedOperation.ofWork(operationId, contentKey, RESULT_REJECTED, rejectionDetail(rejection), now)));
            return Optional.empty();
        } catch (DataIntegrityViolationException exception) {
            if (!isOperationIdViolation(exception)) {
                throw exception;
            }
            return Optional.of(findOperation(operationId).orElseThrow(() -> exception));
        }
    }

    /**
     * 거절 기록의 detail: {@code 거절 코드} 또는 실패한 발급 쿠폰이 있으면 {@code 거절 코드:발급 쿠폰 ID}. 같은 작업 ID 재시도에 같은 코드와
     * 같은 발급 쿠폰 ID로 거절하려고 함께 남긴다.
     */
    static String rejectionDetail(CouponUsageException rejection) {
        Long memberCouponId = rejection.getMemberCouponId();
        return memberCouponId == null
                ? rejection.getErrorCode().getCode()
                : rejection.getErrorCode().getCode() + DETAIL_SEPARATOR + memberCouponId;
    }

    /** 거절 기록의 detail로 처음과 같은 거절을 만든다 */
    static CouponUsageException rejectionOf(String detail) {
        int separator = detail.indexOf(DETAIL_SEPARATOR);
        if (separator < 0) {
            return new CouponUsageException(CouponErrorCode.valueOf(detail));
        }
        return new CouponUsageException(CouponErrorCode.valueOf(detail.substring(0, separator)),
                Long.valueOf(detail.substring(separator + 1)));
    }

    /** 거절 기록의 detail에서 거절 코드만 */
    static String rejectCodeOf(String detail) {
        int separator = detail.indexOf(DETAIL_SEPARATOR);
        return separator < 0 ? detail : detail.substring(0, separator);
    }

    /** 작업 ID UNIQUE 위반인가(같은 작업을 동시에 기록했다) */
    static boolean isOperationIdViolation(DataIntegrityViolationException exception) {
        return UniqueIndexViolation.constraintOf(exception)
                .filter(ProcessedOperationRepository.OPERATION_ID_UNIQUE::equals)
                .isPresent();
    }

    /** 사용 시도를 주문 항목별로 묶는다. 입력 순서(주문 항목 → 적용 순서)를 지킨다 */
    static Map<Long, List<CouponUsage>> groupByOrderItem(List<CouponUsage> usages) {
        return usages.stream().collect(Collectors.groupingBy(CouponUsage::getOrderItemId, LinkedHashMap::new,
                Collectors.toList()));
    }
}
