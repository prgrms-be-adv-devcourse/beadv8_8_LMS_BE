package com.hapbang.auxiliary.search.app;

import java.util.EnumMap;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.hapbang.auxiliary.search.config.ConditionalOnSearchEnabled;
import com.hapbang.auxiliary.search.out.InfluencerDocumentStore;
import com.hapbang.auxiliary.search.out.InfluencerIndexSetup;
import com.hapbang.auxiliary.search.out.InfluencerStats;
import com.hapbang.auxiliary.search.out.MemberStatsSource;
import com.hapbang.auxiliary.search.out.WriteOutcome;
import com.hapbang.shared.member.dto.InfluencerStatsPageDto;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 10분마다 회원 앱 통계로 검색 문서를 맞춘다(#771). 한 pod에서만 돈다({@link SearchJob#STATS_SYNC}).
 * <ol>
 *     <li>분야 이름: 분야 목록을 다시 읽고, 분야가 있는 모든 문서의 이름을 맞춘다(이름이 바뀐 분야, 이름을 모른 채 색인한 분야).</li>
 *     <li>통계: 인플루언서 통계(#769)를 처음부터 끝까지 읽어 채널 이름·대표 채널·팔로워·평점을 부분 갱신한다. 값이 같으면 noop이다.
 *     통계에 있는데 문서가 없는 회원은 건너뛴다(넣는 것은 검색 2 #772의 보정). 숨긴 문서도 건너뛴다.</li>
 * </ol>
 * 회원 version이 문서보다 큰 회원(프로필 이벤트를 놓침)을 다시 받는 일은 검색 2(#772)가 한다. 평점 전체 평균(베이지안 C)은 검색 API가
 * pod마다 계산한다(이 작업은 한 pod에서만 돌아 다른 pod가 값을 받을 수 없다).
 */
@Slf4j
@Service
@ConditionalOnSearchEnabled
@RequiredArgsConstructor
public class InfluencerStatsSync {

    /** 통계 한 페이지 크기(회원 API 상한) */
    static final int PAGE_SIZE = InfluencerStatsPageDto.MAX_SIZE;

    private final InfluencerIndexSetup indexSetup;
    private final InfluencerDocumentStore store;
    private final MemberStatsSource source;
    private final ContentCategoryNames categoryNames;
    private final SearchJobRunner jobRunner;

    /** 한 pod에서만 실행한다. 실패는 다음 회차에 다시 한다 */
    public void run() {
        jobRunner.run(SearchJob.STATS_SYNC, this::sync);
    }

    /** 맞춘다. 테스트는 락 없이 바로 부른다 */
    public StatsSyncResult sync() {
        indexSetup.ensureReady();
        long categoryDocuments = store.syncCategoryNames(categoryNames.refresh());

        Map<WriteOutcome, Long> counts = new EnumMap<>(WriteOutcome.class);
        Long afterId = null;
        int pages = 0;
        do {
            InfluencerStatsPageDto page = source.influencerStats(afterId, PAGE_SIZE);
            store.applyStats(page.items().stream().map(InfluencerStats::from).toList())
                    .values().forEach(outcome -> counts.merge(outcome, 1L, Long::sum));
            afterId = page.nextAfterId();
            pages++;
        } while (afterId != null);

        StatsSyncResult result = new StatsSyncResult(pages, counts.getOrDefault(WriteOutcome.UPDATED, 0L),
                counts.getOrDefault(WriteOutcome.NOOP, 0L), counts.getOrDefault(WriteOutcome.MISSING, 0L),
                categoryDocuments);
        log.info("검색 통계를 맞췄다 pages={}, updated={}, noop={}, missing={}, categoryDocuments={}", result.pages(),
                result.updated(), result.noop(), result.missing(), result.categoryDocuments());
        return result;
    }

    /**
     * @param pages             읽은 통계 페이지 수
     * @param updated           통계가 바뀐 문서 수
     * @param noop              통계가 같았거나 숨긴 문서 수
     * @param missing           문서가 없어 건너뛴 회원 수(검색 2 보정 대상)
     * @param categoryDocuments 분야 이름을 고친 문서 수
     */
    public record StatsSyncResult(int pages, long updated, long noop, long missing, long categoryDocuments) {
    }
}
