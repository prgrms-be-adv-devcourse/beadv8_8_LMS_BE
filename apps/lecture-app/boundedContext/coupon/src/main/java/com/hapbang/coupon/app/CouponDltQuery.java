package com.hapbang.coupon.app;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.coupon.app.dto.CouponDltLogView;
import com.hapbang.coupon.domain.CouponDltKind;
import com.hapbang.coupon.domain.CouponDltLog;
import com.hapbang.coupon.domain.CouponDltStatus;
import com.hapbang.coupon.global.exception.CouponErrorCode;
import com.hapbang.coupon.global.exception.CouponException;
import com.hapbang.coupon.out.CouponDltLogRepository;

import lombok.RequiredArgsConstructor;

/** DLT 격리 기록 조회(관리자). 상태를 주면 그 상태만 */
@Service
@RequiredArgsConstructor
public class CouponDltQuery {

    private final CouponDltLogRepository couponDltLogRepository;

    @Transactional(readOnly = true)
    public Page<CouponDltLogView> list(CouponDltStatus status, Pageable pageable) {
        return (status == null ? couponDltLogRepository.findAll(pageable)
                : couponDltLogRepository.findByStatus(status, pageable)).map(CouponDltLogView::from);
    }

    /** 기록을 남긴 소비자. 관리자 재처리·영구 거절을 가를 때 쓴다. 없으면 404 */
    @Transactional(readOnly = true)
    public CouponDltKind kindOf(long dltId) {
        return couponDltLogRepository.findById(dltId).map(CouponDltLog::getKind)
                .orElseThrow(() -> new CouponException(CouponErrorCode.COUPON_DLT_NOT_FOUND));
    }
}
