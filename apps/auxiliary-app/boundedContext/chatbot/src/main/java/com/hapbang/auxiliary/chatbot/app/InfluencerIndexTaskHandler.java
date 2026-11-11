package com.hapbang.auxiliary.chatbot.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;

import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.chatbot.app.InfluencerIndexPlan.Decision;
import com.hapbang.auxiliary.chatbot.app.InfluencerIndexPlan.IndexedProfile;
import com.hapbang.auxiliary.chatbot.domain.IndexAction;
import com.hapbang.auxiliary.chatbot.domain.IndexType;
import com.hapbang.auxiliary.chatbot.domain.InfluencerProfile;
import com.hapbang.auxiliary.chatbot.domain.InfluencerProfileText;
import com.hapbang.auxiliary.chatbot.out.InfluencerRecommendStore;
import com.hapbang.auxiliary.chatbot.out.RecommendVectorIndex;

/**
 * 인플루언서 색인 요청 처리기 (#719). 실행기({@link IndexTaskRunner})가 문서(회원 ID) 묶음을 넘긴다.
 * <ul>
 *     <li>요청 종류가 아니라 지금 사본 상태로 맞춘다({@link InfluencerIndexPlan}). 마스킹 때 {@code MemberMasking}이 적은 삭제 요청도
 *     사본이 마스킹으로 보이므로 지운다.</li>
 *     <li>임베딩할 문서는 모아 공급자에 한 번에 보낸다. 임베딩 호출이 실패하면 그 문서들만 실패로 돌려준다.</li>
 *     <li>자기소개는 이메일·전화번호를 지운 글로 임베딩 API에만 보낸다. ES에는 글의 해시만 둔다.</li>
 *     <li>지금 문서는 ES 검색으로 읽는다(검색은 refresh 뒤의 쓰기만 본다). 그래서 쓴 묶음은 끝에 refresh해 다음 묶음이
 *     방금 쓴 상태를 보게 한다(정지 직후 복구가 오면 숨김 해제를 놓치지 않는다).</li>
 *     <li>분야 한글 이름은 글 해시를 비교할 문서에서만 부른다. 받지 못하면 그 문서만 실패로 돌려 실행기가 다시 시도한다.
 *     <b>마스킹 삭제·숨김은 회원 API 장애와 상관없이 된다.</b></li>
 * </ul>
 */
@Component
public class InfluencerIndexTaskHandler implements IndexTaskHandler {

    private final RecommendVectorIndex index;
    private final ChatbotEmbedder embedder;
    private final InfluencerRecommendStore store;
    private final ContentCategoryNames categoryNames;

    InfluencerIndexTaskHandler(RecommendVectorIndex index, ChatbotEmbedder embedder, InfluencerRecommendStore store,
            ContentCategoryNames categoryNames) {
        this.index = index;
        this.embedder = embedder;
        this.store = store;
        this.categoryNames = categoryNames;
    }

    @Override
    public IndexType type() {
        return IndexType.INFLUENCER;
    }

    @Override
    public boolean available() {
        return embedder.available() && index.available();
    }

    /** index가 없으면 만든다. 실행기·정합 점검이 쓰기 전에 부른다(ES가 매핑 없이 index를 자동으로 만들지 않게) */
    boolean ensureIndex() {
        return index.ensureIndex(IndexType.INFLUENCER, InfluencerIndexPlan.FIELDS);
    }

    @Override
    public Map<String, RuntimeException> handle(SequencedMap<String, IndexAction> docs) {
        ensureIndex();
        Map<String, RuntimeException> failures = new LinkedHashMap<>();
        Map<String, Long> memberIds = new LinkedHashMap<>();
        for (String docId : docs.keySet()) {
            try {
                memberIds.put(docId, InfluencerIndexPlan.memberIdOf(docId));
            } catch (NumberFormatException e) {
                failures.put(docId, new IllegalArgumentException("인플루언서 문서 ID는 회원 ID여야 합니다 docId=" + docId, e));
            }
        }
        Map<Long, InfluencerProfile> profiles = store.profiles(memberIds.values());
        Map<String, IndexedProfile> indexed = index.documents(IndexType.INFLUENCER, memberIds.keySet(), IndexedProfile.class);
        List<Pending> toEmbed = new ArrayList<>();
        boolean wrote = false;
        for (Map.Entry<String, Long> entry : memberIds.entrySet()) {
            String docId = entry.getKey();
            InfluencerProfile profile = profiles.get(entry.getValue());
            try {
                Decision decision = InfluencerIndexPlan.decide(profile, categoryNames::current, indexed.get(docId));
                switch (decision.action()) {
                    case NONE -> {
                    }
                    case DELETE -> index.delete(IndexType.INFLUENCER, docId);
                    case HIDE -> setHidden(docId, true);
                    case SHOW -> setHidden(docId, false);
                    case EMBED -> toEmbed.add(new Pending(docId, profile, decision.text()));
                }
                wrote |= decision.needsWork();
            } catch (RuntimeException e) {
                failures.put(docId, e);
            }
        }
        embedAndUpsert(toEmbed, failures);
        if (wrote) {
            index.refresh(IndexType.INFLUENCER);
        }
        return failures;
    }

    // 숨김만 바꾼다(벡터는 그대로). 그새 문서가 사라졌으면 실패로 돌려 다시 시도하게 한다(다음 실행은 임베딩해 넣는다)
    private void setHidden(String docId, boolean hidden) {
        if (!index.update(IndexType.INFLUENCER, docId, Map.of(InfluencerIndexPlan.HIDDEN, hidden))) {
            throw new IllegalStateException("숨김을 바꿀 인플루언서 문서가 없습니다 docId=" + docId);
        }
    }

    private void embedAndUpsert(List<Pending> pending, Map<String, RuntimeException> failures) {
        if (pending.isEmpty()) {
            return;
        }
        List<String> texts = new ArrayList<>();
        for (Pending one : pending) {
            texts.add(one.text().text());
        }
        List<float[]> vectors;
        try {
            vectors = embedder.embedDocuments(texts);
        } catch (RuntimeException e) {
            for (Pending one : pending) {
                failures.put(one.docId(), e);
            }
            return;
        }
        for (int i = 0; i < pending.size(); i++) {
            Pending one = pending.get(i);
            try {
                index.upsert(IndexType.INFLUENCER, one.docId(), vectors.get(i),
                        InfluencerIndexPlan.fieldsOf(one.profile(), one.text()));
            } catch (RuntimeException e) {
                failures.put(one.docId(), e);
            }
        }
    }

    private record Pending(String docId, InfluencerProfile profile, InfluencerProfileText text) {
    }
}
