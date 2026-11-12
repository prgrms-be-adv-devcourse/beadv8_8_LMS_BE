package com.hapbang.auxiliary.search.lecture.out;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.search.common.out.DocumentRejectedException;
import com.hapbang.auxiliary.search.common.out.DocumentWrites;
import com.hapbang.auxiliary.search.common.out.WriteOutcome;
import com.hapbang.auxiliary.search.config.SearchElasticsearchConfig;
import com.hapbang.shared.lecture.dto.LectureContentSyncDto;
import com.hapbang.shared.lecture.dto.LectureSyncDto;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Conflicts;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.core.UpdateByQueryResponse;
import co.elastic.clients.json.JsonData;

/**
 * 강의 검색 문서 쓰기(#882). 문서 ID는 강의 ID이고, 모든 쓰기는 alias({@link LectureIndexDefinition#ALIAS})로만 한다({@code require_alias}).
 * <p>
 * 칸마다 출처가 하나다(Q2). 쓰는 곳도 출처마다 나뉜다.
 * <ul>
 *     <li>강의 칸(제목·소개·콘텐츠 제목·가격·판매자 ID·숨김): 강의 이벤트({@link #writeLecture}). <b>강의 version 조건부 쓰기</b>로, 문서의
 *     {@code lectureVersion}보다 클 때만 반영한다(가격 칸만 같은 version에도 맞춘다, {@link #LECTURE_SCRIPT}). 순서가 뒤바뀐 이벤트·
 *     같은 이벤트 두 번·삭제 뒤 늦게 온 옛 이벤트는 noop이다. ES external versioning은 쓰지 않는다(판매자·평점 부분 갱신이 ES 문서 버전을 올려 다음 강의 이벤트를 거절하게 만든다, 인플루언서 #771과 같은 이유).</li>
 *     <li>판매자 칸(닉네임·정상 여부): 회원 사본({@link #updateSeller}, {@link #writeSeller}).</li>
 *     <li>평점 칸(평균·리뷰 수): 리뷰 사본 집계({@link #writeRating}).</li>
 * </ul>
 * 강의 쓰기(script)는 문서를 <b>처음 만들 때만</b> 넘긴 판매자·평점 값을 함께 넣는다. 그 뒤로는 판매자·평점 쓰기만 그 칸을 바꿔, 강의 이벤트가
 * 그사이 바뀐 판매자·평점을 옛 값으로 되돌리지 못하게 한다. 판매자 칸은 회원 version({@code sellerVersion})으로도 옛 값을 막는다.
 * <p>
 * 숨김(tombstone): 판매 중이 아닌 강의({@code onSale=false}: 판매 중지·검수 전·삭제)는 문서를 지우지 않고 {@code hidden=true}·
 * {@code hiddenAt}·version을 남긴다. 지우면 늦게 온 옛 이벤트가 문서를 되살린다. 7일 뒤 정리한다. 이미 숨긴 문서에 더 새 숨김 이벤트가 와도
 * {@code hiddenAt}을 다시 찍는다(인플루언서와 같음). 정리 기준이 마지막 숨김 이벤트에서 7일이 되어 그 사이 늦게 온 옛 이벤트를 막는다(PR #906 리뷰). 판매자 정지는 숨김이 아니라
 * {@code sellerActive}로 나타낸다(Q2, 정지가 길어도 정리가 지우지 않는다).
 */
@Component
public class LectureDocumentStore {

    /**
     * 강의 이벤트 반영 script(scripted upsert). 문서가 없으면 {@code ctx._source}가 빈 문서다. {@code lectureId}가 없으면 새 문서로 보고 판매자·
     * 평점 칸도 채운다.
     * <p>
     * 가격 칸(#909): 넘긴 가격이 없으면(가격 필드가 생기기 전의 옛 메시지·응답) 문서 가격을 그대로 둔다. <b>같은 version</b>이면 다른 칸은 noop이고
     * 넘긴 가격이 있고 문서와 다를 때만 가격 칸 하나를 고친다. 같은 강의 ID·version은 이벤트와 동기화 API가 같은 값이라
     * ({@code docs/api/internal-lecture-sync.md}) 맞는 값이다. 가격을 모르는 옛 pod(롤링 배포 중)가 쓴 문서도 재발행·보정이 고친다.
     */
    static final String LECTURE_SCRIPT = """
            def current = ctx._source.lectureVersion;
            if (current != null) {
              long currentVersion = ((Number) current).longValue();
              if (currentVersion > params.version) { ctx.op = 'noop'; return; }
              if (currentVersion == params.version) {
                if (params.priceHp != null && ctx._source.priceHp != params.priceHp) {
                  ctx._source.priceHp = params.priceHp;
                } else {
                  ctx.op = 'noop';
                }
                return;
              }
            }
            boolean created = ctx._source.lectureId == null;
            ctx._source.lectureId = params.lectureId;
            ctx._source.lectureVersion = params.version;
            ctx._source.sellerUserId = params.sellerUserId;
            ctx._source.title = params.title;
            ctx._source.description = params.description;
            ctx._source.contentTitles = params.contentTitles;
            if (params.priceHp != null) {
              ctx._source.priceHp = params.priceHp;
            }
            if (params.visible) {
              ctx._source.hidden = false;
              ctx._source.remove('hiddenAt');
            } else {
              ctx._source.hidden = true;
              ctx._source.hiddenAt = params.now;
            }
            if (created) {
              ctx._source.sellerNickname = params.sellerNickname;
              ctx._source.sellerActive = params.sellerActive;
              ctx._source.sellerVersion = params.sellerVersion;
              ctx._source.averageRating = params.averageRating;
              ctx._source.reviewCount = params.reviewCount;
            }
            """;

    /**
     * 판매자 칸 반영 script(문서 하나·update_by_query 같이 씀). 값이 같으면 noop이다.
     * <p>
     * <b>회원 version 비교</b>(PR #906 리뷰 Q12): 문서의 {@code sellerVersion}보다 작은 회원 version이면 쓰지 않는다. 강의 소비자가 사본을 읽은 뒤 쓰기
     * 전에 회원 소비자가 더 새 판매자 값(정지 등)을 쓰면, 강의 소비자의 옛 값이 그것을 되돌리지 못하게 한다. 같은 version은 쓴다(재시도). version이 없는
     * 정보는 문서에도 version이 없을 때만 쓴다({@code UserDto} 규칙).
     */
    static final String SELLER_SCRIPT = """
            def current = ctx._source.sellerVersion;
            if (params.version == null) {
              if (current != null) { ctx.op = 'noop'; return; }
            } else if (current != null && ((Number) current).longValue() > ((Number) params.version).longValue()) {
              ctx.op = 'noop'; return;
            }
            boolean changed = false;
            if (ctx._source.sellerNickname != params.nickname) { ctx._source.sellerNickname = params.nickname; changed = true; }
            if (ctx._source.sellerActive != params.active) { ctx._source.sellerActive = params.active; changed = true; }
            if (params.version != null && ctx._source.sellerVersion != params.version) { ctx._source.sellerVersion = params.version; changed = true; }
            if (!changed) { ctx.op = 'noop'; }
            """;

    /** 평점 칸 반영 script. 값이 같으면 noop이다(painless의 {@code !=}는 숫자 타입이 달라도 값으로 비교한다) */
    static final String RATING_SCRIPT = """
            boolean changed = false;
            if (ctx._source.averageRating != params.averageRating) { ctx._source.averageRating = params.averageRating; changed = true; }
            if (ctx._source.reviewCount != params.reviewCount) { ctx._source.reviewCount = params.reviewCount; changed = true; }
            if (!changed) { ctx.op = 'noop'; }
            """;

    private final ElasticsearchClient client;
    private final DocumentWrites writes;

    public LectureDocumentStore(@Qualifier(SearchElasticsearchConfig.CLIENT) ElasticsearchClient client) {
        this.client = client;
        this.writes = new DocumentWrites(client, LectureIndexDefinition.ALIAS);
    }

    /** 대상과 상관없는 문서 쓰기 도우미(tombstone 정리, 보이는 문서 세기) */
    public DocumentWrites writes() {
        return writes;
    }

    /**
     * 강의 이벤트를 문서에 반영한다. 판매 중이면 보이는 문서, 아니면 숨김 문서다. 판매자·평점 칸은 문서를 처음 만들 때만 넘긴 값으로 채운다.
     *
     * @param seller 판매자 정보. 회원 사본에 아직 없으면 {@code null}(닉네임 없이, 정상 판매자로 넣고 회원 이벤트가 채운다)
     * @param rating 리뷰 사본의 평균·리뷰 수
     * @param now    숨긴 시각(7일 뒤 정리 기준)
     * @return 새로 만들었으면 {@link WriteOutcome#CREATED}, 반영했으면(같은 version의 가격만 고친 경우 포함) {@link WriteOutcome#UPDATED},
     *         옛 version이거나 같은 version에 고칠 가격이 없으면 {@link WriteOutcome#NOOP}
     * @throws com.hapbang.auxiliary.search.common.out.IndexNotReadyException alias가 없는 경우(처음 적재 전)
     * @throws DocumentRejectedException ES가 그 강의의 값 때문에 거절한 경우(다시 써도 같다)
     * @throws IllegalStateException     그 밖에 쓰지 못한 경우
     */
    public WriteOutcome writeLecture(LectureSyncDto lecture, Seller seller, LectureRating rating, Instant now) {
        Map<String, JsonData> params = lectureParams(lecture, seller, rating, now);
        try {
            return DocumentWrites.outcome(client.update(u -> u.index(LectureIndexDefinition.ALIAS)
                    .id(String.valueOf(lecture.id()))
                    .requireAlias(true)
                    // 같은 문서를 판매자·평점 쓰기가 함께 바꾸면 다시 읽어 script를 다시 돈다
                    .retryOnConflict(3)
                    .scriptedUpsert(true).upsert(Map.of())
                    .script(s -> s.source(src -> src.scriptString(LECTURE_SCRIPT)).params(params)), Map.class)
                    .result());
        } catch (ElasticsearchException e) {
            writes.throwIfAliasMissing(e);
            if (DocumentWrites.isRejectedByContent(e)) {
                throw new DocumentRejectedException("ES가 강의 검색 문서를 거절했습니다 lectureId=" + lecture.id()
                        + ", error=" + e.error().type(), e);
            }
            throw new IllegalStateException("강의 검색 문서를 쓰지 못했습니다 lectureId=" + lecture.id(), e);
        } catch (IOException e) {
            throw new IllegalStateException("강의 검색 문서를 쓰지 못했습니다 lectureId=" + lecture.id(), e);
        }
    }

    /**
     * 강의 하나의 평점 칸을 바꾼다. 문서가 없으면(강의 이벤트가 아직 오지 않음) 건너뛴다. 강의 문서를 처음 만들 때 리뷰 사본에서 채운다.
     *
     * @return 바꿨으면 {@link WriteOutcome#UPDATED}, 같으면 {@link WriteOutcome#NOOP}, 문서가 없으면 {@link WriteOutcome#MISSING}
     */
    public WriteOutcome writeRating(long lectureId, LectureRating rating) {
        return partialUpdate(lectureId, RATING_SCRIPT, ratingParams(rating));
    }

    /**
     * 강의 하나의 판매자 칸을 바꾼다. 강의 문서를 처음 만든 뒤 회원 사본이 그사이 바뀌었을 때 맞춘다.
     *
     * @return 바꿨으면 {@link WriteOutcome#UPDATED}, 같으면 {@link WriteOutcome#NOOP}, 문서가 없으면 {@link WriteOutcome#MISSING}
     */
    public WriteOutcome writeSeller(long lectureId, Seller seller) {
        return partialUpdate(lectureId, SELLER_SCRIPT, sellerParams(seller));
    }

    /**
     * 그 판매자의 모든 강의 문서(숨긴 문서 포함)의 판매자 칸을 바꾼다(update_by_query, L5·L8).
     * <p>
     * 먼저 refresh한다. update_by_query는 검색에 보이는 문서만 고치므로, 방금 만든 강의 문서(refresh 전)를 놓치지 않게 한다. 그사이 강의 이벤트가 같은
     * 문서를 바꿔 충돌하면 그 문서를 건너뛰지 않고 실패로 알린다(강의 쓰기는 판매자 칸을 바꾸지 않으므로 건너뛰면 옛 판매자 값이 남는다). 부르는 쪽이
     * 다시 시도한다.
     *
     * @return 바꾼 문서 수
     * @throws com.hapbang.auxiliary.search.common.out.IndexNotReadyException alias가 없는 경우(처음 적재 전)
     * @throws IllegalStateException 바꾸지 못했거나 충돌한 문서가 있는 경우
     */
    public long updateSeller(Seller seller) {
        try {
            client.indices().refresh(r -> r.index(LectureIndexDefinition.ALIAS));
            UpdateByQueryResponse response = client.updateByQuery(u -> u.index(LectureIndexDefinition.ALIAS)
                    .conflicts(Conflicts.Proceed)
                    .query(q -> q.term(t -> t.field("sellerUserId").value(seller.userId())))
                    .script(s -> s.source(src -> src.scriptString(SELLER_SCRIPT)).params(sellerParams(seller))));
            long conflicts = response.versionConflicts() == null ? 0 : response.versionConflicts();
            if (conflicts > 0 || !response.failures().isEmpty()) {
                throw new IllegalStateException("판매자의 강의 검색 문서 일부를 바꾸지 못했습니다 sellerUserId=" + seller.userId()
                        + ", versionConflicts=" + conflicts + ", failures=" + response.failures().size());
            }
            return response.updated() == null ? 0 : response.updated();
        } catch (ElasticsearchException e) {
            writes.throwIfAliasMissing(e);
            throw new IllegalStateException("판매자의 강의 검색 문서를 바꾸지 못했습니다 sellerUserId=" + seller.userId(), e);
        } catch (IOException e) {
            throw new IllegalStateException("판매자의 강의 검색 문서를 바꾸지 못했습니다 sellerUserId=" + seller.userId(), e);
        }
    }

    private WriteOutcome partialUpdate(long lectureId, String script, Map<String, JsonData> params) {
        try {
            return DocumentWrites.outcome(client.update(u -> u.index(LectureIndexDefinition.ALIAS)
                    .id(String.valueOf(lectureId))
                    .requireAlias(true)
                    .retryOnConflict(3)
                    .script(s -> s.source(src -> src.scriptString(script)).params(params)), Map.class)
                    .result());
        } catch (ElasticsearchException e) {
            if (e.error() != null && DocumentWrites.DOCUMENT_MISSING.equals(e.error().type())) {
                return WriteOutcome.MISSING;
            }
            writes.throwIfAliasMissing(e);
            throw new IllegalStateException("강의 검색 문서를 고치지 못했습니다 lectureId=" + lectureId, e);
        } catch (IOException e) {
            throw new IllegalStateException("강의 검색 문서를 고치지 못했습니다 lectureId=" + lectureId, e);
        }
    }

    /** {@link #LECTURE_SCRIPT}에 넘기는 값. 처음 적재(#884)의 bulk도 같은 값을 쓴다 */
    static Map<String, JsonData> lectureParams(LectureSyncDto lecture, Seller seller, LectureRating rating,
            Instant now) {
        Map<String, JsonData> params = new HashMap<>();
        params.put("lectureId", JsonData.of(lecture.id()));
        params.put("version", JsonData.of(lecture.version()));
        params.put("sellerUserId", JsonData.of(lecture.sellerUserId()));
        params.put("title", JsonData.of(lecture.title()));
        putIfPresent(params, "description", lecture.description());
        params.put("contentTitles", JsonData.of(lecture.contents().stream().map(LectureContentSyncDto::title).toList()));
        putIfPresent(params, "priceHp", lecture.priceHp());
        params.put("visible", JsonData.of(lecture.onSale()));
        params.put("now", JsonData.of(now.toEpochMilli()));
        putIfPresent(params, "sellerNickname", seller == null ? null : seller.nickname());
        params.put("sellerActive", JsonData.of(seller == null || seller.active()));
        putIfPresent(params, "sellerVersion", seller == null ? null : seller.version());
        params.putAll(ratingParams(rating));
        return params;
    }

    private static Map<String, JsonData> sellerParams(Seller seller) {
        Map<String, JsonData> params = new HashMap<>();
        putIfPresent(params, "nickname", seller.nickname());
        params.put("active", JsonData.of(seller.active()));
        putIfPresent(params, "version", seller.version());
        return params;
    }

    /**
     * 값이 있을 때만 넣는다. ES client는 null을 script 값으로 보내지 못한다. script에서 없는 값({@code params.x})은 null로 읽혀 칸이 비워진다
     */
    private static void putIfPresent(Map<String, JsonData> params, String key, Object value) {
        if (value != null) {
            params.put(key, JsonData.of(value));
        }
    }

    private static Map<String, JsonData> ratingParams(LectureRating rating) {
        Map<String, JsonData> params = new HashMap<>();
        putIfPresent(params, "averageRating", rating.average());
        params.put("reviewCount", JsonData.of(rating.count()));
        return params;
    }
}
