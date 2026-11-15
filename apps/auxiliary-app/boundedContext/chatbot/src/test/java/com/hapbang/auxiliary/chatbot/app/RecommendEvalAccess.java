package com.hapbang.auxiliary.chatbot.app;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.ApplicationContext;

import com.hapbang.auxiliary.chatbot.app.InfluencerRecommendUseCase.Candidate;
import com.hapbang.auxiliary.chatbot.config.ChatbotIndexProperties;
import com.hapbang.auxiliary.chatbot.config.ChatbotInfluencerRecommendProperties;
import com.hapbang.auxiliary.chatbot.config.ChatbotLectureRecommendProperties;
import com.hapbang.auxiliary.chatbot.domain.IndexType;
import com.hapbang.auxiliary.chatbot.domain.InfluencerProfile;
import com.hapbang.auxiliary.chatbot.domain.InfluencerProfileText;
import com.hapbang.auxiliary.chatbot.domain.InfluencerReason;
import com.hapbang.auxiliary.chatbot.domain.LectureRecommendReason;
import com.hapbang.auxiliary.chatbot.domain.LectureSnapshot;
import com.hapbang.auxiliary.chatbot.llm.recommend.RecommendMetrics.Pick;
import com.hapbang.auxiliary.chatbot.out.InfluencerRecommendStore;
import com.hapbang.auxiliary.chatbot.out.LectureRecommendStore;
import com.hapbang.auxiliary.chatbot.out.RecommendVectorIndex;

/**
 * 추천 평가(#867)가 운영 추천 코드를 부르는 <b>유일한 곳</b>. 운영 코드를 고치지 않으려고 같은 패키지(app)에 둔다
 * (인플루언서 UseCase 생성자, 색인 문서 필드가 package-private이다).
 * <ul>
 *     <li>추천 계산은 복사하지 않는다. 시험할 설정 record로 운영 UseCase를 직접 만들어 부른다(값마다 앱을 다시 띄우지 않는다).</li>
 *     <li>저장소·색인·회원 보임 규칙·지표는 테스트 컨텍스트의 빈을 그대로 쓴다. 임베딩만 평가가 준 모델(캐시)을 쓴다.</li>
 *     <li>후보 수를 인자로 받게 바뀌면({@code #720}) {@link #lecture}·{@link #influencer}의 호출 한 줄씩만 고친다.</li>
 * </ul>
 */
public final class RecommendEvalAccess {

    private final LectureRecommendStore lectureStore;
    private final InfluencerRecommendStore influencerStore;
    private final RecommendVectorIndex index;
    private final MemberVisibility visibility;
    private final ChatbotMetrics metrics;
    private final ChatbotEmbedder embedder;

    /** @param embeddingModel 평가에 쓸 임베딩 모델(캐시를 앞에 둔 모델) */
    public RecommendEvalAccess(ApplicationContext context, EmbeddingModel embeddingModel) {
        this.lectureStore = context.getBean(LectureRecommendStore.class);
        this.influencerStore = context.getBean(InfluencerRecommendStore.class);
        this.index = context.getBean(RecommendVectorIndex.class);
        this.visibility = context.getBean(MemberVisibility.class);
        this.metrics = context.getBean(ChatbotMetrics.class);
        StaticListableBeanFactory models = new StaticListableBeanFactory(Map.of("evalEmbeddingModel", embeddingModel));
        this.embedder = new ChatbotEmbedder(context.getBean(ChatbotIndexProperties.class),
                models.getBeanProvider(EmbeddingModel.class));
    }

    /** 강의 추천. 결과 수는 설정의 {@code resultSize}. 인기순으로 채운 항목은 맞춤 후보가 아니다 */
    public List<Pick> lecture(ChatbotLectureRecommendProperties properties, long memberId, @Nullable String interest) {
        // 조합마다 설정이 달라 인기순 캐시도 매번 새로 만든다
        PopularLectureCache popularCache = new PopularLectureCache(lectureStore, properties, Clock.systemUTC());
        LectureRecommendUseCase useCase = new LectureRecommendUseCase(lectureStore, index, embedder, visibility, popularCache,
                properties, metrics);
        LectureRecommendation result = useCase.recommend(memberId, interest);
        List<Pick> picks = new ArrayList<>();
        for (LectureRecommendation.Item item : result.items()) {
            picks.add(new Pick(item.lectureId(), item.reason() != LectureRecommendReason.POPULAR));
        }
        return picks;
    }

    /** 인플루언서 추천(관심사 없음). 결과 수는 설정의 {@code size}. 인기순으로 채운 항목은 맞춤 후보가 아니다 */
    public List<Pick> influencer(ChatbotInfluencerRecommendProperties properties, long memberId) {
        InfluencerRecommendUseCase useCase = new InfluencerRecommendUseCase(influencerStore, visibility, index, embedder,
                properties, metrics);
        InfluencerRecommendUseCase.InfluencerRecommendation result = useCase.recommend(memberId, null);
        List<Pick> picks = new ArrayList<>();
        for (Candidate candidate : result.items()) {
            picks.add(new Pick(candidate.memberId(), !candidate.reasons().contains(InfluencerReason.POPULAR)));
        }
        return picks;
    }

    /** 문서 글을 임베딩한다(운영과 같은 접두어·차원 확인) */
    public List<float[]> embedDocuments(List<String> texts) {
        return embedder.embedDocuments(texts);
    }

    /** 두 index를 운영 매핑으로 만든다 */
    public void ensureIndexes() {
        index.ensureIndex(IndexType.LECTURE, LectureIndexDocument.FIELDS);
        index.ensureIndex(IndexType.INFLUENCER, InfluencerIndexPlan.FIELDS);
    }

    /** 강의 문서를 운영 처리기와 같은 필드로 넣는다 */
    public void upsertLecture(LectureSnapshot lecture, float[] vector) {
        index.upsert(IndexType.LECTURE, LectureIndexDocument.docId(lecture.id()), vector,
                LectureIndexDocument.of(lecture).allFields());
    }

    /** 인플루언서 문서를 운영 처리기와 같은 필드로 넣는다 */
    public void upsertInfluencer(InfluencerProfile profile, InfluencerProfileText text, float[] vector) {
        index.upsert(IndexType.INFLUENCER, InfluencerIndexPlan.docIdOf(profile.id()), vector,
                InfluencerIndexPlan.fieldsOf(profile, text));
    }

    public void refresh() {
        index.refresh(IndexType.LECTURE);
        index.refresh(IndexType.INFLUENCER);
    }

    public String indexName(IndexType type) {
        return index.indexName(type);
    }
}
