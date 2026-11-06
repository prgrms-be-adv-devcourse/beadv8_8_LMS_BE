package com.hapbang.coupon.out.lecture;

import java.util.Optional;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import com.hapbang.coupon.app.port.LectureCatalog;
import com.hapbang.coupon.app.port.LectureCatalogUnavailableException;
import com.hapbang.coupon.out.lecture.CouponLectureApiConfig.LectureNotFoundResponse;
import com.hapbang.shared.lecture.dto.LectureLookupDto;

import lombok.RequiredArgsConstructor;

/**
 * 강의 조회 내부 HTTP API로 강의를 읽는다. 쿠폰 전용 HTTP Interface({@link CouponLectureInternalApi})로 부른다.
 * <p>
 * 강의 없음(404 {@code LECTURE_NOT_FOUND})은 빈 값이다. 그 밖의 오류 상태는 프록시 설정이 {@link LectureCatalogUnavailableException}으로
 * 바꾸고, 여기서는 연결 실패·시간 초과와 성공 상태인데 쓸 수 없는 응답(본문 없음·{@code data} 없음·깨진 JSON)을 같은 예외로 바꾼다.
 * 읽기만 하므로 실패하면 그대로 다시 불러도 안전하다.
 */
@Component
@RequiredArgsConstructor
public class HttpLectureCatalog implements LectureCatalog {

    private final CouponLectureInternalApi lectureInternalApi;

    @Override
    public Optional<LectureSnapshot> findLecture(long lectureId) {
        ResponseEntity<CouponLectureInternalApi.Envelope> response;
        try {
            response = lectureInternalApi.getLecture(lectureId);
        } catch (LectureNotFoundResponse notFound) {
            return Optional.empty();
        } catch (LectureCatalogUnavailableException e) {
            throw e;
        } catch (RestClientException e) {
            // 오류 상태는 프록시가 먼저 바꾸므로 여기 오는 것은 연결 실패·시간 초과나 성공 상태의 읽을 수 없는 본문이다
            throw new LectureCatalogUnavailableException("강의 조회 내부 API를 부르지 못했거나 응답을 읽지 못했습니다.", e);
        }
        CouponLectureInternalApi.Envelope body = response.getBody();
        if (body == null || body.data() == null) {
            throw new LectureCatalogUnavailableException("강의 조회 내부 API 응답에 data가 없습니다.", null);
        }
        LectureLookupDto lecture = body.data();
        return Optional.of(new LectureSnapshot(lecture.lectureId(), lecture.sellerId(), lecture.price(),
                lecture.saleStatus()));
    }
}
