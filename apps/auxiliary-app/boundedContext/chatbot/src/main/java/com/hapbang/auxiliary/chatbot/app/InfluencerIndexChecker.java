package com.hapbang.auxiliary.chatbot.app;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.hapbang.auxiliary.chatbot.app.InfluencerIndexPlan.IndexedProfile;
import com.hapbang.auxiliary.chatbot.config.ChatbotInfluencerRecommendProperties;
import com.hapbang.auxiliary.chatbot.domain.CopyTimes;
import com.hapbang.auxiliary.chatbot.domain.IndexAction;
import com.hapbang.auxiliary.chatbot.domain.IndexType;
import com.hapbang.auxiliary.chatbot.domain.InfluencerProfile;
import com.hapbang.auxiliary.chatbot.out.IndexTaskStore;
import com.hapbang.auxiliary.chatbot.out.InfluencerRecommendStore;
import com.hapbang.auxiliary.chatbot.out.RecommendVectorIndex;

import lombok.extern.slf4j.Slf4j;

/**
 * 인플루언서 색인 정합 점검 (#719). 모든 회원 사본을 색인 문서와 비교해 어긋난 회원의 색인 요청을 적는다. 처음 전체 색인도 이것이다
 * (첫 배포 뒤에는 문서가 하나도 없어 모든 추천 가능 회원이 요청으로 들어간다).
 * <ul>
 *     <li>비교 규칙은 처리기와 같은 {@link InfluencerIndexPlan}이다. 할 일이 있는 회원만 적는다.</li>
 *     <li>요청만 적고 임베딩·쓰기는 실행기가 한다. 실패한 문서의 다시 시도도 실행기 한 곳이 맡는다(재시도를 겹치지 않는다).</li>
 *     <li>분야 이름 표를 받을 수 없으면 이름이 필요 없는 판정(마스킹 삭제·숨김)만 적고, 끝까지 훑은 뒤 실패로 끝낸다(다음 점검이 나머지를 본다).</li>
 *     <li>회원 싱크(매시 :15) 뒤에 돈다. 이벤트가 조용히 빠져도 최대 한 시간 뒤에는 색인이 사본을 따라간다.</li>
 *     <li>pod마다 돌 수 있다. 같은 문서의 요청이 겹쳐도 실행기가 문서 단위로 한 번에 처리한다.</li>
 *     <li>사본 행은 지우지 않으므로(탈퇴·마스킹도 행을 남긴다) 사본만 훑는다. ES에만 남은 문서는 DB를 초기화했을 때만 생기고,
 *     그때는 index를 지우고 다시 색인한다(강의 추천 점검과 같다).</li>
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
    // 정시 점검의 연속 실패 수(이 pod가 떠 있는 동안)
    private final AtomicInteger consecutiveFailures = new AtomicInteger();

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
     * 정시 점검(배치가 부른다). 실패하면 다음 정시에 다시 하고, {@code check-alert-after-failures}번 연속 실패하면
     * {@code [CHATBOT_INFLUENCER_CHECK_FAILED]} ERROR로 알린다. 예외를 밖으로 던지지 않는다.
     */
    public void checkOnSchedule() {
        try {
            check();
            consecutiveFailures.set(0);
        } catch (RuntimeException e) {
            int failures = consecutiveFailures.incrementAndGet();
            if (failures >= properties.checkAlertAfterFailures()) {
                log.error("[CHATBOT_INFLUENCER_CHECK_FAILED] 인플루언서 색인 정합 점검이 연속으로 실패했다 failures={}", failures, e);
            } else {
                log.warn("인플루언서 색인 정합 점검이 실패했다. 다음 정시에 다시 한다 failures={}", failures, e);
            }
        }
    }

    /**
     * 점검한다. 색인을 할 수 없는 실행(ES·임베딩 모델 없음)이면 아무것도 하지 않는다.
     *
     * @return 요청을 적은 회원 수
     * @throws IllegalStateException 분야 이름 표가 없어 일부 회원을 비교하지 못했을 때(비교한 회원의 요청은 적은 뒤)
     */
    public int check() {
        if (!handler.available()) {
            return 0;
        }
        handler.ensureIndex();
        int queued = 0;
        int skipped = 0;
        ContentCategoryNames.Unavailable namesFailure = null;
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
                try {
                    if (InfluencerIndexPlan.decide(profiles.get(id), categoryNames::current, indexed.get(docId)).needsWork()) {
                        stale.add(docId);
                    }
                } catch (ContentCategoryNames.Unavailable e) {
                    namesFailure = e;
                    skipped++;
                }
            }
            indexTaskStore.addAll(IndexType.INFLUENCER, stale, IndexAction.UPSERT, CopyTimes.now(clock));
            queued += stale.size();
            afterId = ids.getLast();
        }
        if (queued > 0) {
            log.info("인플루언서 색인이 사본과 달라 색인 요청을 적었다 queued={}", queued);
        }
        if (namesFailure != null) {
            throw new IllegalStateException("분야 이름 표가 없어 일부 회원을 점검하지 못했다 queued=" + queued
                    + ", skipped=" + skipped, namesFailure);
        }
        return queued;
    }
}
