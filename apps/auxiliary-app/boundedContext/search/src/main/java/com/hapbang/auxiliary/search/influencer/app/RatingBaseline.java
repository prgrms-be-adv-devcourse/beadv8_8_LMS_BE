package com.hapbang.auxiliary.search.influencer.app;

import java.time.Clock;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.search.common.app.CachedRatingBaseline;
import com.hapbang.auxiliary.search.influencer.config.SearchRankingProperties;
import com.hapbang.auxiliary.search.influencer.out.InfluencerSearcher;

/**
 * 인플루언서 평점순 베이지안 평균의 C: 보이는 인플루언서가 받은 출연 리뷰 전체의 평균(리뷰 수 가중, #771). 캐시 동작은 공통
 * {@link CachedRatingBaseline}에 있다(#883에서 강의와 함께 쓰려고 옮김). 생성자만 둔다.
 */
@Component
public class RatingBaseline extends CachedRatingBaseline {

    @Autowired
    public RatingBaseline(InfluencerSearcher searcher, SearchRankingProperties ranking) {
        this(searcher, ranking, Clock.systemDefaultZone());
    }

    RatingBaseline(InfluencerSearcher searcher, SearchRankingProperties ranking, Clock clock) {
        super(searcher::ratingBaseline, ranking.ratingBaselineTtl(), clock);
    }
}
