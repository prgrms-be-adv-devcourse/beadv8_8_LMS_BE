package com.hapbang.auxiliary.search.out;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collection;
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
import co.elastic.clients.elasticsearch.indices.update_aliases.Action;
import co.elastic.clients.elasticsearch.synonyms.SynonymRule;
import co.elastic.clients.elasticsearch.synonyms.SynonymRuleRead;
import lombok.extern.slf4j.Slf4j;

/**
 * 인플루언서 검색 index·alias·동의어 묶음을 다룬다(#771, 처음 적재·재색인 #773).
 * <ul>
 *     <li>동의어: ES 묶음과 레포 파일이 다르면 묶음 전체를 PUT한다. ES가 그 묶음을 쓰는 검색 분석기를 스스로 다시 읽는다(재색인·reload
 *     호출 없음). 묶음은 지우지 않는다(쓰는 index가 있으면 지울 수 없다).</li>
 *     <li>쓰기 준비({@link #ensureReady}): alias가 있어야 준비된 것이다. alias가 없으면 만들지 않고 {@link IndexNotReadyException}을 던진다.
 *     alias는 처음 적재가 index를 다 채우고 확인한 뒤에만 붙인다(#773 Q1). 빈 index에 alias를 먼저 붙이면 "alias 있음, 설정 같음"으로 보여
 *     처음 적재가 돌지 않는다.</li>
 *     <li>alias가 다른 설정의 index를 가리키면(mapping·사용자 사전이 바뀜) 재색인이 옮길 때까지 그 index에 쓴다.</li>
 *     <li>처음 적재·재색인용: 상태 보기, 새 index 만들기·지우기, refresh, alias 원자 교체.</li>
 * </ul>
 * 쓰기 준비는 한 번 되면 다시 ES에 묻지 않는다. 실패하면 예외를 던지고, 다음 호출(이벤트 소비·배치)에서 다시 한다. 준비된 뒤 ES를 새로 만들어
 * alias가 사라지면 문서 쓰기가 alias 없음(404)을 {@link IndexNotReadyException}으로 바꿔 알린다({@link InfluencerDocumentStore}).
 */
@Slf4j
@Component
@ConditionalOnSearchEnabled
public class InfluencerIndexSetup {

    private final ElasticsearchClient client;
    private final InfluencerIndexDefinition definition;
    private volatile boolean synonymsSynced;
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
     * 동의어 묶음을 맞추고 alias가 있는지 본다. 이미 준비됐으면 바로 돌아간다.
     *
     * @throws IndexNotReadyException alias가 없는 경우(처음 적재 전). 준비된 것으로 기억하지 않아 다음 호출에서 다시 본다
     * @throws IllegalStateException  ES에 닿지 못하거나 거절당한 경우
     */
    public void ensureReady() {
        if (ready) {
            return;
        }
        synchronized (this) {
            if (ready) {
                return;
            }
            syncSynonyms();
            // 처음 적재 전에는 이벤트마다 여기에 오므로 alias만 가볍게 본다(index 전체 조회 없음)
            Set<String> targets;
            try {
                targets = aliasTargets();
            } catch (IOException | ElasticsearchException e) {
                throw new IllegalStateException("검색 alias를 읽지 못했습니다 alias=" + InfluencerIndexDefinition.ALIAS, e);
            }
            if (targets.isEmpty()) {
                throw new IndexNotReadyException("검색 index를 처음 적재하기 전입니다 alias=" + InfluencerIndexDefinition.ALIAS);
            }
            if (!targets.equals(Set.of(definition.indexName()))) {
                log.warn("검색 index 설정이 바뀌었다. 재색인이 옮길 때까지 지금 index를 쓴다 alias={}, current={}, expected={}",
                        InfluencerIndexDefinition.ALIAS, targets, definition.indexName());
            }
            ready = true;
        }
    }

    /** 지금 설정의 index 이름 */
    public String indexName() {
        return definition.indexName();
    }

    /**
     * ES의 동의어 묶음을 레포 파일과 맞춘다. 한 번 맞추면 다시 묻지 않는다(쓰기 준비용).
     *
     * @throws IllegalStateException ES에 닿지 못하거나 거절당한 경우
     */
    public void syncSynonyms() {
        if (synonymsSynced) {
            return;
        }
        resyncSynonyms();
    }

    /**
     * 기억과 상관없이 ES의 동의어 묶음을 다시 확인해 맞춘다. 처음 적재·재색인이 index를 만들기 전에 부른다(pod가 떠 있는 동안 ES를 새로 만들었을 수 있다).
     *
     * @throws IllegalStateException ES에 닿지 못하거나 거절당한 경우
     */
    public synchronized void resyncSynonyms() {
        try {
            Map<String, String> expected = expectedSynonyms();
            if (!expected.equals(currentSynonyms())) {
                List<SynonymRule> rules = new ArrayList<>();
                expected.forEach((id, synonyms) -> rules.add(SynonymRule.of(r -> r.id(id).synonyms(synonyms))));
                client.synonyms().putSynonym(p -> p.id(InfluencerIndexDefinition.SYNONYMS_SET).synonymsSet(rules));
                log.info("검색 동의어 묶음을 바꿨다 synonymsSet={}, rules={}", InfluencerIndexDefinition.SYNONYMS_SET,
                        rules.size());
            }
        } catch (IOException | ElasticsearchException e) {
            throw new IllegalStateException("검색 동의어 묶음을 맞추지 못했습니다 synonymsSet="
                    + InfluencerIndexDefinition.SYNONYMS_SET, e);
        }
        synonymsSynced = true;
    }

    /**
     * alias가 가리키는 index와 검색 index 전부를 읽는다.
     *
     * @throws IllegalStateException ES에 닿지 못하거나 거절당한 경우
     */
    public IndexState state() {
        try {
            Set<String> targets = aliasTargets();
            Set<String> indices = client.indices().get(g -> g.index(InfluencerIndexDefinition.INDEX_PREFIX + "*")
                    .allowNoIndices(true)).indices().keySet();
            return new IndexState(targets, indices, definition.indexName());
        } catch (IOException | ElasticsearchException e) {
            throw new IllegalStateException("검색 index 상태를 읽지 못했습니다 alias=" + InfluencerIndexDefinition.ALIAS, e);
        }
    }

    /**
     * 지금 설정의 index를 alias 없이 만든다(처음 적재·재색인이 채울 곳).
     *
     * @return 만든 index 이름
     * @throws IllegalStateException 이미 있거나 만들지 못한 경우(남은 index는 부르는 쪽이 먼저 지운다)
     */
    public String createIndex() {
        String indexName = definition.indexName();
        try {
            client.indices().create(c -> c.index(indexName).withJson(new StringReader(definition.indexBody())));
            log.info("검색 index를 만들었다 index={}", indexName);
            return indexName;
        } catch (IOException | ElasticsearchException e) {
            throw new IllegalStateException("검색 index를 만들지 못했습니다 index=" + indexName, e);
        }
    }

    /**
     * index를 지운다. ES 9는 와일드카드 삭제를 막으므로 이름을 정확히 넘긴다.
     *
     * @throws IllegalStateException 지우지 못한 경우
     */
    public void deleteIndices(Collection<String> indices) {
        if (indices.isEmpty()) {
            return;
        }
        try {
            client.indices().delete(d -> d.index(List.copyOf(indices)));
            log.info("검색 index를 지웠다 indices={}", indices);
        } catch (IOException | ElasticsearchException e) {
            throw new IllegalStateException("검색 index를 지우지 못했습니다 indices=" + indices, e);
        }
    }

    /**
     * 쓴 문서가 검색·세기에 보이게 한다(세기 전에 부른다).
     *
     * @throws IllegalStateException 하지 못한 경우
     */
    public void refresh(String index) {
        try {
            client.indices().refresh(r -> r.index(index));
        } catch (IOException | ElasticsearchException e) {
            throw new IllegalStateException("검색 index를 refresh하지 못했습니다 index=" + index, e);
        }
    }

    /**
     * alias를 옛 index에서 새 index로 <b>한 요청으로</b> 옮긴다(remove + add). ES가 한 번에 적용하므로 검색이 alias를 잃거나 두 index를 함께
     * 보는 순간이 없다. 그사이 alias가 바뀌어 옛 index에 없으면 요청 전체가 거절되고 아무것도 바뀌지 않는다.
     *
     * @param from 지금 alias가 가리키는 index. 비었으면(처음 적재) 붙이기만 한다
     * @param to   새 index. 쓰기 index로 붙인다
     * @throws IllegalStateException 옮기지 못한 경우(alias는 그대로다)
     */
    public void switchAlias(Collection<String> from, String to) {
        List<Action> actions = new ArrayList<>();
        from.forEach(index -> actions.add(Action.of(a -> a.remove(r -> r.index(index)
                .alias(InfluencerIndexDefinition.ALIAS)))));
        actions.add(Action.of(a -> a.add(add -> add.index(to).alias(InfluencerIndexDefinition.ALIAS).isWriteIndex(true))));
        try {
            client.indices().updateAliases(u -> u.actions(actions));
            log.info("검색 alias를 옮겼다 alias={}, from={}, to={}", InfluencerIndexDefinition.ALIAS, from, to);
        } catch (IOException | ElasticsearchException e) {
            throw new IllegalStateException("검색 alias를 옮기지 못했습니다 alias=" + InfluencerIndexDefinition.ALIAS
                    + ", from=" + from + ", to=" + to, e);
        }
    }

    /** alias가 가리키는 index. alias가 없으면 빈 집합 */
    private Set<String> aliasTargets() throws IOException {
        return client.indices().existsAlias(a -> a.name(InfluencerIndexDefinition.ALIAS)).value()
                ? client.indices().getAlias(a -> a.name(InfluencerIndexDefinition.ALIAS)).aliases().keySet()
                : Set.of();
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
}
