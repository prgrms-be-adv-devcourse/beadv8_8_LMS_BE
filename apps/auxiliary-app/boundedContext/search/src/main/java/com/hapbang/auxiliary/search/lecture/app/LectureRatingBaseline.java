package com.hapbang.auxiliary.search.lecture.app;

import java.time.Clock;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.search.common.app.CachedRatingBaseline;
import com.hapbang.auxiliary.search.lecture.config.LectureRankingProperties;
import com.hapbang.auxiliary.search.lecture.out.LectureSearcher;

/**
 * 강의 베이지안 평균의 C: 검색에 보이는 강의가 받은 리뷰 전체의 평균(리뷰 수 가중, #883 L10·L11). 평점순 정렬과 관련도 가산에 쓴다. 캐시 동작은
 * 공통 {@link CachedRatingBaseline}에 있다. 생성자만 둔다.
 */
@Component
public class LectureRatingBaseline extends CachedRatingBaseline {

    @Autowired
    public LectureRatingBaseline(LectureSearcher searcher, LectureRankingProperties ranking) {
        this(searcher, ranking, Clock.systemDefaultZone());
    }

    LectureRatingBaseline(LectureSearcher searcher, LectureRankingProperties ranking, Clock clock) {
        super(searcher::ratingBaseline, ranking.ratingBaselineTtl(), clock);
    }
}
