package com.hapbang.auxiliary.search.out;

import java.util.HashSet;
import java.util.Set;

/**
 * 검색 index·alias의 지금 상태(#773). 처음 적재·재색인을 할지 정하는 데 쓴다.
 *
 * @param aliasTargets  alias({@link InfluencerIndexDefinition#ALIAS})가 가리키는 index. alias가 없으면 빈 집합
 * @param indices       {@code search-influencers-*} index 전부
 * @param expectedIndex 지금 코드 설정(mapping·분석기·사용자 사전)의 index 이름
 */
public record IndexState(Set<String> aliasTargets, Set<String> indices, String expectedIndex) {

    public IndexState {
        aliasTargets = Set.copyOf(aliasTargets);
        indices = Set.copyOf(indices);
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
