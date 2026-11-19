package com.hapbang.coupon.out.lecture;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

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
 * 강의 없음(하나는 404 {@code LECTURE_NOT_FOUND}, 일괄은 값 {@code null})은 빈 값이다. 그 밖의 오류 상태는 프록시 설정이
 * {@link LectureCatalogUnavailableException}으로 바꾸고, 여기서는 연결 실패·시간 초과와 성공 상태인데 쓸 수 없는 응답(본문 없음·{@code data}
 * 없음·깨진 JSON·일괄 응답에 요청한 강의 키가 없음)을 같은 예외로 바꾼다. 읽기만 하므로 실패하면 그대로 다시 불러도 안전하다(여기서 다시
 * 보내지는 않는다).
 * <p>
 * 일괄 조회는 {@link LectureLookupDto#MAX_BATCH_SIZE}개씩 나눠 차례로 부르고, 하나라도 실패하면 전체가 조회 불가다(일부만 돌려주지 않는다).
 * <p>
 * 호출(일괄은 나눈 요청마다)은 circuit breaker 안에서 한다. 하나 조회는 {@code lecture}, 일괄 조회는 {@code lecture-batch}이고 설정은
 * 같다({@link CouponLectureClientProperties}). 일괄 조회는 쿠폰 받기 화면 목록만 쓰며 요청이 가장 많고 무거운 호출이라, 그 실패·지연으로
 * 열린 회로가 주문의 쿠폰 계산·쿠폰 발행이 쓰는 하나 조회까지 막지 않게 회로를 나눈다.
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
    static final String BATCH_CIRCUIT_NAME = "lecture-batch";

    private final CouponLectureInternalApi lectureInternalApi;
    private final CircuitBreaker circuitBreaker;
    private final CircuitBreaker batchCircuitBreaker;

    public HttpLectureCatalog(CouponLectureInternalApi lectureInternalApi, CouponLectureClientProperties properties) {
        this.lectureInternalApi = lectureInternalApi;
        this.circuitBreaker = circuit(CIRCUIT_NAME, properties);
        this.batchCircuitBreaker = circuit(BATCH_CIRCUIT_NAME, properties);
    }

    private static CircuitBreaker circuit(String name, CouponLectureClientProperties properties) {
        return CircuitBreaker.of(name, CircuitBreakerConfig.custom()
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
        return inCircuit(circuitBreaker, () -> fetch(lectureId));
    }

    @Override
    public Map<Long, LectureSummary> findLectures(Collection<Long> lectureIds) {
        List<Long> ids = List.copyOf(new LinkedHashSet<>(lectureIds));
        Map<Long, LectureSummary> lectures = new HashMap<>();
        for (int from = 0; from < ids.size(); from += LectureLookupDto.MAX_BATCH_SIZE) {
            List<Long> batch = ids.subList(from, Math.min(from + LectureLookupDto.MAX_BATCH_SIZE, ids.size()));
            lectures.putAll(inCircuit(batchCircuitBreaker, () -> fetchAll(batch)));
        }
        return lectures;
    }

    private static <T> T inCircuit(CircuitBreaker circuit, Supplier<T> call) {
        try {
            return circuit.executeSupplier(call);
        } catch (CallNotPermittedException open) {
            throw new LectureCatalogUnavailableException(
                    "강의 조회 회로(" + circuit.getName() + ")가 열려 있어 강의 조회 내부 API를 부르지 않았습니다.", open);
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

    private Map<Long, LectureSummary> fetchAll(List<Long> lectureIds) {
        ResponseEntity<CouponLectureInternalApi.BatchEnvelope> response;
        try {
            response = lectureInternalApi.getLectures(lectureIds);
        } catch (LectureNotFoundResponse notFound) {
            // 일괄 조회는 강의 없음을 값 null로 답하므로 계약에 없는 응답이다. 강의 앱이 판단한 4xx라 회로 실패로 세지 않는다
            throw new LectureRejectedResponse(404, CouponLectureApiConfig.LECTURE_NOT_FOUND_CODE);
        } catch (LectureCatalogUnavailableException e) {
            throw e;
        } catch (RestClientException e) {
            throw new LectureCatalogUnavailableException("강의 일괄 조회 내부 API를 부르지 못했거나 응답을 읽지 못했습니다.", e);
        }
        CouponLectureInternalApi.BatchEnvelope body = response.getBody();
        if (body == null || body.data() == null) {
            throw new LectureCatalogUnavailableException("강의 일괄 조회 내부 API 응답에 data가 없습니다.", null);
        }
        Map<Long, LectureSummary> lectures = new HashMap<>();
        for (Long lectureId : lectureIds) {
            // 요청한 ID마다 키가 있어야 한다. 빠졌으면 없는 강의로 보지 않는다(조회 불가를 강의 없음으로 바꾸지 않는다)
            if (!body.data().containsKey(lectureId)) {
                throw new LectureCatalogUnavailableException(
                        "강의 일괄 조회 내부 API 응답에 요청한 강의가 없습니다 lectureId=" + lectureId, null);
            }
            LectureLookupDto lecture = body.data().get(lectureId);
            if (lecture != null) {
                lectures.put(lectureId, new LectureSummary(lectureId, lecture.title(), lecture.saleStatus()));
            }
        }
        return lectures;
    }

    /** 강의 앱이 처리하지 못했거나 결과를 모르는 실패인지(5xx·시간 초과·연결 실패·읽을 수 없는 응답). 4xx는 넣지 않는다 */
    static boolean unavailable(Throwable exception) {
        return exception instanceof LectureCatalogUnavailableException
                && !(exception instanceof LectureRejectedResponse);
    }
}
