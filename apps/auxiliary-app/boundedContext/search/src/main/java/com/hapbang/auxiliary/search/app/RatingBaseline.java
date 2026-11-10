package com.hapbang.auxiliary.search.app;

import java.time.Clock;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.search.config.ConditionalOnSearchEnabled;
import com.hapbang.auxiliary.search.config.SearchRankingProperties;
import com.hapbang.auxiliary.search.out.InfluencerSearcher;

/**
 * 평점순 베이지안 평균 (v×R + m×C)/(v+m)의 C: 보이는 인플루언서가 받은 출연 리뷰 전체의 평균(리뷰 수 가중, #771).
 * <p>
 * pod마다 메모리에 두고 주기({@code ratingBaselineTtl}, 기본 10분)가 지나면 평점순 검색 때 ES 집계로 다시 구한다. 통계 동기화는 한 pod만
 * 돌아 다른 pod에 값을 줄 수 없어서다(#771 Q8). 리뷰가 하나도 없으면 0이다(모두 0이라 순서는 팔로워가 가른다).
 */
@Component
@ConditionalOnSearchEnabled
public class RatingBaseline {

    private final InfluencerSearcher searcher;
    private final SearchRankingProperties ranking;
    private final Clock clock;
    private volatile double value;
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
     * @throws IllegalStateException 처음 구하는데 ES에 닿지 못한 경우(이미 구한 값이 있으면 그 값을 쓴다)
     */
    public double get() {
        Instant now = clock.instant();
        if (now.isBefore(refreshAt)) {
            return value;
        }
        synchronized (this) {
            if (now.isBefore(refreshAt)) {
                return value;
            }
            try {
                Double baseline = searcher.ratingBaseline();
                value = baseline == null ? 0 : baseline;
                refreshAt = now.plus(ranking.ratingBaselineTtl());
            } catch (IllegalStateException e) {
                if (refreshAt.equals(Instant.MIN)) {
                    throw e;
                }
            }
            return value;
        }
    }
}
