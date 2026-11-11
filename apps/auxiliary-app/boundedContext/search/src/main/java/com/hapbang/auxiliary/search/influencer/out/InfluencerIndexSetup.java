package com.hapbang.auxiliary.search.influencer.out;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.search.common.out.IndexDefinition;
import com.hapbang.auxiliary.search.common.out.IndexSetup;
import com.hapbang.auxiliary.search.common.out.SynonymsSetup;
import com.hapbang.auxiliary.search.config.SearchElasticsearchConfig;

import co.elastic.clients.elasticsearch.ElasticsearchClient;

/**
 * 인플루언서 검색 index의 {@link IndexSetup}(#771, #868 R7). 인플루언서 정의({@link InfluencerIndexDefinition})만 채운다.
 * <p>
 * <b>생성자만 둔다.</b> 메서드를 덮어쓰지 않는다. index·alias를 다루는 동작은 공통 {@link IndexSetup}에서 바꾼다. 따로 둔 이유는 쓰는 곳이 타입으로
 * 대상을 가리켜 다른 대상의 index를 잘못 넘기지 못하게 하기 위해서다.
 */
@Component
public class InfluencerIndexSetup extends IndexSetup {

    /** 앱: 동의어 묶음은 모든 대상이 함께 쓰는 빈을 받는다 */
    @Autowired
    public InfluencerIndexSetup(@Qualifier(SearchElasticsearchConfig.CLIENT) ElasticsearchClient client,
            SynonymsSetup synonyms) {
        super(client, InfluencerIndexDefinition.load(), synonyms);
    }

    /** 테스트용. 레포 파일로 만들고 동의어 묶음도 따로 맞춘다 */
    public InfluencerIndexSetup(ElasticsearchClient client) {
        this(client, InfluencerIndexDefinition.load());
    }

    /** 테스트용. 정의(틀린 동의어 규칙 등)를 직접 넘긴다 */
    InfluencerIndexSetup(ElasticsearchClient client, IndexDefinition definition) {
        super(client, definition, new SynonymsSetup(client, definition.synonymRules()));
    }
}
