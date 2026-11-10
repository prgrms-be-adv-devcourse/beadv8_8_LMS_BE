package com.hapbang.auxiliary.search.out;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.search.config.ConditionalOnSearchEnabled;
import com.hapbang.auxiliary.search.config.SearchElasticsearchConfig;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Conflicts;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.Result;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.json.JsonData;

/**
 * 인플루언서 검색 문서 쓰기(#771). 문서 ID는 회원 ID이고, 모든 쓰기는 alias({@link InfluencerIndexDefinition#ALIAS})로만 한다
 * ({@code require_alias}: alias가 없을 때 ES가 이름이 같은 index를 멋대로 만들지 않게).
 * <p>
 * <b>회원 version 비교</b>: ES external versioning은 쓰지 않는다. 10분 통계 부분 갱신이 문서 버전을 올려 다음 회원 이벤트를 거절하게
 * 만들기 때문이다(#771 시험 결과). 회원 version은 문서 필드 {@code memberVersion}에 두고, 회원 이벤트 쓰기는 painless scripted upsert로
 * "들어온 version이 더 클 때만" 반영한다. 순서가 뒤바뀐 이벤트, 같은 이벤트 두 번, 숨긴 뒤 늦게 온 옛 이벤트는 모두 noop이다.
 */
@Component
@ConditionalOnSearchEnabled
public class InfluencerDocumentStore {

    /** 숨길 때 비우는 개인정보 필드 */
    static final List<String> PERSONAL_FIELDS = List.of("nickname", "bio", "channelNames", "representativeChannelName");

    /**
     * 회원 이벤트 반영 script. 문서가 없으면 {@code ctx._source}가 빈 문서(scripted upsert)다.
     * <ul>
     *     <li>version이 있으면: 문서의 memberVersion이 그보다 작을 때만 반영한다(같거나 크면 noop).</li>
     *     <li>version이 없으면({@code UserDto} 규칙): 문서에 memberVersion이 없을 때만 반영하고, 있으면 버전 도입 전 옛 이벤트로 보고 noop.</li>
     *     <li>보이기: 숨김을 풀고 프로필 필드를 덮는다. 새 문서의 팔로워·리뷰 수는 0으로 시작한다(통계 동기화가 채운다).</li>
     *     <li>숨기기(tombstone): 문서가 없어도 만든다(#771 Q6). 숨김과 시각을 남기고 개인정보 필드를 지운다.</li>
     * </ul>
     */
    static final String MEMBER_SCRIPT = """
            def current = ctx._source.memberVersion;
            if (params.hasVersion) {
              long incoming = ((Number) params.version).longValue();
              if (current != null && ((Number) current).longValue() >= incoming) { ctx.op = 'noop'; return; }
              ctx._source.memberVersion = incoming;
            } else if (current != null) {
              ctx.op = 'noop'; return;
            }
            if (params.visible) {
              ctx._source.hidden = false;
              ctx._source.remove('hiddenAt');
              for (def field : params.profile.entrySet()) { ctx._source[field.getKey()] = field.getValue(); }
              if (ctx._source.followerCount == null) { ctx._source.followerCount = 0L; }
              if (ctx._source.ratingCount == null) { ctx._source.ratingCount = 0L; }
            } else {
              ctx._source.hidden = true;
              ctx._source.hiddenAt = params.now;
              for (def field : params.personal) { ctx._source.remove(field); }
            }
            """;

    /**
     * 통계 반영 script. 숨긴 문서(tombstone)에는 채널 이름(개인정보)을 다시 넣지 않는다. 값이 모두 같으면 noop이다(painless의 {@code ==}는
     * 숫자 타입이 달라도 값으로, 목록은 원소로 비교한다). 회원 version은 건드리지 않는다.
     */
    static final String STATS_SCRIPT = """
            if (ctx._source.hidden == true) { ctx.op = 'noop'; return; }
            boolean changed = false;
            for (def field : params.stats.entrySet()) {
              if (ctx._source[field.getKey()] != field.getValue()) { ctx._source[field.getKey()] = field.getValue(); changed = true; }
            }
            if (!changed) { ctx.op = 'noop'; }
            """;

    /** 분야 이름 반영 script. 문서의 분야마다 지금 이름을 붙이고(모르는 코드는 이름 없음), 바뀐 것이 없으면 noop이다 */
    static final String CATEGORY_NAMES_SCRIPT = """
            boolean changed = false;
            for (def category : ctx._source.contentCategories) {
              def name = params.names.get(category.code);
              if (category.name != name) { category.name = name; changed = true; }
            }
            if (!changed) { ctx.op = 'noop'; }
            """;

    private final ElasticsearchClient client;

    public InfluencerDocumentStore(@Qualifier(SearchElasticsearchConfig.CLIENT) ElasticsearchClient client) {
        this.client = client;
    }

    /**
     * 정상 인플루언서의 프로필을 넣거나 덮는다(숨김을 푼다).
     *
     * @param version 회원 version. 모르면 null
     */
    public WriteOutcome show(long userId, Long version, MemberProfile profile) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("nickname", profile.nickname());
        fields.put("bio", profile.bio());
        fields.put("role", profile.role());
        fields.put("verified", profile.verified());
        fields.put("contentCategories", profile.contentCategories().stream().map(category -> {
            Map<String, Object> value = new HashMap<>();
            value.put("code", category.code());
            value.put("name", category.name());
            return value;
        }).toList());
        return writeMember(userId, version, true, fields, Instant.EPOCH);
    }

    /**
     * 정상 인플루언서가 아닌 회원을 숨긴다(tombstone). 문서가 없어도 숨김 문서를 남겨 늦게 온 옛 이벤트를 막는다.
     *
     * @param version  회원 version. 모르면 null
     * @param hiddenAt 숨긴 시각(7일 뒤 정리 기준)
     */
    public WriteOutcome hide(long userId, Long version, Instant hiddenAt) {
        return writeMember(userId, version, false, Map.of(), hiddenAt);
    }

    /**
     * 숨긴 지 오래된 tombstone을 지운다.
     *
     * @param hiddenBefore 이 시각보다 먼저 숨긴 문서
     * @return 지운 문서 수
     */
    public long deleteHiddenBefore(Instant hiddenBefore) {
        try {
            Long deleted = client.deleteByQuery(d -> d.index(InfluencerIndexDefinition.ALIAS)
                    // 정리하는 동안 그 회원의 새 이벤트가 문서를 바꾸면 그 문서는 남긴다
                    .conflicts(Conflicts.Proceed)
                    .query(q -> q.bool(b -> b
                            .filter(f -> f.term(t -> t.field("hidden").value(true)))
                            .filter(f -> f.range(r -> r.untyped(u -> u.field("hiddenAt")
                                    .lt(JsonData.of(hiddenBefore.toEpochMilli()))))))))
                    .deleted();
            return deleted == null ? 0 : deleted;
        } catch (IOException | ElasticsearchException e) {
            throw new IllegalStateException("숨긴 검색 문서를 정리하지 못했습니다", e);
        }
    }

    /**
     * 통계를 한 번에(bulk) 부분 갱신한다. 문서가 없는 회원은 건너뛴다(넣는 것은 검색 2의 보정). 숨긴 문서와 값이 같은 문서는 noop이다.
     *
     * @return 회원 ID별 결과
     * @throws IllegalStateException ES에 보내지 못했거나, 문서 없음 밖의 이유로 실패한 항목이 있는 경우
     */
    public Map<Long, WriteOutcome> applyStats(List<InfluencerStats> stats) {
        if (stats.isEmpty()) {
            return Map.of();
        }
        try {
            BulkResponse response = client.bulk(b -> {
                b.index(InfluencerIndexDefinition.ALIAS).requireAlias(true);
                stats.forEach(s -> b.operations(op -> op.update(u -> u.id(String.valueOf(s.userId())).retryOnConflict(3)
                        .action(a -> a.script(script -> script.source(src -> src.scriptString(STATS_SCRIPT))
                                .params("stats", JsonData.of(statsFields(s))))))));
                return b;
            });
            Map<Long, WriteOutcome> outcomes = new HashMap<>();
            List<String> failures = new ArrayList<>();
            for (BulkResponseItem item : response.items()) {
                long userId = Long.parseLong(item.id());
                if (item.error() == null) {
                    outcomes.put(userId, "noop".equals(item.result()) ? WriteOutcome.NOOP : WriteOutcome.UPDATED);
                } else if (item.status() == 404) {
                    outcomes.put(userId, WriteOutcome.MISSING);
                } else {
                    failures.add(item.id() + ":" + item.error().type());
                }
            }
            if (!failures.isEmpty()) {
                throw new IllegalStateException("검색 통계 일부를 쓰지 못했습니다 failures=" + failures);
            }
            return outcomes;
        } catch (IOException | ElasticsearchException e) {
            throw new IllegalStateException("검색 통계를 쓰지 못했습니다", e);
        }
    }

    /**
     * 분야가 있는 모든 문서의 분야 이름을 지금 목록으로 맞춘다(update_by_query). 이름이 바뀐 분야와, 이름을 모른 채 색인한 분야를 함께 고친다.
     *
     * @return 바꾼 문서 수
     */
    public long syncCategoryNames(Map<String, String> names) {
        try {
            Long updated = client.updateByQuery(u -> u.index(InfluencerIndexDefinition.ALIAS)
                    // 그사이 회원 이벤트가 바꾼 문서는 그 이벤트가 지금 이름으로 썼다
                    .conflicts(Conflicts.Proceed)
                    .query(q -> q.exists(e -> e.field("contentCategories.code")))
                    .script(s -> s.source(src -> src.scriptString(CATEGORY_NAMES_SCRIPT))
                            .params("names", JsonData.of(names))))
                    .updated();
            return updated == null ? 0 : updated;
        } catch (IOException | ElasticsearchException e) {
            throw new IllegalStateException("검색 문서의 분야 이름을 맞추지 못했습니다", e);
        }
    }

    private static Map<String, Object> statsFields(InfluencerStats stats) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("channelNames", stats.channelNames());
        fields.put("representativeChannelName", stats.representativeChannelName());
        fields.put("followerCount", stats.followerCount());
        fields.put("ratingAverage", stats.ratingAverage());
        fields.put("ratingCount", stats.ratingCount());
        return fields;
    }

    private WriteOutcome writeMember(long userId, Long version, boolean visible, Map<String, Object> profile,
            Instant hiddenAt) {
        Map<String, JsonData> params = new HashMap<>();
        params.put("hasVersion", JsonData.of(version != null));
        params.put("version", JsonData.of(version == null ? 0L : version));
        params.put("visible", JsonData.of(visible));
        params.put("profile", JsonData.of(profile));
        params.put("personal", JsonData.of(PERSONAL_FIELDS));
        params.put("now", JsonData.of(hiddenAt.toEpochMilli()));
        try {
            Result result = client.update(u -> u.index(InfluencerIndexDefinition.ALIAS).id(String.valueOf(userId))
                    .requireAlias(true)
                    // 같은 문서를 통계 동기화가 함께 바꾸면 다시 읽어 script를 다시 돈다
                    .retryOnConflict(3)
                    .scriptedUpsert(true).upsert(Map.of())
                    .script(s -> s.source(src -> src.scriptString(MEMBER_SCRIPT)).params(params)), Map.class)
                    .result();
            return outcome(result);
        } catch (IOException | ElasticsearchException e) {
            throw new IllegalStateException("검색 문서를 쓰지 못했습니다 userId=" + userId, e);
        }
    }

    static WriteOutcome outcome(Result result) {
        return switch (result) {
            case Created -> WriteOutcome.CREATED;
            case Updated -> WriteOutcome.UPDATED;
            case NoOp -> WriteOutcome.NOOP;
            default -> throw new IllegalStateException("예상하지 못한 쓰기 결과입니다 result=" + result);
        };
    }
}
