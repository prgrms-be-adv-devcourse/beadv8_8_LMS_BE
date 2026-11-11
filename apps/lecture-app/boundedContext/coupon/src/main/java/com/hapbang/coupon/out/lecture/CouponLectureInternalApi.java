package com.hapbang.coupon.out.lecture;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

import com.hapbang.shared.lecture.dto.LectureLookupDto;

/**
 * 강의 조회 내부 HTTP API의 쿠폰 전용 HTTP Interface. 프록시는 {@link CouponLectureApiConfig}가 만든다(주소·내부 API 키 헤더·오류 변환).
 * 응답은 강의 앱의 성공 봉투(RsData)다.
 */
@HttpExchange(CouponLectureInternalApi.LECTURES_PATH)
public interface CouponLectureInternalApi {

    String LECTURES_PATH = "/api/v1/internal/lectures";

    /** 강의 하나의 판매자 ID·가격·판매 상태. 없거나 삭제된 강의는 404 {@code LECTURE_NOT_FOUND} */
    @GetExchange("/{lectureId}")
    ResponseEntity<Envelope> getLecture(@PathVariable("lectureId") long lectureId);

    /** 강의 성공 응답(RsData)의 모양. 쿠폰은 강의 global에 의존하지 않아 같은 필드로 읽는다 */
    record Envelope(Integer statusCode, String message, LectureLookupDto data) {
    }
}
