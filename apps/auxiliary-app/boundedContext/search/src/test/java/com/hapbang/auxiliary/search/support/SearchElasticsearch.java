package com.hapbang.auxiliary.search.support;

import java.io.IOException;
import java.net.URI;
import java.util.List;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.json.jackson.Jackson3JsonpMapper;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;

import com.hapbang.auxiliary.search.out.InfluencerIndexDefinition;
import com.hapbang.auxiliary.search.out.InfluencerIndexSetup;

/**
 * Spring 컨텍스트 없이 검색 ES를 쓰는 테스트 도우미(#771). 컨테이너는 {@link SearchElasticsearchTestcontainersConfig}가 JVM에 하나 띄운다.
 * 테스트 클래스는 차례로 돌고 같은 alias·동의어 묶음 이름을 쓰므로, ES를 쓰는 테스트는 시작할 때 {@link #reset(ElasticsearchClient)}로 비운다.
 */
public final class SearchElasticsearch {

    private SearchElasticsearch() {
    }

    /** 컨테이너 ES에 붙는 client */
    public static ElasticsearchClient client() {
        return client(SearchElasticsearchTestcontainersConfig.uri());
    }

    /** 주소를 고른 client(닿지 않는 주소로 장애를 흉내 낸다) */
    public static ElasticsearchClient client(String uri) {
        return new ElasticsearchClient(new Rest5ClientTransport(Rest5Client.builder(URI.create(uri)).build(),
                new Jackson3JsonpMapper()));
    }

    /**
     * 처음 적재가 끝난 상태를 만든다: 동의어 묶음을 올리고, 지금 설정의 빈 index를 만들어 alias를 붙인다. 처음 적재 전에는 alias가 없어 쓰기를
     * 건너뛰므로(#773 Q1), 문서를 쓰는 테스트는 먼저 이것을 부른다.
     *
     * @return 만든 index 이름
     */
    public static String createIndex(ElasticsearchClient client) {
        InfluencerIndexSetup setup = new InfluencerIndexSetup(client);
        setup.syncSynonyms();
        String index = setup.createIndex();
        setup.switchAlias(List.of(), index);
        return index;
    }

    /**
     * 검색 index(search-influencers-*)와 동의어 묶음을 지운다. 묶음은 쓰는 index가 있으면 지울 수 없어 index를 먼저 지운다.
     * ES 9는 와일드카드 삭제를 막으므로({@code action.destructive_requires_name}) 이름을 찾아 지운다
     */
    public static void reset(ElasticsearchClient client) throws IOException {
        List<String> indices = List.copyOf(client.indices().get(g -> g.index(InfluencerIndexDefinition.INDEX_PREFIX + "*")
                .allowNoIndices(true)).indices().keySet());
        if (!indices.isEmpty()) {
            client.indices().delete(d -> d.index(indices));
        }
        try {
            client.synonyms().deleteSynonym(d -> d.id(InfluencerIndexDefinition.SYNONYMS_SET));
        } catch (ElasticsearchException e) {
            if (e.status() != 404) {
                throw e;
            }
        }
    }
}
