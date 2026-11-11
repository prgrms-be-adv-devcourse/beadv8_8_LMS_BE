package com.hapbang.auxiliary.search.common.out;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.search.config.SearchElasticsearchConfig;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.synonyms.SynonymRule;
import co.elastic.clients.elasticsearch.synonyms.SynonymRuleRead;
import lombok.extern.slf4j.Slf4j;

/**
 * 검색 동의어 묶음({@link IndexDefinition#SYNONYMS_SET})을 레포 파일({@code search/common/synonyms.txt})과 맞춘다(#771, 공통 코드 #868).
 * 묶음은 cluster에 하나이고 모든 검색 index가 함께 쓴다(L17).
 * <ul>
 *     <li>ES 묶음과 레포 파일이 다르면 묶음 전체를 PUT한다. ES가 그 묶음을 쓰는 검색 분석기를 스스로 다시 읽는다(재색인·reload 호출 없음).</li>
 *     <li>묶음은 지우지 않는다(쓰는 index가 있으면 지울 수 없다).</li>
 * </ul>
 */
@Slf4j
@Component
public class SynonymsSetup {

    private final ElasticsearchClient client;
    private final List<String> rules;
    private volatile boolean synced;

    @Autowired
    public SynonymsSetup(@Qualifier(SearchElasticsearchConfig.CLIENT) ElasticsearchClient client) {
        this(client, IndexDefinition.loadSynonymRules());
    }

    /**
     * @param rules 동의어 규칙(Solr 형식 한 줄씩, 파일 순서). 테스트는 틀린 규칙을 넘겨 거절을 흉내 낸다
     */
    public SynonymsSetup(ElasticsearchClient client, List<String> rules) {
        this.client = client;
        this.rules = List.copyOf(rules);
    }

    /**
     * 묶음을 맞춘다. 한 번 맞추면 다시 묻지 않는다(쓰기 준비용).
     *
     * @throws IllegalStateException ES에 닿지 못하거나 거절당한 경우
     */
    public void sync() {
        if (synced) {
            return;
        }
        resync();
    }

    /**
     * 기억과 상관없이 ES의 묶음을 다시 확인해 맞춘다. 처음 적재·재색인이 index를 만들기 전에 부른다(pod가 떠 있는 동안 ES를 새로 만들었을 수 있다).
     *
     * @throws SynonymsRejectedException ES가 묶음을 내용 때문에 거절한 경우(4xx, 429 제외). 파일을 고쳐야 한다
     * @throws IllegalStateException     ES에 닿지 못하거나 5xx·429인 경우(기다리면 풀린다)
     */
    public synchronized void resync() {
        try {
            Map<String, String> expected = expectedSynonyms();
            if (!expected.equals(currentSynonyms())) {
                List<SynonymRule> putRules = new ArrayList<>();
                expected.forEach((id, synonyms) -> putRules.add(SynonymRule.of(r -> r.id(id).synonyms(synonyms))));
                client.synonyms().putSynonym(p -> p.id(IndexDefinition.SYNONYMS_SET).synonymsSet(putRules));
                log.info("검색 동의어 묶음을 바꿨다 synonymsSet={}, rules={}", IndexDefinition.SYNONYMS_SET,
                        putRules.size());
            }
        } catch (ElasticsearchException e) {
            if (e.status() >= 400 && e.status() < 500 && e.status() != 429) {
                throw new SynonymsRejectedException("ES가 검색 동의어 묶음을 거절했습니다. synonyms.txt를 고쳐야 합니다 synonymsSet="
                        + IndexDefinition.SYNONYMS_SET + ", error="
                        + (e.error() == null ? null : e.error().type()), e);
            }
            throw new IllegalStateException("검색 동의어 묶음을 맞추지 못했습니다 synonymsSet="
                    + IndexDefinition.SYNONYMS_SET, e);
        } catch (IOException e) {
            throw new IllegalStateException("검색 동의어 묶음을 맞추지 못했습니다 synonymsSet="
                    + IndexDefinition.SYNONYMS_SET, e);
        }
        synced = true;
    }

    /** 파일 순서대로 r001, r002 … id를 붙인 규칙 */
    private Map<String, String> expectedSynonyms() {
        Map<String, String> expected = new LinkedHashMap<>();
        for (int i = 0; i < rules.size(); i++) {
            expected.put("r%03d".formatted(i + 1), rules.get(i));
        }
        return expected;
    }

    /** ES의 묶음 규칙. 묶음이 없으면 빈 Map */
    private Map<String, String> currentSynonyms() throws IOException {
        try {
            Map<String, String> current = new LinkedHashMap<>();
            for (SynonymRuleRead rule : client.synonyms()
                    .getSynonym(g -> g.id(IndexDefinition.SYNONYMS_SET).size(10_000)).synonymsSet()) {
                current.put(rule.id(), rule.synonyms());
            }
            return current;
        } catch (ElasticsearchException e) {
            if (e.status() == 404) {
                return Map.of();
            }
            throw e;
        }
    }
}
