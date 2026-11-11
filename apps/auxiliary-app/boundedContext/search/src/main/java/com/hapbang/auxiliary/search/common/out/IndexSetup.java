package com.hapbang.auxiliary.search.common.out;

import java.io.IOException;
import java.io.StringReader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.indices.IndexSettings;
import co.elastic.clients.elasticsearch.indices.update_aliases.Action;
import lombok.extern.slf4j.Slf4j;

/**
 * 검색 index 하나의 index·alias를 다룬다(#771, 처음 적재·재색인 #773, 공통 코드 #868). 어느 index인지는 {@link IndexDefinition}으로 받는다.
 * <ul>
 *     <li>쓰기 준비({@link #ensureReady}): 동의어 묶음을 맞추고({@link SynonymsSetup}), alias가 있어야 준비된 것이다. alias가 없으면 만들지 않고
 *     {@link IndexNotReadyException}을 던진다. alias는 처음 적재가 index를 다 채우고 확인한 뒤에만 붙인다(#773 Q1). 빈 index에 alias를 먼저
 *     붙이면 "alias 있음, 설정 같음"으로 보여 처음 적재가 돌지 않는다.</li>
 *     <li>alias가 다른 설정의 index를 가리키면(mapping·사용자 사전이 바뀜) 재색인이 옮길 때까지 그 index에 쓴다.</li>
 *     <li>처음 적재·재색인용: 상태 보기, 새 index 만들기·지우기, refresh, alias 원자 교체.</li>
 * </ul>
 * 쓰기 준비는 한 번 되면 다시 ES에 묻지 않는다. 실패하면 예외를 던지고, 다음 호출(이벤트 소비·배치)에서 다시 한다. 준비된 뒤 ES를 새로 만들어
 * alias가 사라지면 문서 쓰기가 alias 없음(404)을 {@link IndexNotReadyException}으로 바꿔 알린다({@link DocumentWrites}).
 * <p>
 * 대상마다 정의만 채운 하위 클래스를 빈으로 둔다(예: {@code InfluencerIndexSetup}, #868 R7). 하위 클래스는 생성자만 두고, 동작은 여기서 바꾼다.
 * 메서드를 {@code final}로 막지 않은 것은 테스트가 익명 클래스로 장애(index 생성 실패, alias 교체 시간 초과)를 흉내 내기 때문이다.
 */
@Slf4j
public class IndexSetup {

    private final ElasticsearchClient client;
    private final IndexDefinition definition;
    private final SynonymsSetup synonyms;
    private volatile boolean ready;

    public IndexSetup(ElasticsearchClient client, IndexDefinition definition, SynonymsSetup synonyms) {
        this.client = client;
        this.definition = definition;
        this.synonyms = synonyms;
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
            try {
                syncSynonyms();
            } catch (SynonymsRejectedException e) {
                // 쓰기는 동의어와 상관없다. ES의 지난 묶음으로 검색은 그대로 되고, 재색인 확인이 10분마다 ERROR로 알린다(PR #845 재리뷰)
                log.warn("검색 동의어 묶음을 ES가 거절해 지난 묶음으로 문서 쓰기를 계속한다", e);
            }
            // 처음 적재 전에는 이벤트마다 여기에 오므로 alias만 가볍게 본다(index 전체 조회 없음)
            Set<String> targets;
            try {
                targets = aliasTargets();
            } catch (IOException | ElasticsearchException e) {
                throw new IllegalStateException("검색 alias를 읽지 못했습니다 alias=" + definition.alias(), e);
            }
            if (targets.isEmpty()) {
                throw new IndexNotReadyException("검색 index를 처음 적재하기 전입니다 alias=" + definition.alias());
            }
            if (!targets.equals(Set.of(definition.indexName()))) {
                log.warn("검색 index 설정이 바뀌었다. 재색인이 옮길 때까지 지금 index를 쓴다 alias={}, current={}, expected={}",
                        definition.alias(), targets, definition.indexName());
            }
            ready = true;
        }
    }

    /** 검색·쓰기에 쓰는 alias */
    public String alias() {
        return definition.alias();
    }

    /** 지금 설정의 index 이름 */
    public String indexName() {
        return definition.indexName();
    }

    /**
     * ES의 동의어 묶음을 레포 파일과 맞춘다. 한 번 맞추면 다시 묻지 않는다(쓰기 준비용). 묶음은 모든 대상이 함께 쓴다({@link SynonymsSetup#sync}).
     *
     * @throws IllegalStateException ES에 닿지 못하거나 거절당한 경우
     */
    public void syncSynonyms() {
        synonyms.sync();
    }

    /**
     * 기억과 상관없이 ES의 동의어 묶음을 다시 확인해 맞춘다({@link SynonymsSetup#resync}). 처음 적재·재색인이 index를 만들기 전에 부른다.
     *
     * @throws SynonymsRejectedException ES가 묶음을 내용 때문에 거절한 경우(4xx, 429 제외). 파일을 고쳐야 한다
     * @throws IllegalStateException     ES에 닿지 못하거나 5xx·429인 경우(기다리면 풀린다)
     */
    public void resyncSynonyms() {
        synonyms.resync();
    }

    /**
     * alias가 가리키는 index와 이 대상의 index 전부를 읽는다.
     *
     * @throws IllegalStateException ES에 닿지 못하거나 거절당한 경우
     */
    public IndexState state() {
        try {
            Set<String> targets = aliasTargets();
            Map<String, co.elastic.clients.elasticsearch.indices.IndexState> indices = client.indices()
                    .get(g -> g.index(definition.indexPrefix() + "*").allowNoIndices(true)).indices();
            Map<String, Instant> createdAt = new HashMap<>();
            indices.forEach((name, index) -> creationDate(index).ifPresent(at -> createdAt.put(name, at)));
            return new IndexState(targets, indices.keySet(), definition.indexName(), createdAt);
        } catch (IOException | ElasticsearchException e) {
            throw new IllegalStateException("검색 index 상태를 읽지 못했습니다 alias=" + definition.alias(), e);
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
        String alias = definition.alias();
        List<Action> actions = new ArrayList<>();
        from.forEach(index -> actions.add(Action.of(a -> a.remove(r -> r.index(index).alias(alias)))));
        actions.add(Action.of(a -> a.add(add -> add.index(to).alias(alias).isWriteIndex(true))));
        try {
            client.indices().updateAliases(u -> u.actions(actions));
            log.info("검색 alias를 옮겼다 alias={}, from={}, to={}", alias, from, to);
        } catch (IOException | ElasticsearchException e) {
            throw new IllegalStateException("검색 alias를 옮기지 못했습니다 alias=" + alias + ", from=" + from + ", to=" + to, e);
        }
    }

    /** ES가 index를 만든 시각({@code index.creation_date}). 설정이 {@code index} 아래에 들어 있어도 읽는다 */
    private static Optional<Instant> creationDate(co.elastic.clients.elasticsearch.indices.IndexState index) {
        IndexSettings settings = index.settings();
        if (settings == null) {
            return Optional.empty();
        }
        Long millis = settings.creationDate() != null ? settings.creationDate()
                : settings.index() != null ? settings.index().creationDate() : null;
        return Optional.ofNullable(millis).map(Instant::ofEpochMilli);
    }

    /** alias가 가리키는 index. alias가 없으면 빈 집합 */
    private Set<String> aliasTargets() throws IOException {
        String alias = definition.alias();
        return client.indices().existsAlias(a -> a.name(alias)).value()
                ? client.indices().getAlias(a -> a.name(alias)).aliases().keySet()
                : Set.of();
    }
}
