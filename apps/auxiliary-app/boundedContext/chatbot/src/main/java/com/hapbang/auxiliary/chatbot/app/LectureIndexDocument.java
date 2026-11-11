package com.hapbang.auxiliary.chatbot.app;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.hapbang.auxiliary.chatbot.domain.LectureSnapshot;
import com.hapbang.auxiliary.chatbot.out.RecommendVectorIndex;

/**
 * 강의 추천 index 문서의 벡터 밖 필드 (#718). 처리기가 쓰고, 정합 점검이 사본과 비교한다.
 *
 * @param textHash 임베딩한 글의 해시. 같으면 다시 임베딩하지 않는다
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record LectureIndexDocument(
        @JsonProperty(ON_SALE) @Nullable Boolean onSale,
        @JsonProperty(DELETED) @Nullable Boolean deleted,
        @JsonProperty(MISSING_IN_SOURCE) @Nullable Boolean missingInSource,
        @JsonProperty(SELLER_ID) @Nullable Long sellerId,
        @JsonProperty(TEXT_HASH) @Nullable String textHash) {

    static final String ON_SALE = "on_sale";
    static final String DELETED = "deleted";
    static final String MISSING_IN_SOURCE = "missing_in_source";
    static final String SELLER_ID = "seller_id";
    static final String TEXT_HASH = "text_hash";

    /** index 매핑. kNN 필터는 on_sale·deleted·missing_in_source로 건다 */
    static final Map<String, RecommendVectorIndex.Field> FIELDS = Map.of(
            ON_SALE, RecommendVectorIndex.Field.BOOLEAN,
            DELETED, RecommendVectorIndex.Field.BOOLEAN,
            MISSING_IN_SOURCE, RecommendVectorIndex.Field.BOOLEAN,
            SELLER_ID, RecommendVectorIndex.Field.LONG,
            TEXT_HASH, RecommendVectorIndex.Field.STORED_TEXT);

    /** 추천 후보 kNN 필터: 판매 중, 삭제 안 됨, 원본에 있음 */
    static final Map<String, Object> RECOMMENDABLE = Map.of(ON_SALE, true, DELETED, false, MISSING_IN_SOURCE, false);

    /** 강의 ID의 문서 ID(강의 ID 문자열) */
    static String docId(long lectureId) {
        return String.valueOf(lectureId);
    }

    /** 문서 ID의 강의 ID. 숫자가 아니면 {@link NumberFormatException} */
    static long lectureId(String docId) {
        return Long.parseLong(docId);
    }

    /** 사본의 지금 상태로 만든 문서 */
    static LectureIndexDocument of(LectureSnapshot lecture) {
        return new LectureIndexDocument(lecture.onSale(), lecture.deleted(), lecture.missingInSource(), lecture.sellerId(),
                lecture.textHash());
    }

    /** 상태 필드(벡터를 다시 만들지 않고 바꿀 수 있는 값) */
    Map<String, Object> stateFields() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(ON_SALE, onSale);
        fields.put(DELETED, deleted);
        fields.put(MISSING_IN_SOURCE, missingInSource);
        fields.put(SELLER_ID, sellerId);
        return fields;
    }

    /** 벡터와 함께 넣는 전체 필드 */
    Map<String, Object> allFields() {
        Map<String, Object> fields = stateFields();
        fields.put(TEXT_HASH, textHash);
        return fields;
    }

    /** 임베딩한 글이 같은지 */
    boolean sameText(LectureIndexDocument other) {
        return Objects.equals(textHash, other.textHash);
    }
}
