package com.hapbang.auxiliary.chatbot.app;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.ApplicationContext;

import com.hapbang.auxiliary.chatbot.app.InfluencerRecommendUseCase.Candidate;
import com.hapbang.auxiliary.chatbot.config.ChatbotIndexProperties;
import com.hapbang.auxiliary.chatbot.config.ChatbotInfluencerRecommendProperties;
import com.hapbang.auxiliary.chatbot.config.ChatbotLectureRecommendProperties;
import com.hapbang.auxiliary.chatbot.config.ChatbotRecommendLlmProperties;
import com.hapbang.auxiliary.chatbot.domain.IndexType;
import com.hapbang.auxiliary.chatbot.domain.InfluencerProfile;
import com.hapbang.auxiliary.chatbot.domain.InfluencerProfileText;
import com.hapbang.auxiliary.chatbot.domain.InfluencerReason;
import com.hapbang.auxiliary.chatbot.domain.LectureRecommendReason;
import com.hapbang.auxiliary.chatbot.domain.LectureSnapshot;
import com.hapbang.auxiliary.chatbot.llm.recommend.RecommendMetrics.Pick;
import com.hapbang.auxiliary.chatbot.out.InfluencerRecommendStore;
import com.hapbang.auxiliary.chatbot.out.LectureRecommendStore;
import com.hapbang.auxiliary.chatbot.out.MemberSyncSource;
import com.hapbang.auxiliary.chatbot.out.RecommendVectorIndex;
import com.hapbang.shared.member.dto.MemberPageDto;

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
    private final LlmCallGuard llm;

    /** @param embeddingModel 평가에 쓸 임베딩 모델(캐시를 앞에 둔 모델) */
    public RecommendEvalAccess(ApplicationContext context, EmbeddingModel embeddingModel) {
        this.lectureStore = context.getBean(LectureRecommendStore.class);
        this.influencerStore = context.getBean(InfluencerRecommendStore.class);
        this.index = context.getBean(RecommendVectorIndex.class);
        this.visibility = context.getBean(MemberVisibility.class);
        this.metrics = context.getBean(ChatbotMetrics.class);
        this.llm = context.getBean(LlmCallGuard.class);
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

    /**
     * 버튼 경로(#720) 한 벌. 조합(모드)마다 설정과 채팅 모델로 운영 클래스를 그대로 만든다(#867 2단계).
     *
     * @param chatModel     평가용 채팅 모델(같은 회원 안에서 같은 요청을 다시 보내지 않는 감싸기)
     * @param categoryNames 분야 코드 → 이름(평가 데이터의 분야 표. 회원 API를 부르지 않는다)
     */
    public Buttons buttons(ChatbotLectureRecommendProperties lectureProperties,
            ChatbotInfluencerRecommendProperties influencerProperties, ChatbotRecommendLlmProperties llmProperties,
            ChatModel chatModel, Map<String, String> categoryNames) {
        return new Buttons(lectureProperties, influencerProperties, llmProperties, chatModel, categoryNames);
    }

    /**
     * 버튼 경로 한 번의 결과.
     *
     * @param pool     같은 요청의 맞춤 후보 풀(코드 순서). 리랭킹하지 않았을 때의 순서다
     * @param top      버튼 경로가 보여 준 추천(순서대로, 인기순 채움은 맞춤 후보가 아니다)
     * @param reasons  추천 ID → 이유 문장
     * @param fallback LLM 답을 쓰지 못하고 코드 순서로 답했다
     */
    public record ButtonResult(List<Long> pool, List<Pick> top, Map<Long, String> reasons, boolean fallback) {
    }

    /** 한 조합의 버튼 경로. 운영 UseCase를 그대로 부르고, 같은 쿼리 확장 결과로 코드 순서의 풀을 다시 만든다 */
    public final class Buttons {

        private final ChatbotRecommendLlmProperties llmProperties;
        private final QueryExpander expander;
        private final LectureRecommendUseCase lectureUseCase;
        private final InfluencerRecommendUseCase influencerUseCase;
        private final LectureRecommendExplainUseCase lectureButton;
        private final InfluencerRecommendExplainUseCase influencerButton;

        private Buttons(ChatbotLectureRecommendProperties lectureProperties,
                ChatbotInfluencerRecommendProperties influencerProperties, ChatbotRecommendLlmProperties llmProperties,
                ChatModel chatModel, Map<String, String> names) {
            this.llmProperties = llmProperties;
            StaticListableBeanFactory beans = new StaticListableBeanFactory(
                    Map.of("evalChatModel", chatModel, "evalChatClientBuilder", ChatClient.builder(chatModel)));
            ObjectProvider<ChatModel> model = beans.getBeanProvider(ChatModel.class);
            ObjectProvider<ChatClient.Builder> builder = beans.getBeanProvider(ChatClient.Builder.class);
            this.expander = new QueryExpander(model, builder, llm, llmProperties, metrics);
            RecommendReranker reranker = new RecommendReranker(model, builder, llm, llmProperties, metrics);
            ChatbotCategoryNames categoryNames = new ChatbotCategoryNames(new StaticCategoryNames(names), influencerProperties,
                    Clock.systemUTC());
            RecommendTasteSupport tasteSupport = new RecommendTasteSupport(influencerStore, categoryNames, llmProperties);
            PopularLectureCache popularCache = new PopularLectureCache(lectureStore, lectureProperties, Clock.systemUTC());
            this.lectureUseCase = new LectureRecommendUseCase(lectureStore, index, embedder, visibility, popularCache,
                    lectureProperties, metrics);
            this.influencerUseCase = new InfluencerRecommendUseCase(influencerStore, visibility, index, embedder,
                    influencerProperties, metrics);
            this.lectureButton = new LectureRecommendExplainUseCase(lectureUseCase, lectureStore, expander, reranker,
                    tasteSupport, lectureProperties, llmProperties);
            this.influencerButton = new InfluencerRecommendExplainUseCase(influencerUseCase, expander, reranker, tasteSupport,
                    categoryNames, influencerProperties, llmProperties);
        }

        /** 강의 버튼. 채팅 모델 감싸기가 같은 확장 답을 돌려줘 풀이 버튼 경로와 같다 */
        public ButtonResult lecture(long memberId, @Nullable String interest) {
            LectureRecommendExplainUseCase.Explained explained = lectureButton.recommend(memberId, interest);
            List<String> queries = interest == null ? null : expander.expand(interest);
            LectureRecommendUseCase.Pool pool = lectureUseCase.pool(memberId, queries, llmProperties.rerank().poolSize());
            List<Pick> top = new ArrayList<>();
            Map<Long, String> reasons = new HashMap<>();
            for (LectureRecommendation.Item item : explained.recommendation().items()) {
                top.add(new Pick(item.lectureId(), item.reason() != LectureRecommendReason.POPULAR));
                reasons.put(item.lectureId(), item.reasonText());
            }
            return new ButtonResult(pool.lectureIds(), top, reasons, explained.fallback());
        }

        /** 인플루언서 버튼(관심사 없음) */
        public ButtonResult influencer(long userId) {
            InfluencerRecommendExplainUseCase.Explained explained = influencerButton.recommend(userId, null);
            InfluencerRecommendUseCase.Pool pool = influencerUseCase.pool(userId, null, llmProperties.rerank().poolSize());
            List<Pick> top = new ArrayList<>();
            for (Candidate candidate : explained.recommendation().items()) {
                top.add(new Pick(candidate.memberId(), !candidate.reasons().contains(InfluencerReason.POPULAR)));
            }
            return new ButtonResult(pool.memberIds(), top, explained.explanations(), explained.fallback());
        }
    }

    /** 리랭킹 요청의 시스템 프롬프트인지(운영 프롬프트의 첫 줄로 본다, 쿼리 확장과 구분) */
    public static boolean isRerankPrompt(String systemText) {
        String firstLine = RecommendReranker.SYSTEM_PROMPT.lines().findFirst().orElseThrow();
        return systemText.startsWith(firstLine);
    }

    /** 리랭킹 요청이 LLM에 보여 준 후보 수(운영 프롬프트의 후보 줄 모양으로 센다) */
    public static int shownCandidates(String userText) {
        return (int) userText.lines().filter(line -> line.startsWith("- 후보 ")).count();
    }

    // 평가 데이터의 분야 표. 회원 목록은 쓰지 않는다
    private record StaticCategoryNames(Map<String, String> names) implements MemberSyncSource {

        @Override
        public MemberPageDto members(Long afterId, int size) {
            throw new UnsupportedOperationException("평가는 회원 목록을 받지 않는다");
        }

        @Override
        public Map<String, String> categoryNames() {
            return names;
        }
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
