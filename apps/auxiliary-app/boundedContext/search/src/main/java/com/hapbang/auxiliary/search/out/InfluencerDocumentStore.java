package com.hapbang.auxiliary.search.out;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.search.config.ConditionalOnSearchEnabled;
import com.hapbang.auxiliary.search.config.SearchElasticsearchConfig;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Conflicts;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.Result;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.MgetResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.get.GetResult;
import co.elastic.clients.elasticsearch.core.mget.MultiGetResponseItem;
import co.elastic.clients.json.JsonData;
import lombok.extern.slf4j.Slf4j;

/**
 * 인플루언서 검색 문서 쓰기(#771). 문서 ID는 회원 ID이고, 모든 쓰기는 alias({@link InfluencerIndexDefinition#ALIAS})로만 한다
 * ({@code require_alias}: alias가 없을 때 ES가 이름이 같은 index를 멋대로 만들지 않게).
 * <p>
 * <b>회원 version 비교</b>: ES external versioning은 쓰지 않는다. 10분 통계 부분 갱신이 문서 버전을 올려 다음 회원 이벤트를 거절하게
 * 만들기 때문이다(#771 시험 결과). 회원 version은 문서 필드 {@code memberVersion}에 두고, 회원 이벤트 쓰기는 painless scripted upsert로
 * "들어온 version이 더 클 때만" 반영한다. 순서가 뒤바뀐 이벤트, 같은 이벤트 두 번, 숨긴 뒤 늦게 온 옛 이벤트는 모두 noop이다.
 */
@Slf4j
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
     *     <li>{@code userId}(문서 ID와 같은 값)를 늘 쓴다. 검색 정렬의 마지막 키다(동점이어도 페이지를 넘길 때 순서가 같게).</li>
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
            ctx._source.userId = params.userId;
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

    /** 증분 보정이 반영 전에 읽는 회원 이벤트 필드({@link IndexedMember}) */
    static final List<String> MEMBER_FIELDS = List.of("memberVersion", "hidden", "nickname", "bio", "role",
            "contentCategories.code");

    /**
     * 그 회원의 값 때문에 ES가 거절할 때의 오류 종류({@link DocumentRejectedException}, PR #833 리뷰 Q1). 같은 400이라도 script 오류
     * ({@code illegal_argument_exception} ← {@code script_exception})나 mapping에 없는 칸({@code strict_dynamic_mapping_exception})은 우리 배포
     * 실수라 모든 회원에게 나므로 여기에 넣지 않는다(ES 9.5.5로 시험해 확인). {@code mapper_parsing_exception}은 예전 이름이다.
     */
    static final Set<String> REJECTED_TYPES = Set.of("document_parsing_exception", "mapper_parsing_exception");

    /** 부분 갱신할 문서가 없을 때의 오류 종류 */
    static final String DOCUMENT_MISSING = "document_missing_exception";

    /** index·alias가 없을 때의 오류 종류. alias로 쓰다 이것을 받으면 ES를 새로 만들어 alias가 사라진 것이다(#773 리뷰) */
    static final String INDEX_NOT_FOUND = "index_not_found_exception";

    private final ElasticsearchClient client;

    public InfluencerDocumentStore(@Qualifier(SearchElasticsearchConfig.CLIENT) ElasticsearchClient client) {
        this.client = client;
    }

    /**
     * 정상 인플루언서의 프로필을 넣거나 덮는다(숨김을 푼다).
     *
     * @param version 회원 version. 모르면 null
     * @throws DocumentRejectedException ES가 그 회원의 값 때문에 거절한 경우(다시 써도 같다)
     * @throws IllegalStateException     그 밖에 쓰지 못한 경우
     */
    public WriteOutcome show(long userId, Long version, MemberProfile profile) {
        return writeMember(userId, version, true, profileFields(profile), Instant.EPOCH);
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
     * 회원들의 문서에 지금 들어 있는 회원 이벤트 필드를 한 번에(mget) 읽는다(#772 증분 보정, 페이지마다 1번).
     *
     * @return 문서가 있는 회원만 담는다(없는 회원은 빠진다)
     * @throws IllegalStateException 읽지 못한 경우(alias가 없는 경우 포함)
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Map<Long, IndexedMember> findMembers(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        try {
            MgetResponse<Map> response = client.mget(m -> m.index(InfluencerIndexDefinition.ALIAS)
                    .ids(userIds.stream().map(String::valueOf).toList())
                    .sourceIncludes(MEMBER_FIELDS), Map.class);
            Map<Long, IndexedMember> found = new HashMap<>();
            for (MultiGetResponseItem<Map> item : response.docs()) {
                if (item.isFailure()) {
                    if (INDEX_NOT_FOUND.equals(item.failure().error().type())) {
                        throw aliasMissing();
                    }
                    throw new IllegalStateException("검색 문서를 읽지 못했습니다 userId=" + item.failure().id()
                            + ", error=" + item.failure().error().type());
                }
                GetResult<Map> result = item.result();
                if (result.found() && result.source() != null) {
                    found.put(Long.parseLong(result.id()), indexedMember(result.source()));
                }
            }
            return found;
        } catch (ElasticsearchException e) {
            throwIfAliasMissing(e);
            throw new IllegalStateException("검색 문서를 읽지 못했습니다", e);
        } catch (IOException e) {
            throw new IllegalStateException("검색 문서를 읽지 못했습니다", e);
        }
    }

    /**
     * 검색에 보이는(숨기지 않은) 문서 수(#772 정합성 점검). 원본의 정상 인플루언서 수와 비교한다.
     *
     * @throws IllegalStateException 세지 못한 경우
     */
    public long countVisible() {
        return countVisible(InfluencerIndexDefinition.ALIAS);
    }

    /**
     * 이 index(또는 alias)에서 보이는 문서 수(#773: alias를 옮기기 전에 새 index를 센다). 세기 전에 refresh해야 방금 쓴 문서가 보인다.
     *
     * @throws IllegalStateException 세지 못한 경우
     */
    public long countVisible(String target) {
        try {
            return client.count(c -> c.index(target)
                    .query(q -> q.term(t -> t.field("hidden").value(false)))).count();
        } catch (ElasticsearchException e) {
            if (InfluencerIndexDefinition.ALIAS.equals(target)) {
                throwIfAliasMissing(e);
            }
            throw new IllegalStateException("보이는 검색 문서 수를 세지 못했습니다 target=" + target, e);
        } catch (IOException e) {
            throw new IllegalStateException("보이는 검색 문서 수를 세지 못했습니다 target=" + target, e);
        }
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
        } catch (ElasticsearchException e) {
            throwIfAliasMissing(e);
            throw new IllegalStateException("숨긴 검색 문서를 정리하지 못했습니다", e);
        } catch (IOException e) {
            throw new IllegalStateException("숨긴 검색 문서를 정리하지 못했습니다", e);
        }
    }

    /**
     * 통계를 한 번에(bulk) 부분 갱신한다. 문서가 없는 회원은 건너뛴다(넣는 것은 검색 2의 보정). 숨긴 문서와 값이 같은 문서는 noop이다.
     *
     * 문서 없음({@value #DOCUMENT_MISSING}) 밖의 이유로 실패한 항목은 {@link WriteOutcome#FAILED}로 돌려주고 나머지는 그대로 반영한다.
     * FAILED에는 alias가 사라진 404와 겹친 쓰기가 재시도를 넘긴 경우 등이 들어간다.
     * 한 회원 때문에 같은 페이지·뒤 페이지 회원이 갱신되지 못하면 안 된다. 실패한 회원은 다음 회차에 다시 쓴다.
     *
     * @return 회원 ID별 결과
     * @throws IllegalStateException 요청 자체를 보내지 못한 경우
     */
    public Map<Long, WriteOutcome> applyStats(List<InfluencerStats> stats) {
        return applyStats(InfluencerIndexDefinition.ALIAS, stats);
    }

    /**
     * 이 index(또는 alias)에 통계를 부분 갱신한다(#773: alias를 옮기기 전에 새 index를 채운다). 규칙은 {@link #applyStats(List)}와 같다.
     *
     * @param target alias면 {@code require_alias}로 쓴다
     */
    public Map<Long, WriteOutcome> applyStats(String target, List<InfluencerStats> stats) {
        if (stats.isEmpty()) {
            return Map.of();
        }
        try {
            BulkResponse response = client.bulk(b -> {
                b.index(target).requireAlias(InfluencerIndexDefinition.ALIAS.equals(target));
                stats.forEach(s -> b.operations(op -> op.update(u -> u.id(String.valueOf(s.userId())).retryOnConflict(3)
                        .action(a -> a.script(script -> script.source(src -> src.scriptString(STATS_SCRIPT))
                                .params("stats", JsonData.of(statsFields(s))))))));
                return b;
            });
            Map<Long, WriteOutcome> outcomes = bulkOutcomes(response);
            List<String> failures = failures(response);
            if (!failures.isEmpty()) {
                log.warn("검색 통계 일부를 쓰지 못해 다음 회차에 다시 쓴다 target={}, failures={}", target, failures);
            }
            return outcomes;
        } catch (IOException | ElasticsearchException e) {
            throw new IllegalStateException("검색 통계를 쓰지 못했습니다 target=" + target, e);
        }
    }

    /**
     * 회원들을 이름을 정한 index에 한 번에(bulk) 넣는다(#773 처음 적재·재색인). alias가 아니라 아직 alias가 없는 새 index에 쓴다. 정상 인플루언서는
     * 보이는 문서, 그 밖의 회원은 숨김 문서다. 회원 이벤트와 같은 script(회원 version 조건부 쓰기)라 같은 회원이 두 번 와도 더 새 version만 남는다.
     * <p>
     * index를 먼저 만들고 부른다. bulk에는 "index가 있어야 한다"는 옵션이 없어, 없는 index에 넣으면 ES가 기본 설정(nori·mapping 없음)으로
     * 만들어 버린다.
     *
     * @param index 새 index 이름
     * @return 회원 ID별 결과. ES가 그 회원 값 때문에 거절했으면 {@link WriteOutcome#REJECTED}, 그 밖에 실패했으면 {@link WriteOutcome#FAILED}
     * (둘 다 WARN을 남긴다)
     * @throws IllegalStateException 요청 자체를 보내지 못한 경우
     */
    public Map<Long, WriteOutcome> loadMembers(String index, List<MemberDocument> members) {
        if (members.isEmpty()) {
            return Map.of();
        }
        try {
            BulkResponse response = client.bulk(b -> {
                b.index(index);
                members.forEach(m -> b.operations(op -> op.update(u -> u.id(String.valueOf(m.userId()))
                        .action(a -> a.scriptedUpsert(true).upsert(Map.of())
                                .script(script -> script.source(src -> src.scriptString(MEMBER_SCRIPT))
                                        .params(memberParams(m.userId(), m.version(), m.isVisible(),
                                                m.isVisible() ? profileFields(m.profile()) : Map.of(),
                                                m.isVisible() ? Instant.EPOCH : m.hiddenAt())))))));
                return b;
            });
            Map<Long, WriteOutcome> outcomes = bulkOutcomes(response);
            List<String> failures = failures(response);
            if (!failures.isEmpty()) {
                log.warn("검색 index에 회원 일부를 넣지 못했다 index={}, failures={}", index, failures);
            }
            return outcomes;
        } catch (IOException | ElasticsearchException e) {
            throw new IllegalStateException("검색 index에 회원을 넣지 못했습니다 index=" + index, e);
        }
    }

    /**
     * bulk 항목별 결과. 문서 없음은 {@link WriteOutcome#MISSING}, 그 회원 값 때문에 거절은 {@link WriteOutcome#REJECTED}, 그 밖의 오류는
     * {@link WriteOutcome#FAILED}다
     */
    private static Map<Long, WriteOutcome> bulkOutcomes(BulkResponse response) {
        Map<Long, WriteOutcome> outcomes = new HashMap<>();
        for (BulkResponseItem item : response.items()) {
            long userId = Long.parseLong(item.id());
            if (item.error() == null) {
                outcomes.put(userId, switch (item.result()) {
                    case "created" -> WriteOutcome.CREATED;
                    case "noop" -> WriteOutcome.NOOP;
                    default -> WriteOutcome.UPDATED;
                });
            } else if (DOCUMENT_MISSING.equals(item.error().type())) {
                // 문서 없음만 건너뛴다. alias·index가 없어서 난 404(index_not_found_exception)는 실패로 센다
                outcomes.put(userId, WriteOutcome.MISSING);
            } else if (REJECTED_TYPES.contains(item.error().type())) {
                outcomes.put(userId, WriteOutcome.REJECTED);
            } else {
                outcomes.put(userId, WriteOutcome.FAILED);
            }
        }
        return outcomes;
    }

    /** 실패한 bulk 항목의 "회원 ID:오류 종류"(문서 없음은 빼고) */
    private static List<String> failures(BulkResponse response) {
        List<String> failures = new ArrayList<>();
        for (BulkResponseItem item : response.items()) {
            if (item.error() != null && !DOCUMENT_MISSING.equals(item.error().type())) {
                failures.add(item.id() + ":" + item.error().type());
            }
        }
        return failures;
    }

    /**
     * 보이는 문서 중 분야가 있는 문서의 분야 이름을 지금 목록으로 맞춘다(update_by_query). 이름이 바뀐 분야와, 이름을 모른 채 색인한 분야를 함께
     * 고친다. 숨긴 문서는 일반 유저 가입마다 늘어나므로 훑지 않는다(다시 보이게 될 때 회원 이벤트가 지금 이름으로 쓴다).
     *
     * @return 바꾼 문서 수
     */
    public long syncCategoryNames(Map<String, String> names) {
        try {
            Long updated = client.updateByQuery(u -> u.index(InfluencerIndexDefinition.ALIAS)
                    // 그사이 회원 이벤트가 바꾼 문서는 그 이벤트가 지금 이름으로 썼다
                    .conflicts(Conflicts.Proceed)
                    .query(q -> q.bool(b -> b
                            .filter(f -> f.term(t -> t.field("hidden").value(false)))
                            .filter(f -> f.exists(e -> e.field("contentCategories.code")))))
                    .script(s -> s.source(src -> src.scriptString(CATEGORY_NAMES_SCRIPT))
                            .params("names", JsonData.of(names))))
                    .updated();
            return updated == null ? 0 : updated;
        } catch (ElasticsearchException e) {
            throwIfAliasMissing(e);
            throw new IllegalStateException("검색 문서의 분야 이름을 맞추지 못했습니다", e);
        } catch (IOException e) {
            throw new IllegalStateException("검색 문서의 분야 이름을 맞추지 못했습니다", e);
        }
    }

    private static IndexedMember indexedMember(Map<String, Object> source) {
        Object version = source.get("memberVersion");
        Object categories = source.get("contentCategories");
        List<String> codes = categories instanceof List<?> list
                ? list.stream().filter(Map.class::isInstance).map(category -> (String) ((Map<?, ?>) category).get("code"))
                .toList()
                : List.of();
        return new IndexedMember(version == null ? null : ((Number) version).longValue(),
                Boolean.TRUE.equals(source.get("hidden")), (String) source.get("nickname"), (String) source.get("bio"),
                (String) source.get("role"), codes);
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

    /** 프로필을 문서 필드로 */
    private static Map<String, Object> profileFields(MemberProfile profile) {
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
        return fields;
    }

    /** {@link #MEMBER_SCRIPT}에 넘기는 값 */
    private static Map<String, JsonData> memberParams(long userId, Long version, boolean visible,
            Map<String, Object> profile, Instant hiddenAt) {
        Map<String, JsonData> params = new HashMap<>();
        params.put("userId", JsonData.of(userId));
        params.put("hasVersion", JsonData.of(version != null));
        params.put("version", JsonData.of(version == null ? 0L : version));
        params.put("visible", JsonData.of(visible));
        params.put("profile", JsonData.of(profile));
        params.put("personal", JsonData.of(PERSONAL_FIELDS));
        params.put("now", JsonData.of(hiddenAt.toEpochMilli()));
        return params;
    }

    private WriteOutcome writeMember(long userId, Long version, boolean visible, Map<String, Object> profile,
            Instant hiddenAt) {
        Map<String, JsonData> params = memberParams(userId, version, visible, profile, hiddenAt);
        try {
            Result result = client.update(u -> u.index(InfluencerIndexDefinition.ALIAS).id(String.valueOf(userId))
                    .requireAlias(true)
                    // 같은 문서를 통계 동기화가 함께 바꾸면 다시 읽어 script를 다시 돈다
                    .retryOnConflict(3)
                    .scriptedUpsert(true).upsert(Map.of())
                    .script(s -> s.source(src -> src.scriptString(MEMBER_SCRIPT)).params(params)), Map.class)
                    .result();
            return outcome(result);
        } catch (ElasticsearchException e) {
            throwIfAliasMissing(e);
            if (isRejectedByContent(e)) {
                throw new DocumentRejectedException("ES가 검색 문서를 거절했습니다 userId=" + userId + ", error="
                        + e.error().type(), e);
            }
            throw new IllegalStateException("검색 문서를 쓰지 못했습니다 userId=" + userId, e);
        } catch (IOException e) {
            throw new IllegalStateException("검색 문서를 쓰지 못했습니다 userId=" + userId, e);
        }
    }

    /** alias가 없어(ES를 새로 만듦) 난 오류면 {@link IndexNotReadyException}을 던진다. 다음 처음 적재가 채운다 */
    private static void throwIfAliasMissing(ElasticsearchException e) {
        if (e.status() == 404 && e.error() != null && INDEX_NOT_FOUND.equals(e.error().type())) {
            throw aliasMissing();
        }
    }

    private static IndexNotReadyException aliasMissing() {
        return new IndexNotReadyException("검색 alias가 없습니다. 처음 적재 전입니다 alias=" + InfluencerIndexDefinition.ALIAS);
    }

    /** 그 회원의 값 때문에 거절됐는지(다시 써도 같다). 배포 실수·장애는 false다 */
    static boolean isRejectedByContent(ElasticsearchException e) {
        return e.status() == 400 && e.error() != null && REJECTED_TYPES.contains(e.error().type());
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
