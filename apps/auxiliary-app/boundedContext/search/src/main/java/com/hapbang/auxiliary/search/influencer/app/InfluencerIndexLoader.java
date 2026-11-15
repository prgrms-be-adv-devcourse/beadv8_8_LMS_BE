package com.hapbang.auxiliary.search.influencer.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.hapbang.auxiliary.search.common.app.IndexLoader;
import com.hapbang.auxiliary.search.common.out.WriteOutcome;
import com.hapbang.auxiliary.search.influencer.out.InfluencerDocumentStore;
import com.hapbang.auxiliary.search.influencer.out.MemberDocument;
import com.hapbang.auxiliary.search.influencer.out.MemberListSource;
import com.hapbang.shared.member.dto.MemberPageDto;
import com.hapbang.shared.member.dto.UserDto;

import lombok.extern.slf4j.Slf4j;

/**
 * 인플루언서 재색인 채우기 부품(#773, #868). 회원 목록(모든 회원)을 끝까지 읽어 새 index에 bulk로 넣고(회원 version 조건부 쓰기), 이어서 통계를 끝까지
 * 읽어 채운다.
 * <ul>
 *     <li>정상 인플루언서는 보이는 문서, 그 밖의 회원은 숨김 문서다(#773 리뷰 Q3).</li>
 *     <li>회원 정보가 빠졌거나 ES가 그 회원 값 때문에 거절한 회원은 건너뛴다(증분 보정과 같다). 그 밖의 쓰기 실패는 멈춘다.</li>
 *     <li>통계를 쓰지 못한 회원 수는 INFO로 남긴다(10분 통계 동기화가 다시 쓴다).</li>
 * </ul>
 */
@Slf4j
class InfluencerIndexLoader implements IndexLoader {

    /** 회원 목록·통계 한 페이지 크기(회원 API 상한) */
    static final int PAGE_SIZE = MemberPageDto.MAX_SIZE;

    private final InfluencerDocumentStore store;
    private final InfluencerMemberIndexer indexer;
    private final ContentCategoryNames categoryNames;
    private final MemberListSource members;
    private final InfluencerStatsSync statsSync;

    InfluencerIndexLoader(InfluencerDocumentStore store, InfluencerMemberIndexer indexer,
            ContentCategoryNames categoryNames, MemberListSource members, InfluencerStatsSync statsSync) {
        this.store = store;
        this.indexer = indexer;
        this.categoryNames = categoryNames;
        this.members = members;
        this.statsSync = statsSync;
    }

    /** 회원 목록으로 문서를 넣고(정상 인플루언서는 보이게, 그 밖은 숨김), 통계를 채운다 */
    @Override
    public LoadResult load(String index) {
        refreshCategoryNames();
        long read = 0;
        long influencers = 0;
        long hidden = 0;
        long skipped = 0;
        Long afterId = null;
        do {
            MemberPageDto page = members.members(afterId, PAGE_SIZE);
            List<MemberDocument> documents = new ArrayList<>();
            for (UserDto user : page.items()) {
                try {
                    documents.add(indexer.documentOf(user));
                } catch (IllegalArgumentException e) {
                    // 회원 정보가 빠졌다. 다시 읽어도 같으므로 건너뛴다(증분 보정과 같다)
                    log.warn("회원 정보가 빠져 검색 처음 적재에서 건너뛴다 userId={}", user == null ? null : user.id(), e);
                    skipped++;
                }
            }
            Map<Long, WriteOutcome> outcomes = store.loadMembers(index, documents);
            requireNoFailure(outcomes, index);
            for (MemberDocument document : documents) {
                if (outcomes.get(document.userId()) == WriteOutcome.REJECTED) {
                    // ES가 이 회원의 값 때문에 거절했다. 다시 넣어도 같으므로 건너뛴다(멈추면 처음 적재가 영영 끝나지 않는다)
                    skipped++;
                } else if (document.isVisible()) {
                    influencers++;
                } else {
                    hidden++;
                }
            }
            read += page.items().size();
            afterId = page.nextAfterId();
        } while (afterId != null);

        long statsFailed = statsSync.applyAllStats(index).count(WriteOutcome.FAILED);
        log.info("검색 처음 적재·재색인의 새 index에 인플루언서 통계를 채웠다 index={}, statsFailed={}", index, statsFailed);
        return new LoadResult(read, influencers, hidden, skipped);
    }

    /** 분야 이름을 새로 읽는다. 못 읽으면 가진 이름으로 넣고 10분 동기화가 채운다(통계 동기화와 같다) */
    private void refreshCategoryNames() {
        try {
            categoryNames.refresh();
        } catch (RuntimeException e) {
            log.warn("분야 이름을 읽지 못해 가진 이름으로 처음 적재한다", e);
        }
    }

    /** 넣지 못한 회원이 있으면(장애) 멈춘다. 그 회원이 빠진 채 alias를 옮기면 다음 재색인까지 검색에 보이지 않는다 */
    private static void requireNoFailure(Map<Long, WriteOutcome> outcomes, String index) {
        List<Long> failed = outcomes.entrySet().stream().filter(e -> e.getValue() == WriteOutcome.FAILED)
                .map(Map.Entry::getKey).sorted().toList();
        if (!failed.isEmpty()) {
            throw new IllegalStateException("새 index에 회원을 넣지 못했습니다 index=" + index + ", userIds=" + failed);
        }
    }
}
