package com.hapbang.coupon.out.lecture;

import java.util.Optional;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import com.hapbang.coupon.app.port.LectureCatalog;
import com.hapbang.coupon.app.port.LectureCatalogUnavailableException;
import com.hapbang.coupon.out.lecture.CouponLectureApiConfig.LectureNotFoundResponse;
import com.hapbang.coupon.out.lecture.CouponLectureApiConfig.LectureRejectedResponse;
import com.hapbang.shared.lecture.dto.LectureLookupDto;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;

/**
 * 강의 조회 내부 HTTP API로 강의를 읽는다. 쿠폰 전용 HTTP Interface({@link CouponLectureInternalApi})로 부른다.
 * <p>
 * 강의 없음(404 {@code LECTURE_NOT_FOUND})은 빈 값이다. 그 밖의 오류 상태는 프록시 설정이 {@link LectureCatalogUnavailableException}으로
 * 바꾸고, 여기서는 연결 실패·시간 초과와 성공 상태인데 쓸 수 없는 응답(본문 없음·{@code data} 없음·깨진 JSON)을 같은 예외로 바꾼다.
 * 읽기만 하므로 실패하면 그대로 다시 불러도 안전하다(여기서 다시 보내지는 않는다).
 * <p>
 * 호출은 circuit breaker({@code lecture}, 설정 {@link CouponLectureClientProperties}) 안에서 한다.
 * <ul>
 *     <li>5xx·시간 초과·연결 실패·읽을 수 없는 응답만 실패로 센다. 강의 없음과 그 밖의 4xx는 강의 앱이 판단한 응답이라 성공으로 센다
 *     (지갑·회원의 나가는 호출 회로와 같은 기준).</li>
 *     <li>회로가 열려 있으면 HTTP를 부르지 않고 {@link LectureCatalogUnavailableException}을 던진다.
 *     열린 회로는 시간이 지나면 스스로 HALF_OPEN이 된다.</li>
 * </ul>
 */
@Component
public class HttpLectureCatalog implements LectureCatalog {

    static final String CIRCUIT_NAME = "lecture";

    private final CouponLectureInternalApi lectureInternalApi;
    private final CircuitBreaker circuitBreaker;

    public HttpLectureCatalog(CouponLectureInternalApi lectureInternalApi, CouponLectureClientProperties properties) {
        this.lectureInternalApi = lectureInternalApi;
        this.circuitBreaker = CircuitBreaker.of(CIRCUIT_NAME, CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(properties.slidingWindowSize())
                .minimumNumberOfCalls(properties.minimumNumberOfCalls())
                .failureRateThreshold(properties.failureRateThreshold())
                .slowCallDurationThreshold(properties.slowCallDuration())
                .slowCallRateThreshold(properties.slowCallRateThreshold())
                .waitDurationInOpenState(properties.openDuration())
                .permittedNumberOfCallsInHalfOpenState(properties.halfOpenCalls())
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordException(HttpLectureCatalog::unavailable)
                .build());
    }

    @Override
    public Optional<LectureSnapshot> findLecture(long lectureId) {
        try {
            return circuitBreaker.executeSupplier(() -> fetch(lectureId));
        } catch (CallNotPermittedException open) {
            throw new LectureCatalogUnavailableException("강의 조회 회로가 열려 있어 강의 조회 내부 API를 부르지 않았습니다.", open);
        }
    }

    private Optional<LectureSnapshot> fetch(long lectureId) {
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

    /** 강의 앱이 처리하지 못했거나 결과를 모르는 실패인지(5xx·시간 초과·연결 실패·읽을 수 없는 응답). 4xx는 넣지 않는다 */
    static boolean unavailable(Throwable exception) {
        return exception instanceof LectureCatalogUnavailableException
                && !(exception instanceof LectureRejectedResponse);
    }

    /** 테스트에서 회로 상태를 확인할 때 쓴다 */
    CircuitBreaker circuitBreaker() {
        return circuitBreaker;
    }
}
