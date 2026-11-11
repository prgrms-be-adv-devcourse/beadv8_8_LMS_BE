package com.hapbang.auxiliary.search.app;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.search.config.SearchRankingProperties;
import com.hapbang.auxiliary.search.out.InfluencerSearcher;

/**
 * 평점순 베이지안 평균 (v×R + m×C)/(v+m)의 C: 보이는 인플루언서가 받은 출연 리뷰 전체의 평균(리뷰 수 가중, #771).
 * <p>
 * pod마다 메모리에 두고 주기({@code ratingBaselineTtl}, 기본 10분)가 지나면 평점순 검색 때 ES 집계로 다시 구한다. 구하지 못하면 1분 동안
 * 다시 묻지 않는다. 통계 동기화는 한 pod만
 * 돌아 다른 pod에 값을 줄 수 없어서다(#771 Q8). 리뷰가 하나도 없으면 0이다(모두 0이라 순서는 팔로워가 가른다).
 */
@Component
public class RatingBaseline {

    /** 구하지 못하면 이만큼 다시 묻지 않는다 */
    static final Duration RETRY_AFTER_FAILURE = Duration.ofMinutes(1);

    private final InfluencerSearcher searcher;
    private final SearchRankingProperties ranking;
    private final Clock clock;
    private volatile Double value;
    /** 이 시각 전에는 ES에 묻지 않는다(성공하면 주기 뒤, 실패하면 1분 뒤) */
    private volatile Instant refreshAt = Instant.MIN;

    @Autowired
    public RatingBaseline(InfluencerSearcher searcher, SearchRankingProperties ranking) {
        this(searcher, ranking, Clock.systemDefaultZone());
    }

    RatingBaseline(InfluencerSearcher searcher, SearchRankingProperties ranking, Clock clock) {
        this.searcher = searcher;
        this.ranking = ranking;
        this.clock = clock;
    }

    /**
     * 구하지 못하면 가진 값을 쓰고 1분 동안 다시 묻지 않는다. ES가 멈췄을 때 평점순 요청마다 락을 차례로 잡고 시간 제한까지 기다리면, 쌓인
     * 요청이 요청 스레드를 붙잡아 같은 앱의 챗봇·알림까지 느려진다.
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
                Double baseline = searcher.ratingBaseline();
                value = baseline == null ? 0 : baseline;
                refreshAt = now.plus(ranking.ratingBaselineTtl());
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
            throw new IllegalStateException("출연 평점 전체 평균을 아직 구하지 못했습니다");
        }
        return current;
    }
}
