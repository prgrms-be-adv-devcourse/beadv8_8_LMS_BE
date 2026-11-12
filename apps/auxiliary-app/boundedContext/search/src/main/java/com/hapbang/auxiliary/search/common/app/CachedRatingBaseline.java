package com.hapbang.auxiliary.search.common.app;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * 베이지안 평균 (v×R + m×C)/(v+m)의 C(보이는 문서의 평점 전체 평균, 리뷰 수 가중)를 pod 메모리에 두는 캐시(#771, 공통 코드 #883).
 * <p>
 * 주기({@code ttl})가 지나면 다음 {@link #get()} 때 ES 집계로 다시 구한다. 구하지 못하면 1분 동안 다시 묻지 않는다. 집계를 한 pod에서만 돌려
 * 다른 pod에 값을 줄 수 없어 pod마다 따로 구한다(#771 Q8). 리뷰가 하나도 없으면 0이다.
 * <p>
 * 대상은 생성자만 둔 얇은 하위 클래스로 빈을 만든다(인플루언서 {@code RatingBaseline}, 강의 {@code LectureRatingBaseline}, #868 R7과 같은 방식).
 */
public class CachedRatingBaseline {

    /** 구하지 못하면 이만큼 다시 묻지 않는다 */
    public static final Duration RETRY_AFTER_FAILURE = Duration.ofMinutes(1);

    private final Supplier<Double> source;
    private final Duration ttl;
    private final Clock clock;
    private volatile Double value;
    /** 이 시각 전에는 ES에 묻지 않는다(성공하면 주기 뒤, 실패하면 1분 뒤) */
    private volatile Instant refreshAt = Instant.MIN;

    /**
     * @param source ES 집계. 리뷰가 없으면 null, 구하지 못하면 {@link IllegalStateException}
     * @param ttl    다시 구하는 주기
     */
    protected CachedRatingBaseline(Supplier<Double> source, Duration ttl, Clock clock) {
        this.source = source;
        this.ttl = ttl;
        this.clock = clock;
    }

    /**
     * 구하지 못하면 가진 값을 쓰고 1분 동안 다시 묻지 않는다. ES가 멈췄을 때 요청마다 락을 차례로 잡고 시간 제한까지 기다리면, 쌓인 요청이 요청
     * 스레드를 붙잡아 같은 앱의 챗봇·알림까지 느려진다.
     *
     * @throws IllegalStateException 한 번도 구하지 못했고 지금도 구하지 못한 경우(1분 안에는 ES에 묻지 않고 바로 던진다)
     */
    public double get() {
        if (clock.instant().isBefore(refreshAt)) {
            return known();
        }
        synchronized (this) {
            Instant now = clock.instant();
            if (now.isBefore(refreshAt)) {
                return known();
            }
            try {
                Double baseline = source.get();
                value = baseline == null ? 0 : baseline;
                refreshAt = now.plus(ttl);
            } catch (IllegalStateException e) {
                refreshAt = now.plus(RETRY_AFTER_FAILURE);
                if (value == null) {
                    throw e;
                }
            }
            return value;
        }
    }

    private double known() {
        Double current = value;
        if (current == null) {
            throw new IllegalStateException("평점 전체 평균을 아직 구하지 못했습니다");
        }
        return current;
    }
}
