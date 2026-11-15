package com.hapbang.auxiliary.search.common.out;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Conflicts;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.Result;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.json.JsonData;

/**
 * 검색 문서 쓰기에서 대상과 상관없는 부분(#771·#772·#773, 공통 코드 #868). 대상의 문서 저장소(예: {@code InfluencerDocumentStore})가 alias 하나로
 * 만들어 쓴다. 문서 ID는 원본 ID(숫자)이고, 숨김(tombstone)은 문서 필드 {@code hidden}·{@code hiddenAt}으로 표시한다.
 * <ul>
 *     <li>ES 오류 가르기: alias 없음 404 → {@link IndexNotReadyException}(건너뜀), 그 문서 값 때문의 거절 → {@link DocumentRejectedException}
 *     대상({@link #isRejectedByContent}), 그 밖은 장애</li>
 *     <li>bulk 결과를 문서 ID별 {@link WriteOutcome}으로</li>
 *     <li>보이는 문서 세기, 오래된 숨김 문서 지우기</li>
 * </ul>
 */
public class DocumentWrites {

    /**
     * 그 문서의 값 때문에 ES가 거절할 때의 오류 종류({@link DocumentRejectedException}, PR #833 리뷰 Q1). 같은 400이라도 script 오류
     * ({@code illegal_argument_exception} ← {@code script_exception})나 mapping에 없는 칸({@code strict_dynamic_mapping_exception})은 우리 배포
     * 실수라 모든 문서에 나므로 여기에 넣지 않는다(ES 9.5.5로 시험해 확인). {@code mapper_parsing_exception}은 예전 이름이다.
     */
    public static final Set<String> REJECTED_TYPES = Set.of("document_parsing_exception", "mapper_parsing_exception");

    /** 부분 갱신할 문서가 없을 때의 오류 종류 */
    public static final String DOCUMENT_MISSING = "document_missing_exception";

    /** index·alias가 없을 때의 오류 종류. alias로 쓰다 이것을 받으면 ES를 새로 만들어 alias가 사라진 것이다(#773 리뷰) */
    public static final String INDEX_NOT_FOUND = "index_not_found_exception";

    private final ElasticsearchClient client;
    private final String alias;

    /**
     * @param alias 이 대상의 검색·쓰기 alias
     */
    public DocumentWrites(ElasticsearchClient client, String alias) {
        this.client = client;
        this.alias = alias;
    }

    /** 이 대상의 alias */
    public String alias() {
        return alias;
    }

    /**
     * 이 index(또는 alias)에서 보이는(숨기지 않은) 문서 수. 정합성 점검(#772)과 처음 적재·재색인의 수 비교(#773)에 쓴다. 세기 전에 refresh해야 방금
     * 쓴 문서가 보인다.
     *
     * @param target alias면 alias 없음을 {@link IndexNotReadyException}으로 바꾼다
     * @throws IllegalStateException 세지 못한 경우
     */
    public long countVisible(String target) {
        try {
            return client.count(c -> c.index(target)
                    .query(q -> q.term(t -> t.field("hidden").value(false)))).count();
        } catch (ElasticsearchException e) {
            if (alias.equals(target)) {
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
            Long deleted = client.deleteByQuery(d -> d.index(alias)
                    // 정리하는 동안 그 문서의 새 변경이 문서를 바꾸면 그 문서는 남긴다
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

    /** alias가 없어(ES를 새로 만듦) 난 오류면 {@link IndexNotReadyException}을 던진다. 다음 처음 적재가 채운다 */
    public void throwIfAliasMissing(ElasticsearchException e) {
        if (e.status() == 404 && e.error() != null && INDEX_NOT_FOUND.equals(e.error().type())) {
            throw aliasMissing();
        }
    }

    /** alias가 없다는 예외(처음 적재 전) */
    public IndexNotReadyException aliasMissing() {
        return new IndexNotReadyException("검색 alias가 없습니다. 처음 적재 전입니다 alias=" + alias);
    }

    /**
     * bulk 항목별 결과. 문서 없음은 {@link WriteOutcome#MISSING}, 그 문서 값 때문에 거절은 {@link WriteOutcome#REJECTED}, 그 밖의 오류는
     * {@link WriteOutcome#FAILED}다
     */
    public static Map<Long, WriteOutcome> bulkOutcomes(BulkResponse response) {
        Map<Long, WriteOutcome> outcomes = new HashMap<>();
        for (BulkResponseItem item : response.items()) {
            long id = Long.parseLong(item.id());
            if (item.error() == null) {
                outcomes.put(id, switch (item.result()) {
                    case "created" -> WriteOutcome.CREATED;
                    case "noop" -> WriteOutcome.NOOP;
                    default -> WriteOutcome.UPDATED;
                });
            } else if (DOCUMENT_MISSING.equals(item.error().type())) {
                // 문서 없음만 건너뛴다. alias·index가 없어서 난 404(index_not_found_exception)는 실패로 센다
                outcomes.put(id, WriteOutcome.MISSING);
            } else if (REJECTED_TYPES.contains(item.error().type())) {
                outcomes.put(id, WriteOutcome.REJECTED);
            } else {
                outcomes.put(id, WriteOutcome.FAILED);
            }
        }
        return outcomes;
    }

    /** 실패한 bulk 항목의 "문서 ID:오류 종류"(문서 없음은 빼고) */
    public static List<String> failures(BulkResponse response) {
        List<String> failures = new ArrayList<>();
        for (BulkResponseItem item : response.items()) {
            if (item.error() != null && !DOCUMENT_MISSING.equals(item.error().type())) {
                failures.add(item.id() + ":" + item.error().type());
            }
        }
        return failures;
    }

    /** 그 문서의 값 때문에 거절됐는지(다시 써도 같다). 배포 실수·장애는 false다 */
    public static boolean isRejectedByContent(ElasticsearchException e) {
        return e.status() == 400 && e.error() != null && REJECTED_TYPES.contains(e.error().type());
    }

    /** 문서 하나 쓰기(update) 결과 */
    public static WriteOutcome outcome(Result result) {
        return switch (result) {
            case Created -> WriteOutcome.CREATED;
            case Updated -> WriteOutcome.UPDATED;
            case NoOp -> WriteOutcome.NOOP;
            default -> throw new IllegalStateException("예상하지 못한 쓰기 결과입니다 result=" + result);
        };
    }
}
