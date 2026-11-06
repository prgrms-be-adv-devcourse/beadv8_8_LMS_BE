package com.hapbang.coupon.app;

import java.time.Clock;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.hapbang.coupon.app.dto.CouponCreateCommand;
import com.hapbang.coupon.app.dto.CouponView;
import com.hapbang.coupon.app.port.LectureCatalog.LectureSnapshot;
import com.hapbang.coupon.config.CouponClockConfig;
import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.CouponTargetType;
import com.hapbang.coupon.domain.CouponTerms;
import com.hapbang.coupon.global.exception.CouponErrorCode;
import com.hapbang.coupon.global.exception.CouponException;
import com.hapbang.coupon.out.CouponRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 쿠폰 발행(DRAFT). 특정 강의 대상이면 강의 조회 내부 API로 강의를 읽어 가격을 snapshot한다.
 * 강의 조회는 트랜잭션 밖에서 하고, 저장은 그 뒤 저장 한 번(짧은 트랜잭션)이다.
 * 강의 판매 상태는 보지 않는다(판매 중이 아닌 강의에 발행할 수 있는지는 정해지지 않았다).
 */
@Slf4j
@Service
public class CouponCreateUseCase {

    private final CouponRepository couponRepository;
    private final CouponSupport couponSupport;
    private final Clock clock;

    public CouponCreateUseCase(CouponRepository couponRepository, CouponSupport couponSupport,
                               @Qualifier(CouponClockConfig.CLOCK) Clock clock) {
        this.couponRepository = couponRepository;
        this.couponSupport = couponSupport;
        this.clock = clock;
    }

    /** 판매자 쿠폰 신청. 대상 강의의 판매자 본인이어야 한다 */
    public CouponView createSeller(long sellerId, CouponCreateCommand command) {
        LectureSnapshot lecture = findTargetLecture(command);
        if (lecture != null && lecture.sellerId() != sellerId) {
            throw new CouponException(CouponErrorCode.NOT_LECTURE_SELLER);
        }
        Coupon coupon = couponRepository.save(Coupon.createSeller(sellerId, terms(command, lecture), clock.instant()));
        log.info("판매자 쿠폰을 신청했다 couponId={}, sellerId={}, lectureId={}", coupon.getId(), sellerId,
                coupon.getLectureId());
        return CouponView.from(coupon);
    }

    /** 플랫폼 쿠폰 발행. 판매자 확인은 하지 않는다 */
    public CouponView createPlatform(long adminId, CouponCreateCommand command) {
        LectureSnapshot lecture = findTargetLecture(command);
        Coupon coupon = couponRepository.save(Coupon.createPlatform(adminId, terms(command, lecture), clock.instant()));
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

    private static CouponTerms terms(CouponCreateCommand command, LectureSnapshot lecture) {
        return command.toTerms(lecture == null ? null : lecture.price());
    }
}
