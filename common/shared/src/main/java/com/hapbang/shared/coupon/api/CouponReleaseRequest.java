package com.hapbang.shared.coupon.api;

import java.util.List;
import java.util.Objects;

/**
 * 쿠폰 해제 내부 API({@code POST /api/v1/internal/coupons/releases})의 요청. 강의가 주문 실패·보상 뒤 예약한 쿠폰을 돌려줄 때 보낸다.
 * 쿠폰 모듈은 나열한 사용 시도를 한 트랜잭션에서 모두 해제하거나 하나도 해제하지 않는다.
 * 이 record는 필수 값·빈 목록·문자열 길이 같은 요청 구조만 검사한다. workId 형식·상태 규칙은 쿠폰 모듈이 검사한다.
 * 요청에 회원 ID가 없다. 쿠폰 모듈은 작업 내용 비교에 해제할 사용 시도에 저장된 회원 ID를 쓴다.
 *
 * @param orderId         주문 ID
 * @param workId          해제 작업 ID(멱등 키). 1~100자 {@code [A-Za-z0-9:_-]}
 * @param usages          해제할 사용 시도 목록
 * @param reason          해제 사유. {@value #MAX_REASON_LENGTH}자 이하
 * @param compensationRef 보상 완료 근거(선택). {@value #MAX_COMPENSATION_REF_LENGTH}자 이하. 지금은 기록만 하고 검증하지 않는다
 */
public record CouponReleaseRequest(
        Long orderId,
        String workId,
        List<Usage> usages,
        String reason,
        String compensationRef
) {

    /** {@code reason} 최대 길이(저장 컬럼 길이) */
    public static final int MAX_REASON_LENGTH = 500;
    /** {@code compensationRef} 최대 길이(저장 컬럼 길이) */
    public static final int MAX_COMPENSATION_REF_LENGTH = 200;

    public CouponReleaseRequest {
        Objects.requireNonNull(orderId, "orderId는 필수입니다.");
        Objects.requireNonNull(workId, "workId는 필수입니다.");
        Objects.requireNonNull(usages, "usages는 필수입니다.");
        Objects.requireNonNull(reason, "reason은 필수입니다.");
        if (reason.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("reason은 " + MAX_REASON_LENGTH + "자 이하여야 합니다.");
        }
        if (compensationRef != null && compensationRef.length() > MAX_COMPENSATION_REF_LENGTH) {
            throw new IllegalArgumentException(
                    "compensationRef는 " + MAX_COMPENSATION_REF_LENGTH + "자 이하여야 합니다.");
        }
        if (usages.isEmpty()) {
            throw new IllegalArgumentException("usages는 한 개 이상이어야 합니다.");
        }
        usages = List.copyOf(usages);
    }

    /**
     * 해제할 사용 시도 하나.
     *
     * @param memberCouponId 발급 쿠폰 ID
     * @param generation     예약 응답의 reservedGeneration
     */
    public record Usage(
            Long memberCouponId,
            Long generation
    ) {

        public Usage {
            Objects.requireNonNull(memberCouponId, "memberCouponId는 필수입니다.");
            Objects.requireNonNull(generation, "generation은 필수입니다.");
        }
    }
}
