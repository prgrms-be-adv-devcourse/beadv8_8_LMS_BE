package com.hapbang.coupon.app;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import com.hapbang.coupon.config.CouponCalculateProperties;
import com.hapbang.coupon.global.exception.CouponErrorCode;
import com.hapbang.coupon.global.exception.CouponException;
import com.hapbang.shared.coupon.api.CouponCalculateRequest;
import com.hapbang.shared.coupon.api.CouponCalculateResponse;
import com.hapbang.shared.coupon.api.CouponPriceRaiseBlockedResponse;
import com.hapbang.shared.coupon.api.CouponReleaseRequest;
import com.hapbang.shared.coupon.api.CouponReleaseResponse;
import com.hapbang.shared.coupon.api.CouponReservationStatusResponse;
import com.hapbang.shared.coupon.api.CouponReserveRequest;
import com.hapbang.shared.coupon.api.CouponReserveResponse;

import lombok.RequiredArgsConstructor;

/**
 * 쿠폰 사용 내부 API(강의 → 쿠폰) 진입점. 계산·예약·해제·예약 상태·가격 인상 제한.
 * 요청 모양(작업 ID 형식, 항목 수, 같은 강의 중복)을 여기서 검사하고, 쿠폰 규칙은 각 UseCase가 검사한다.
 * 필수 값·빈 목록 같은 구조 검사는 요청 record가 한다.
 */
@Service
@RequiredArgsConstructor
// 내부 API 보안 체인(lecture-app InternalApiSecurityConfig)이 키를 검사하고, 회원 로그인이 이 진입점에 오지 못하게 한 번 더 막는다
@PreAuthorize("hasRole('INTERNAL')")
public class CouponInternalFacade {

    /** 작업 ID 형식: 1~100자 영문·숫자·{@code :_-} */
    private static final Pattern WORK_ID = Pattern.compile("[A-Za-z0-9:_-]{1,100}");

    private final CouponCalculateUseCase couponCalculateUseCase;
    private final CouponReserveUseCase couponReserveUseCase;
    private final CouponReleaseUseCase couponReleaseUseCase;
    private final CouponReservationStatusQuery couponReservationStatusQuery;
    private final CouponPriceRaiseBlockedQuery couponPriceRaiseBlockedQuery;
    private final CouponCalculateProperties calculateProperties;

    public CouponCalculateResponse calculate(CouponCalculateRequest request) {
        checkItems(request.items().stream().map(CouponCalculateRequest.Item::lectureId).toList());
        return couponCalculateUseCase.calculate(request);
    }

    public CouponReserveResponse reserve(CouponReserveRequest request) {
        checkWorkId(request.workId());
        checkItems(request.items().stream().map(CouponReserveRequest.Item::lectureId).toList());
        return couponReserveUseCase.reserve(request);
    }

    public CouponReleaseResponse release(CouponReleaseRequest request) {
        checkWorkId(request.workId());
        return couponReleaseUseCase.release(request);
    }

    public CouponReservationStatusResponse reservationStatus(String workId, long memberId, long orderId) {
        checkWorkId(workId);
        return couponReservationStatusQuery.status(workId, memberId, orderId);
    }

    public CouponPriceRaiseBlockedResponse priceRaiseBlocked(long lectureId) {
        return couponPriceRaiseBlockedQuery.blocked(lectureId);
    }

    private static void checkWorkId(String workId) {
        if (workId == null || !WORK_ID.matcher(workId).matches()) {
            throw new CouponException(CouponErrorCode.INVALID_WORK_ID);
        }
    }

    /** 항목 수 상한과 한 주문 안 같은 강의 중복을 검사한다. 강의 조회를 강의마다 부르므로 상한은 그 비용의 상한이기도 하다 */
    private void checkItems(List<Long> lectureIds) {
        if (lectureIds.size() > calculateProperties.maxItems()) {
            throw new CouponException(CouponErrorCode.COUPON_TOO_MANY_ITEMS,
                    "한 번에 계산할 수 있는 강의는 %d개까지입니다.".formatted(calculateProperties.maxItems()));
        }
        Set<Long> seen = new HashSet<>();
        for (Long lectureId : lectureIds) {
            if (!seen.add(lectureId)) {
                throw new CouponException(CouponErrorCode.COUPON_DUPLICATE_LECTURE,
                        "강의 %d가 두 번 들어 있습니다.".formatted(lectureId));
            }
        }
    }
}
