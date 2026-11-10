package com.hapbang.auxiliary.chatbot.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.hapbang.auxiliary.chatbot.domain.InfluencerProfileText;
import com.hapbang.auxiliary.chatbot.out.InfluencerRecommendStore.Profile;
import com.hapbang.auxiliary.chatbot.out.RecommendVectorIndex.Field;

/**
 * 인플루언서 색인 문서가 지금 사본 상태와 맞는지, 아니면 무엇을 해야 하는지 정하는 한 곳 (#719).
 * 처리기({@link InfluencerIndexTaskHandler})와 정합 점검({@link InfluencerIndexChecker})이 같은 규칙을 쓴다.
 * <ul>
 *     <li>사본 없음·마스킹·분야와 자기소개가 모두 없음 → 문서가 있으면 지운다(벡터 없는 문서는 두지 않는다. 인기순 후보는 DB에서 고른다).</li>
 *     <li>추천 불가(일반 회원·관리자로 강등, 정지, 탈퇴) → 문서가 있으면 {@code hidden=true}로만 바꾼다(다시 임베딩하지 않는다).
 *     문서가 없으면 만들지 않는다.</li>
 *     <li>추천 가능 → 문서가 없거나 글 해시가 다르면 임베딩해 넣는다. 해시가 같고 숨겨져 있으면 {@code hidden=false}로만 바꾼다.</li>
 * </ul>
 */
final class InfluencerIndexPlan {

    static final String HIDDEN = "hidden";
    static final String CATEGORIES = "categories";
    static final String TEXT_HASH = "text_hash";

    /** 벡터 밖 필드 매핑 */
    static final Map<String, Field> FIELDS = Map.of(HIDDEN, Field.BOOLEAN, CATEGORIES, Field.KEYWORD,
            TEXT_HASH, Field.STORED_TEXT);

    private InfluencerIndexPlan() {
    }

    /** 문서 하나에 할 일 */
    enum Action {
        /** 맞다 */
        NONE,
        /** 임베딩해 넣는다 */
        EMBED,
        /** {@code hidden=true}로 바꾼다 */
        HIDE,
        /** {@code hidden=false}로 바꾼다 */
        SHOW,
        /** 지운다 */
        DELETE
    }

    /**
     * @param profile 회원 사본(없으면 null)
     * @param text    그 회원의 임베딩 글(사본이 없으면 null)
     * @param indexed 지금 색인 문서(없으면 null)
     */
    static Action decide(@Nullable Profile profile, @Nullable InfluencerProfileText text, @Nullable IndexedProfile indexed) {
        boolean exists = indexed != null;
        if (profile == null || profile.masked() || text == null || text.isEmpty()) {
            return exists ? Action.DELETE : Action.NONE;
        }
        if (!profile.recommendable()) {
            return exists && !indexed.hidden() ? Action.HIDE : Action.NONE;
        }
        if (!exists || !text.hash().equals(indexed.textHash())) {
            return Action.EMBED;
        }
        return indexed.hidden() ? Action.SHOW : Action.NONE;
    }

    /** 회원의 임베딩 글. 모르는 분야 코드는 코드 그대로 쓴다(분야 표가 늦게 갱신된 경우) */
    static InfluencerProfileText textOf(Profile profile, Map<String, String> categoryNames) {
        List<String> names = new ArrayList<>();
        for (String code : profile.categories()) {
            names.add(categoryNames.getOrDefault(code, code));
        }
        return InfluencerProfileText.of(names, profile.bio());
    }

    /** 넣을 문서의 벡터 밖 필드 */
    static Map<String, Object> fieldsOf(Profile profile, InfluencerProfileText text) {
        return Map.of(HIDDEN, false, CATEGORIES, profile.categories(), TEXT_HASH, text.hash());
    }

    /** 색인 문서의 벡터 밖 필드 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record IndexedProfile(boolean hidden, @JsonProperty(TEXT_HASH) @Nullable String textHash) {
    }
}
