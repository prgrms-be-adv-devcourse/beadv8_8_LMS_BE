package com.hapbang.coupon.app.port;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * 강의 조회(강의 앱 내부 API). 판매자 쿠폰의 강의 소유자 확인과 특정 강의 대상 쿠폰의 가격 snapshot, 쿠폰 받기 화면의 강의 제목·판매 중
 * 거르기에 쓴다. 강의 앱에 닿지 못하면 {@link LectureCatalogUnavailableException}을 던진다.
 */
public interface LectureCatalog {

    /** 강의가 없으면 빈 값 */
    Optional<LectureSnapshot> findLecture(long lectureId);

    /**
     * 강의 여러 개의 제목·판매 상태. 없거나 삭제된 강의는 결과에 없다(판매 상태로는 거르지 않는다). 한 강의라도 확인하지 못하면 전체가
     * {@link LectureCatalogUnavailableException}이다(일부만 돌려주지 않는다).
     *
     * @return 강의 ID → 강의
     */
    Map<Long, LectureSummary> findLectures(Collection<Long> lectureIds);

    /**
     * @param sellerId   판매자 회원 ID
     * @param price      지금 가격(HP)
     * @param saleStatus 강의 앱의 판매 상태 이름(그대로 전달)
     */
    record LectureSnapshot(long lectureId, long sellerId, long price, String saleStatus) {
    }

    /**
     * @param title      지금 강의 제목
     * @param saleStatus 강의 앱의 판매 상태 이름(그대로 전달)
     */
    record LectureSummary(long lectureId, String title, String saleStatus) {
    }
}
