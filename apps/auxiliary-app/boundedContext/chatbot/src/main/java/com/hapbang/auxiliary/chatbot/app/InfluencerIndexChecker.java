package com.hapbang.auxiliary.chatbot.app;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.hapbang.auxiliary.chatbot.app.InfluencerIndexPlan.IndexedProfile;
import com.hapbang.auxiliary.chatbot.config.ChatbotInfluencerRecommendProperties;
import com.hapbang.auxiliary.chatbot.domain.CopyTimes;
import com.hapbang.auxiliary.chatbot.domain.IndexAction;
import com.hapbang.auxiliary.chatbot.domain.IndexType;
import com.hapbang.auxiliary.chatbot.out.IndexTaskStore;
import com.hapbang.auxiliary.chatbot.out.InfluencerRecommendStore;
import com.hapbang.auxiliary.chatbot.domain.InfluencerProfile;
import com.hapbang.auxiliary.chatbot.out.RecommendVectorIndex;

import lombok.extern.slf4j.Slf4j;

/**
 * 인플루언서 색인 정합 점검 (#719). 모든 회원 사본을 색인 문서와 비교해 어긋난 회원의 색인 요청을 적는다. 처음 전체 색인도 이것이다
 * (첫 배포 뒤에는 문서가 하나도 없어 모든 추천 가능 회원이 요청으로 들어간다).
 * <ul>
 *     <li>비교 규칙은 처리기와 같은 {@link InfluencerIndexPlan}이다. 할 일이 있는 회원만 적는다.</li>
 *     <li>요청만 적고 임베딩·쓰기는 실행기가 한다. 실패한 문서의 다시 시도도 실행기 한 곳이 맡는다(재시도를 겹치지 않는다).</li>
 *     <li>회원 싱크(매시 :15) 뒤에 돈다. 이벤트가 조용히 빠져도 최대 한 시간 뒤에는 색인이 사본을 따라간다.</li>
 *     <li>pod마다 돌 수 있다. 같은 문서의 요청이 겹쳐도 실행기가 문서 단위로 한 번에 처리한다.</li>
 * </ul>
 */
@Slf4j
@Service
public class InfluencerIndexChecker {

    private final InfluencerIndexTaskHandler handler;
    private final RecommendVectorIndex index;
    private final InfluencerRecommendStore store;
    private final ContentCategoryNames categoryNames;
    private final IndexTaskStore indexTaskStore;
    private final ChatbotInfluencerRecommendProperties properties;
    private final Clock clock;

    InfluencerIndexChecker(InfluencerIndexTaskHandler handler, RecommendVectorIndex index, InfluencerRecommendStore store,
            ContentCategoryNames categoryNames, IndexTaskStore indexTaskStore, ChatbotInfluencerRecommendProperties properties,
            @Qualifier(ChatbotConfig.CLOCK) Clock clock) {
        this.handler = handler;
        this.index = index;
        this.store = store;
        this.categoryNames = categoryNames;
        this.indexTaskStore = indexTaskStore;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 점검한다. 색인을 할 수 없는 실행(ES·임베딩 모델 없음)이면 아무것도 하지 않는다.
     *
     * @return 요청을 적은 회원 수
     */
    public int check() {
        if (!handler.available()) {
            return 0;
        }
        handler.ensureIndex();
        Map<String, String> names = categoryNames.current();
        int queued = 0;
        long afterId = 0;
        while (true) {
            List<Long> ids = store.memberIdsAfter(afterId, properties.checkPageSize());
            if (ids.isEmpty()) {
                break;
            }
            Map<Long, InfluencerProfile> profiles = store.profiles(ids);
            Map<String, IndexedProfile> indexed = index.documents(IndexType.INFLUENCER, InfluencerIndexPlan.docIdsOf(ids),
                    IndexedProfile.class);
            List<String> stale = new ArrayList<>();
            for (Long id : ids) {
                String docId = InfluencerIndexPlan.docIdOf(id);
                if (InfluencerIndexPlan.decide(profiles.get(id), names, indexed.get(docId)).action()
                        != InfluencerIndexPlan.Action.NONE) {
                    stale.add(docId);
                }
            }
            indexTaskStore.addAll(IndexType.INFLUENCER, stale, IndexAction.UPSERT, CopyTimes.now(clock));
            queued += stale.size();
            afterId = ids.getLast();
        }
        if (queued > 0) {
            log.info("인플루언서 색인이 사본과 달라 색인 요청을 적었다 queued={}", queued);
        }
        return queued;
    }
}
