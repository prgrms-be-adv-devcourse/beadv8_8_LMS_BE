package com.hapbang.auxiliary.chatbot.app;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.hapbang.auxiliary.chatbot.domain.InfluencerProfile;
import com.hapbang.auxiliary.chatbot.domain.InfluencerProfileText;
import com.hapbang.auxiliary.chatbot.out.RecommendVectorIndex.Field;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 인플루언서 색인 문서가 지금 사본 상태와 맞는지, 아니면 무엇을 해야 하는지 정하는 한 곳 (#719).
 * 처리기({@link InfluencerIndexTaskHandler})와 정합 점검({@link InfluencerIndexChecker})이 같은 규칙을 쓴다.
 * <ol>
 *     <li>사본 없음·마스킹 → 문서가 있으면 지운다(문서 삭제는 마스킹 때만).</li>
 *     <li>추천 불가(일반 회원·관리자로 강등, 정지, 탈퇴) → 문서가 있으면 {@code hidden=true}로만 바꾼다(다시 임베딩하지 않는다).
 *     문서가 없으면 만들지 않는다. 글이 비어도 지우지 않고 숨긴다(복구되면 그때 글로 판단한다).</li>
 *     <li>추천 가능한데 분야·자기소개가 모두 없음 → 문서가 있으면 지운다(벡터 없는 문서는 두지 않는다. 인기순 후보는 DB에서 고른다).</li>
 *     <li>추천 가능 → 문서가 없거나 글 해시가 다르면 임베딩해 넣는다. 해시가 같고 숨겨져 있으면 {@code hidden=false}로만 바꾼다.</li>
 * </ol>
 * <b>분야 한글 이름 표는 4번(글 해시)에서만 쓴다.</b> 1~3번은 사본만으로 정하므로 회원 API가 죽어 있어도 마스킹 삭제·숨김은 된다.
 * 복구(숨김 해제)는 글이 바뀌었는지 봐야 하므로 4번이고, 표를 받을 때까지 숨긴 채로 둔다(추천이 잘못 나가지 않는 쪽).
 * 문서 ID는 회원 ID의 문자열이다({@link #docIdOf}·{@link #memberIdOf}).
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
    @Getter
    @RequiredArgsConstructor
    enum Action {
        NONE("맞다"),
        EMBED("임베딩해 넣는다"),
        HIDE("hidden=true로 바꾼다"),
        SHOW("hidden=false로 바꾼다"),
        DELETE("지운다");

        private final String description;
    }

    /** 할 일과, 임베딩할 때 쓸 글 */
    record Decision(Action action, @Nullable InfluencerProfileText text) {

        /** 색인 문서를 바꿔야 한다 */
        boolean needsWork() {
            return action != Action.NONE;
        }
    }

    /**
     * @param profile       회원 사본(없으면 null)
     * @param categoryNames 분야 코드 → 한글 이름 표. 4번(글 해시)에서만 부른다
     * @param indexed       지금 색인 문서(없으면 null)
     * @throws ContentCategoryNames.Unavailable 4번까지 와서 분야 이름 표를 받을 수 없을 때
     */
    static Decision decide(@Nullable InfluencerProfile profile, Supplier<Map<String, String>> categoryNames,
            @Nullable IndexedProfile indexed) {
        boolean exists = indexed != null;
        if (profile == null || profile.masked()) {
            return new Decision(exists ? Action.DELETE : Action.NONE, null);
        }
        if (!profile.recommendable()) {
            return new Decision(exists && !indexed.hidden() ? Action.HIDE : Action.NONE, null);
        }
        if (!hasText(profile)) {
            return new Decision(exists ? Action.DELETE : Action.NONE, null);
        }
        InfluencerProfileText text = InfluencerProfileText.of(
                ContentCategoryNames.namesOf(profile.categories(), categoryNames.get()), profile.bio());
        if (!exists || !text.hash().equals(indexed.textHash())) {
            return new Decision(Action.EMBED, text);
        }
        return new Decision(indexed.hidden() ? Action.SHOW : Action.NONE, null);
    }

    /**
     * 임베딩할 글이 있다(분야나, 개인정보를 지우고도 남는 자기소개). 없으면 문서를 두지 않는다.
     * 이름 표 없이 코드로 판단한다: 이름은 코드마다 하나씩이라 비었는지가 같다.
     */
    static boolean hasText(InfluencerProfile profile) {
        return !InfluencerProfileText.of(profile.categories(), profile.bio()).isEmpty();
    }

    /** 넣을 문서의 벡터 밖 필드 */
    static Map<String, Object> fieldsOf(InfluencerProfile profile, InfluencerProfileText text) {
        return Map.of(HIDDEN, false, CATEGORIES, profile.categories(), TEXT_HASH, text.hash());
    }

    static String docIdOf(long memberId) {
        return String.valueOf(memberId);
    }

    static List<String> docIdsOf(Collection<Long> memberIds) {
        List<String> docIds = new ArrayList<>();
        for (Long memberId : memberIds) {
            docIds.add(docIdOf(memberId));
        }
        return docIds;
    }

    /** @throws NumberFormatException 회원 ID가 아닌 문서 ID */
    static long memberIdOf(String docId) {
        return Long.parseLong(docId);
    }

    /** 색인 문서의 벡터 밖 필드 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record IndexedProfile(boolean hidden, @JsonProperty(TEXT_HASH) @Nullable String textHash) {
    }
}
