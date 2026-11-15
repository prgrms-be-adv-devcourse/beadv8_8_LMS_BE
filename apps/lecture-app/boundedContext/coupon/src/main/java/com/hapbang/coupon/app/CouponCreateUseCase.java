package com.hapbang.coupon.app;

import java.time.Clock;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.hapbang.coupon.app.dto.CouponCreateCommand;
import com.hapbang.coupon.app.dto.CouponView;
import com.hapbang.coupon.app.port.LectureCatalog.LectureSnapshot;
import com.hapbang.coupon.config.CouponClockConfig;
import com.hapbang.coupon.config.CouponLimitsProperties;
import com.hapbang.coupon.config.CouponPolicyProperties;
import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.CouponTargetType;
import com.hapbang.coupon.domain.CouponTerms;
import com.hapbang.coupon.domain.LectureSaleRule;
import com.hapbang.coupon.global.exception.CouponErrorCode;
import com.hapbang.coupon.global.exception.CouponException;
import com.hapbang.coupon.out.CouponRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 쿠폰 발행(DRAFT). 특정 강의 대상이면 강의 조회 내부 API로 강의를 읽어 가격을 snapshot한다.
 * 강의 조회는 트랜잭션 밖에서 하고, 저장은 그 뒤 저장 한 번(짧은 트랜잭션)이다.
 * 특정 강의 대상은 강의가 판매 중일 때만 발행한다(아니면 409). 판매자 쿠폰은 강의 판매자 확인을 먼저 한다.
 * 만료 상한은 설정({@link CouponPolicyProperties#maxExpiryDays()})을 도메인에 넘기고, 발급 종료 시각이 지난 쿠폰은 도메인이 거절한다(409).
 * 수량이 쿠폰별 상한({@link CouponLimitsProperties#maxQuantityPerCoupon()})을 넘으면 강의를 조회하기 전에 400이다.
 */
@Slf4j
@Service
public class CouponCreateUseCase {

    private final CouponRepository couponRepository;
    private final CouponSupport couponSupport;
    private final CouponPolicyProperties policyProperties;
    private final CouponLimitsProperties limitsProperties;
    private final Clock clock;

    public CouponCreateUseCase(CouponRepository couponRepository, CouponSupport couponSupport,
                               CouponPolicyProperties policyProperties, CouponLimitsProperties limitsProperties,
                               @Qualifier(CouponClockConfig.CLOCK) Clock clock) {
        this.couponRepository = couponRepository;
        this.couponSupport = couponSupport;
        this.policyProperties = policyProperties;
        this.limitsProperties = limitsProperties;
        this.clock = clock;
    }

    /** 판매자 쿠폰 신청. 대상 강의의 판매자 본인이어야 한다 */
    public CouponView createSeller(long sellerId, CouponCreateCommand command) {
        CouponCapacitySupport.requireWithinPerCoupon(command.quantity(), limitsProperties);
        LectureSnapshot lecture = findTargetLecture(command);
        if (lecture != null && lecture.sellerId() != sellerId) {
            throw new CouponException(CouponErrorCode.NOT_LECTURE_SELLER);
        }
        requireOnSale(lecture);
        Coupon coupon = couponRepository.save(Coupon.createSeller(sellerId, terms(command, lecture),
                policyProperties.maxExpiryDays(), clock.instant()));
        log.info("판매자 쿠폰을 신청했다 couponId={}, sellerId={}, lectureId={}", coupon.getId(), sellerId,
                coupon.getLectureId());
        return CouponView.from(coupon);
    }

    /** 플랫폼 쿠폰 발행. 판매자 확인은 하지 않는다 */
    public CouponView createPlatform(long adminId, CouponCreateCommand command) {
        CouponCapacitySupport.requireWithinPerCoupon(command.quantity(), limitsProperties);
        LectureSnapshot lecture = findTargetLecture(command);
        requireOnSale(lecture);
        Coupon coupon = couponRepository.save(Coupon.createPlatform(adminId, terms(command, lecture),
                policyProperties.maxExpiryDays(), clock.instant()));
        log.info("플랫폼 쿠폰을 만들었다 couponId={}, adminId={}, targetType={}, lectureId={}", coupon.getId(), adminId,
                coupon.getTargetType(), coupon.getLectureId());
        return CouponView.from(coupon);
    }

    /** 특정 강의 대상이고 강의를 지정했을 때만 조회한다. 나머지 조합은 도메인 발행 검증이 거절한다 */
    private LectureSnapshot findTargetLecture(CouponCreateCommand command) {
        if (command.targetType() != CouponTargetType.LECTURE || command.lectureId() == null) {
            return null;
        }
        return couponSupport.findLecture(command.lectureId());
    }

    /** 조회한 대상 강의가 있으면 판매 중이어야 한다. 모든 강의 대상(조회 안 함)은 보지 않는다 */
    private static void requireOnSale(LectureSnapshot lecture) {
        if (lecture != null) {
            LectureSaleRule.requireOnSale(lecture.saleStatus());
        }
    }

    private static CouponTerms terms(CouponCreateCommand command, LectureSnapshot lecture) {
        return command.toTerms(lecture == null ? null : lecture.price());
    }
}
