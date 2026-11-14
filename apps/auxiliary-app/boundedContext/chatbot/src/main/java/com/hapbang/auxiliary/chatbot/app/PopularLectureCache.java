package com.hapbang.auxiliary.chatbot.app;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.chatbot.config.ChatbotLectureRecommendProperties;
import com.hapbang.auxiliary.chatbot.domain.LectureRanker;
import com.hapbang.auxiliary.chatbot.out.LectureRecommendStore;

/**
 * 강의 인기순 상위 목록의 pod 메모리 캐시 (#720). 인기순 쿼리는 판매 중 강의 전체를 구매 사본과 집계하므로 요청마다 돌리지 않는다.
 * <ul>
 *     <li>회원과 상관없는 상위 {@code popular-cache-size}개를 {@code popular-cache-ttl}마다 다시 읽는다. 회원별 제외(본인 강의·
 *     보유·장바구니 등)는 쓰는 쪽이 코드에서 한다.</li>
 *     <li>캐시 뒤 판매 중지·판매자 숨김이 된 강의가 있을 수 있다. 쓰는 쪽이 응답 직전에 고른 강의를 ID로 다시 읽어 거른다
 *     ({@link LectureRecommendUseCase}).</li>
 *     <li>만료 직후 여러 요청이 함께 오면 한 요청만 다시 읽고 나머지는 기다린다(같은 집계를 겹쳐 돌리지 않게).</li>
 *     <li>시각은 챗봇 시계({@link ChatbotConfig#CLOCK})로 잰다.</li>
 * </ul>
 */
@Component
public class PopularLectureCache {

    private final LectureRecommendStore store;
    private final ChatbotLectureRecommendProperties properties;
    private final Clock clock;

    private volatile Snapshot snapshot = new Snapshot(List.of(), Instant.MIN);

    PopularLectureCache(LectureRecommendStore store, ChatbotLectureRecommendProperties properties,
            @Qualifier(ChatbotConfig.CLOCK) Clock clock) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    /** 인기순 상위 강의(구매 수 내림차순, 같으면 강의 ID 내림차순) */
    List<LectureRanker.Popular> top() {
        Snapshot current = snapshot;
        if (fresh(current)) {
            return current.items();
        }
        synchronized (this) {
            current = snapshot;
            if (!fresh(current)) {
                current = new Snapshot(store.popular(null, Set.of(), properties.popularCacheSize()), clock.instant());
                snapshot = current;
            }
            return current.items();
        }
    }

    /** 다음 조회 때 다시 읽게 한다. 사본을 통째로 비운 뒤(테스트) 부른다 */
    public synchronized void invalidate() {
        snapshot = new Snapshot(List.of(), Instant.MIN);
    }

    private boolean fresh(Snapshot current) {
        return current.loadedAt().plus(properties.popularCacheTtl()).isAfter(clock.instant());
    }

    private record Snapshot(List<LectureRanker.Popular> items, Instant loadedAt) {
    }
}
