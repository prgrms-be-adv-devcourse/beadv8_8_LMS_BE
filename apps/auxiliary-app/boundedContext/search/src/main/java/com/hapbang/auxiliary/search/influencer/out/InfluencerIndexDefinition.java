package com.hapbang.auxiliary.search.influencer.out;

import java.util.List;

import com.hapbang.auxiliary.search.common.out.IndexDefinition;

/**
 * 인플루언서 검색 index의 이름과 정의(#771). 정의를 만드는 일은 공통 {@link IndexDefinition}이 하고, 여기는 대상 이름과 alias만 둔다(#868).
 * 설정·mapping은 {@code search/influencers/index.json}이다.
 */
public final class InfluencerIndexDefinition {

    /** 대상 이름. {@code search/influencers/index.json}을 읽는다 */
    public static final String TARGET = "influencers";
    /** 검색·쓰기에 쓰는 alias({@code search-influencers}). 대상 이름에서 만든다(출처를 하나로, #868 리뷰) */
    public static final String ALIAS = IndexDefinition.aliasOf(TARGET);
    /** index 이름 앞부분. 뒤에 설정 해시가 붙는다 */
    public static final String INDEX_PREFIX = ALIAS + "-";
    /** 지표·로그의 대상 태그 값({@code target=influencer}) */
    public static final String METRIC_TARGET = "influencer";

    private InfluencerIndexDefinition() {
    }

    /** 클래스패스의 레포 파일로 만든다 */
    public static IndexDefinition load() {
        return IndexDefinition.load(TARGET);
    }

    /** 테스트용. 파일 내용을 직접 넘긴다 */
    static IndexDefinition of(String indexTemplate, List<String> userDictionary, List<String> synonymRules) {
        return IndexDefinition.of(TARGET, indexTemplate, userDictionary, synonymRules);
    }
}
