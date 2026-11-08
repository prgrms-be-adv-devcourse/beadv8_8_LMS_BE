package com.hapbang.coupon.app.port;

import java.util.Optional;

/**
 * 강의 조회(강의 앱 내부 API). 판매자 쿠폰의 강의 소유자 확인과 특정 강의 대상 쿠폰의 가격 snapshot에 쓴다.
 * 강의 앱에 닿지 못하면 {@link LectureCatalogUnavailableException}을 던진다.
 */
public interface LectureCatalog {

    /** 강의가 없으면 빈 값 */
    Optional<LectureSnapshot> findLecture(long lectureId);

    /**
     * @param sellerId   판매자 회원 ID
     * @param price      지금 가격(HP)
     * @param saleStatus 강의 앱의 판매 상태 이름(그대로 전달)
     */
    record LectureSnapshot(long lectureId, long sellerId, long price, String saleStatus) {
    }
}
