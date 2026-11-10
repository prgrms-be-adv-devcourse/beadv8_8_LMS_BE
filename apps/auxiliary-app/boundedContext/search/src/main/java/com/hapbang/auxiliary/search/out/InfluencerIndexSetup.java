package com.hapbang.auxiliary.search.out;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.search.config.ConditionalOnSearchEnabled;
import com.hapbang.auxiliary.search.config.SearchElasticsearchConfig;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.synonyms.SynonymRule;
import co.elastic.clients.elasticsearch.synonyms.SynonymRuleRead;
import lombok.extern.slf4j.Slf4j;

/**
 * 인플루언서 검색 index를 쓸 수 있게 맞춘다(#771). 순서는 "동의어 묶음 → index·alias"다(묶음이 없어도 index는 만들어지지만,
 * 먼저 맞춰 두면 처음 검색부터 동의어가 걸린다).
 * <ul>
 *     <li>동의어: ES 묶음과 레포 파일이 다르면 묶음 전체를 PUT한다. ES가 그 묶음을 쓰는 검색 분석기를 스스로 다시 읽는다(재색인·reload
 *     호출 없음). 묶음은 지우지 않는다(쓰는 index가 있으면 지울 수 없다).</li>
 *     <li>alias가 없으면 지금 설정의 index를 만들고 alias를 붙인다. pod 둘이 함께 해도 결과가 같다(이미 있는 index는 그대로 쓰고,
 *     alias 붙이기는 여러 번 해도 같다).</li>
 *     <li>alias가 다른 설정의 index를 가리키면(mapping·사용자 사전이 바뀜) 그대로 쓰고 경고만 남긴다. 새 index로 옮기는 재색인은
 *     검색 3(#773)이 맡는다.</li>
 * </ul>
 * 한 번 맞추면 다시 ES에 묻지 않는다. 실패하면 예외를 던지고, 다음 호출(이벤트 소비·배치)에서 다시 한다.
 */
@Slf4j
@Component
@ConditionalOnSearchEnabled
public class InfluencerIndexSetup {

    private final ElasticsearchClient client;
    private final InfluencerIndexDefinition definition;
    private volatile boolean ready;

    @Autowired
    public InfluencerIndexSetup(@Qualifier(SearchElasticsearchConfig.CLIENT) ElasticsearchClient client) {
        this(client, InfluencerIndexDefinition.load());
    }

    InfluencerIndexSetup(ElasticsearchClient client, InfluencerIndexDefinition definition) {
        this.client = client;
        this.definition = definition;
    }

    /**
     * 동의어 묶음과 index·alias를 맞춘다. 이미 맞췄으면 바로 돌아간다.
     *
     * @throws IllegalStateException ES에 닿지 못하거나 거절당한 경우
     */
    public void ensureReady() {
        if (ready) {
            return;
        }
        synchronized (this) {
            if (ready) {
                return;
            }
            try {
                syncSynonyms();
                ensureAlias();
            } catch (IOException | ElasticsearchException e) {
                throw new IllegalStateException("검색 index를 준비하지 못했습니다 alias=" + InfluencerIndexDefinition.ALIAS, e);
            }
            ready = true;
        }
    }

    /** 지금 설정의 index 이름 */
    public String indexName() {
        return definition.indexName();
    }

    private void syncSynonyms() throws IOException {
        Map<String, String> expected = expectedSynonyms();
        if (expected.equals(currentSynonyms())) {
            return;
        }
        List<SynonymRule> rules = new ArrayList<>();
        expected.forEach((id, synonyms) -> rules.add(SynonymRule.of(r -> r.id(id).synonyms(synonyms))));
        client.synonyms().putSynonym(p -> p.id(InfluencerIndexDefinition.SYNONYMS_SET).synonymsSet(rules));
        log.info("검색 동의어 묶음을 바꿨다 synonymsSet={}, rules={}", InfluencerIndexDefinition.SYNONYMS_SET, rules.size());
    }

    /** 파일 순서대로 r001, r002 … id를 붙인 규칙 */
    private Map<String, String> expectedSynonyms() {
        Map<String, String> rules = new LinkedHashMap<>();
        List<String> lines = definition.synonymRules();
        for (int i = 0; i < lines.size(); i++) {
            rules.put("r%03d".formatted(i + 1), lines.get(i));
        }
        return rules;
    }

    /** ES의 묶음 규칙. 묶음이 없으면 빈 Map */
    private Map<String, String> currentSynonyms() throws IOException {
        try {
            Map<String, String> rules = new LinkedHashMap<>();
            for (SynonymRuleRead rule : client.synonyms()
                    .getSynonym(g -> g.id(InfluencerIndexDefinition.SYNONYMS_SET).size(10_000)).synonymsSet()) {
                rules.put(rule.id(), rule.synonyms());
            }
            return rules;
        } catch (ElasticsearchException e) {
            if (e.status() == 404) {
                return Map.of();
            }
            throw e;
        }
    }

    private void ensureAlias() throws IOException {
        String indexName = definition.indexName();
        if (client.indices().existsAlias(a -> a.name(InfluencerIndexDefinition.ALIAS)).value()) {
            Set<String> targets = client.indices().getAlias(a -> a.name(InfluencerIndexDefinition.ALIAS)).aliases().keySet();
            if (!targets.contains(indexName)) {
                log.warn("검색 index 설정이 바뀌어 재색인이 필요하다. 지금 index를 그대로 쓴다 alias={}, current={}, expected={}",
                        InfluencerIndexDefinition.ALIAS, targets, indexName);
            }
            return;
        }
        createIndexIfMissing(indexName);
        client.indices().updateAliases(u -> u.actions(a -> a.add(add -> add.index(indexName)
                .alias(InfluencerIndexDefinition.ALIAS).isWriteIndex(true))));
        log.info("검색 index에 alias를 붙였다 alias={}, index={}", InfluencerIndexDefinition.ALIAS, indexName);
    }

    private void createIndexIfMissing(String indexName) throws IOException {
        if (client.indices().exists(e -> e.index(indexName)).value()) {
            return;
        }
        try {
            client.indices().create(c -> c.index(indexName).withJson(new StringReader(definition.indexBody())));
            log.info("검색 index를 만들었다 index={}", indexName);
        } catch (ElasticsearchException e) {
            // 다른 pod가 먼저 만들었다
            if (!"resource_already_exists_exception".equals(e.error().type())) {
                throw e;
            }
        }
    }
}
