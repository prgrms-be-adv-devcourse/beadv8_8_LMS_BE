package com.hapbang.lecture.app;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureErrorCode;
import com.hapbang.lecture.domain.LectureException;
import com.hapbang.lecture.out.coupon.CouponCallResult;
import com.hapbang.lecture.out.coupon.CouponClient;
import com.hapbang.shared.coupon.api.CouponPriceRaiseBlockedResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 강의 정보(제목·소개·가격·썸네일) 수정. 판매자 본인, 삭제 전이면 어느 상태에서든.
 * 강의를 먼저 검사하고 썸네일은 나중에 검사한다. 썸네일이 맞지 않으면 수정 트랜잭션이 롤백되어 수정도 취소된다 (D21)
 * <p>
 * 가격을 올리면 플랫폼 쿠폰이 걸린 강의인지 쿠폰 모듈에 먼저 묻는다(가격 인상 제한). 호출한 Facade가 세 단계로 나눠 부른다.
 * <ol>
 *     <li>{@link #currentPrice}: 짧은 읽기 트랜잭션에서 수정할 수 있는지 검사하고 지금 가격을 읽는다.</li>
 *     <li>{@link #requireRaisable}: 올릴 때만, 트랜잭션 밖에서 쿠폰의 가격 인상 제한을 묻는다. 막혔으면 409, 확인할 수 없으면 503이다
 *     (막는 쪽). 내리거나 같은 가격이면 부르지 않는다.</li>
 *     <li>{@link #updateInfo}: 수정 트랜잭션에서 읽은 가격이 그대로일 때만 바꾼다. 바뀌었으면 409 {@code LECTURE_PRICE_CHANGED}다.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LectureUpdateInfoUseCase {

    private final LectureSupport lectureSupport;
    private final LectureFileSupport lectureFileSupport;
    private final LectureSyncEvents lectureSyncEvents;
    private final CouponClient couponClient;

    /** 수정할 수 있는지 검사하고 지금 가격을 돌려준다 */
    @Transactional(readOnly = true)
    public long currentPrice(Long sellerUserId, Long lectureId) {
        Lecture lecture = lectureSupport.findLecture(lectureId);
        lecture.checkEditableBy(sellerUserId);
        return lecture.getPriceHp();
    }

    /** 가격을 올려도 되는지 쿠폰 모듈에 묻는다. 트랜잭션 밖에서 부른다. 올려도 되지 않거나 확인할 수 없으면 예외다 */
    public void requireRaisable(Long lectureId) {
        switch (couponClient.priceRaiseBlocked(lectureId)) {
            case CouponCallResult.Succeeded<CouponPriceRaiseBlockedResponse> succeeded -> {
                if (succeeded.data().blocked()) {
                    log.info("플랫폼 쿠폰이 걸린 강의라 가격 인상을 막았다 lectureId={}", lectureId);
                    throw new LectureException(LectureErrorCode.LECTURE_PRICE_RAISE_BLOCKED);
                }
            }
            case CouponCallResult.Rejected<CouponPriceRaiseBlockedResponse> rejected
                    when OrderCouponQuoteUseCase.isAuthFailure(rejected.status()) -> {
                log.error(OrderCouponQuoteUseCase.AUTH_FAILED_LOG + " 쿠폰 내부 API가 가격 인상 제한 조회의 인증·권한을 거절해 "
                        + "가격 인상을 막았다. 내부 API 키·설정을 확인한다 lectureId={}, httpStatus={}", lectureId,
                        rejected.status());
                throw new LectureException(LectureErrorCode.LECTURE_PRICE_RAISE_UNAVAILABLE);
            }
            case CouponCallResult.Rejected<CouponPriceRaiseBlockedResponse> rejected -> {
                // 강의 ID만 보내는 읽기라 판매자가 고칠 수 있는 거절이 없다. 계약·설정 문제로 보고 막는 쪽으로 처리한다
                log.error("쿠폰 내부 API가 가격 인상 제한 조회를 거절해 가격 인상을 막았다 lectureId={}, httpStatus={}, couponCode={}",
                        lectureId, rejected.status(), rejected.code());
                throw new LectureException(LectureErrorCode.LECTURE_PRICE_RAISE_UNAVAILABLE);
            }
            case CouponCallResult.NotSent<CouponPriceRaiseBlockedResponse> notSent -> {
                log.warn("가격 인상 제한을 확인하지 못해 가격 인상을 막았다 lectureId={}, reason={}", lectureId,
                        notSent.reason());
                throw new LectureException(LectureErrorCode.LECTURE_PRICE_RAISE_UNAVAILABLE);
            }
            case CouponCallResult.Unknown<CouponPriceRaiseBlockedResponse> unknown -> {
                log.warn("가격 인상 제한 결과를 몰라 가격 인상을 막았다 lectureId={}, httpStatus={}, reason={}", lectureId,
                        unknown.status(), unknown.reason());
                throw new LectureException(LectureErrorCode.LECTURE_PRICE_RAISE_UNAVAILABLE);
            }
        }
    }

    /** 읽은 가격({@code expectedPriceHp})이 그대로일 때만 바꾼다 */
    @Transactional
    public void updateInfo(Long sellerUserId, Long lectureId, String title, String description, long priceHp,
                           Long thumbnailFileId, long expectedPriceHp) {
        Lecture lecture = lectureSupport.findLecture(lectureId);
        lecture.updateInfo(sellerUserId, expectedPriceHp, title, description, priceHp, thumbnailFileId,
                LocalDateTime.now());
        lectureFileSupport.validateThumbnail(sellerUserId, thumbnailFileId);
        lectureSyncEvents.lectureChanged(lecture);
    }
}
