package com.hapbang.auxiliary.search.common.out;

import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.hapbang.auxiliary.search.influencer.out.InfluencerIndexDefinition;

/**
 * 검색 index·alias의 지금 상태(#773). 처음 적재·재색인을 할지 정하는 데 쓴다.
 *
 * @param aliasTargets  alias({@link InfluencerIndexDefinition#ALIAS})가 가리키는 index. alias가 없으면 빈 집합
 * @param indices       {@code search-influencers-*} index 전부
 * @param expectedIndex 지금 코드 설정(mapping·분석기·사용자 사전)의 index 이름
 * @param createdAt     index마다 ES가 만든 시각({@code index.creation_date}). 읽지 못한 index는 빠진다
 */
public record IndexState(Set<String> aliasTargets, Set<String> indices, String expectedIndex,
                         Map<String, Instant> createdAt) {

    public IndexState {
        aliasTargets = Set.copyOf(aliasTargets);
        indices = Set.copyOf(indices);
        createdAt = Map.copyOf(createdAt);
    }

    /**
     * alias가 가리키는 index 중 가장 늦게 만든 시각. 롤링 배포 중 옛 pod가 더 새 배포의 index를 되돌리지 않게 쓴다(PR #845 리뷰 Q4).
     *
     * @return alias가 없거나 만든 시각을 모르면 빈 값
     */
    public Optional<Instant> aliasTargetCreatedAt() {
        return aliasTargets.stream().map(createdAt::get).filter(Objects::nonNull).max(Instant::compareTo);
    }

    /** alias가 없다(처음 배포, ES를 새로 만듦) */
    public boolean aliasMissing() {
        return aliasTargets.isEmpty();
    }

    /** alias가 지금 설정의 index 하나만 가리킨다. 처음 적재·재색인이 필요 없다 */
    public boolean upToDate() {
        return aliasTargets.equals(Set.of(expectedIndex));
    }

    /** alias가 가리키지 않는 index. 실패한 처음 적재·재색인이 남긴 것이거나, 교체한 뒤 지우지 못한 옛 index다 */
    public Set<String> leftovers() {
        Set<String> leftovers = new HashSet<>(indices);
        leftovers.removeAll(aliasTargets);
        return Set.copyOf(leftovers);
    }
}
