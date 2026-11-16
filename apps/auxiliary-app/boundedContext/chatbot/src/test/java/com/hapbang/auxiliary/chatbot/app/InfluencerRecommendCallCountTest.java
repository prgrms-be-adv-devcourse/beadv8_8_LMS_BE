package com.hapbang.auxiliary.chatbot.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.hapbang.auxiliary.chatbot.app.InfluencerRecommendUseCase.Candidate;
import com.hapbang.auxiliary.chatbot.app.InfluencerRecommendUseCase.InfluencerRecommendation;
import com.hapbang.auxiliary.chatbot.domain.ApplySource;
import com.hapbang.auxiliary.chatbot.domain.IndexAction;
import com.hapbang.auxiliary.chatbot.domain.IndexType;
import com.hapbang.auxiliary.chatbot.domain.InfluencerReason;
import com.hapbang.auxiliary.chatbot.domain.RecommendBasis;
import com.hapbang.auxiliary.chatbot.out.RecommendVectorIndex;
import com.hapbang.auxiliary.chatbot.support.CallRecorder;
import com.hapbang.auxiliary.chatbot.support.CallRecorderConfig;
import com.hapbang.auxiliary.chatbot.support.ChatbotIntegrationTestBase;
import com.hapbang.auxiliary.chatbot.support.CopyRows;
import com.hapbang.auxiliary.chatbot.support.ElasticsearchTestcontainersConfig;
import com.hapbang.auxiliary.chatbot.support.FakeAiModelsConfig;
import com.hapbang.auxiliary.chatbot.support.FakeChatModel;
import com.hapbang.auxiliary.chatbot.support.FakeEmbeddingModel;
import com.hapbang.shared.appearance.domain.AppearanceResult;
import com.hapbang.shared.appearance.dto.AppearanceReviewSyncDto;
import com.hapbang.shared.appearance.dto.AppearanceSyncDto;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;

/**
 * 인플루언서 추천 한 번이 DB·ES를 몇 번 부르는지와 그때의 결과를 함께 고정한다 (#835 효율 리팩터).
 * 리팩터 전은 SQL 위젯 12회·버튼 13회, ES 검색 4회였다.
 * <p>
 * 회원 1(게임)은 3(음악)과 출연을 함께 마쳐 취향이 내 분야 + 상대 평균을 섞은 값이다. 2(게임 비슷)·3·4(요리)가 맞춤 후보,
 * 5는 정지, 7·8은 프로필이 비어 인기순으로만 채워진다. 3은 리뷰(별점 4)를 받았다.
 */
@Import({FakeAiModelsConfig.class, ElasticsearchTestcontainersConfig.class, CallRecorderConfig.class})
class InfluencerRecommendCallCountTest extends ChatbotIntegrationTestBase {

    @Autowired
    private InfluencerRecommendUseCase useCase;

    @Autowired
    private InfluencerRecommendExplainUseCase explainUseCase;

    @Autowired
    private CopyApplier copyApplier;

    @Autowired
    private InfluencerIndexTaskHandler handler;

    @Autowired
    private RecommendVectorIndex index;

    @Autowired
    private FakeEmbeddingModel embeddingModel;

    @Autowired
    private FakeChatModel chatModel;

    private final Map<Long, Long> versions = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        embeddingModel.reset();
        chatModel.reset();
        versions.clear();
        deleteIndex();
        embeddingModel.put("분야: 게임", FakeEmbeddingModel.axis(0));
        embeddingModel.put("분야: 음악", FakeEmbeddingModel.axis(1));
        embeddingModel.put("분야: 요리", FakeEmbeddingModel.axis(2));
        embeddingModel.put("분야: 게임\n소개: 발로란트", FakeEmbeddingModel.withCosine(0, 3, 0.9));
        influencer(1L, "GAME", "롤");
        influencer(2L, "GAME", "발로란트");
        influencer(3L, "MUSIC", "노래");
        influencer(4L, "COOKING", "요리");
        influencer(5L, "GAME", "롤");
        index(1L, 2L, 3L, 4L, 5L);
        member(5L, Role.INFLUENCER, UserStatus.SUSPENDED, List.of("GAME"), "롤");
        index(5L);
        member(7L, Role.INFLUENCER, UserStatus.ACTIVE, List.of(), null);
        member(8L, Role.INFLUENCER, UserStatus.ACTIVE, List.of(), null);
        copyApplier.apply(new AppearanceSyncDto(100L, 1L, 3L, AppearanceResult.COMPLETED, CopyRows.SCHEDULED_AT, 0L),
                ApplySource.EVENT);
        copyApplier.apply(new AppearanceReviewSyncDto(500L, 100L, 3L, 4), ApplySource.EVENT);
    }

    @AfterEach
    void tearDown() throws Exception {
        deleteIndex();
    }

    @Test
    @DisplayName("위젯 경로(맞춤 3명 + 인기순 2명): 결과와 함께 SQL 8회·ES 검색 3회")
    void widget() {
        CallRecorder.Recorded<InfluencerRecommendation> recorded = CallRecorder.record(() -> useCase.recommend(1L, null));

        InfluencerRecommendation result = recorded.value();
        assertThat(result.basis()).isEqualTo(RecommendBasis.PERSONALIZED);
        assertThat(result.needsInterest()).isFalse();
        assertResult(result.items());
        assertCalls(recorded.calls());
    }

    @Test
    @DisplayName("버튼 경로(LLM이 둘째를 고름): 결과·프롬프트의 후보 줄과 함께 SQL 8회·ES 검색 3회(프롬프트 분야를 다시 읽지 않는다)")
    void button() {
        chatModel.reply("""
                {"picks": [{"candidate": 2, "reason": "같은 게임을 해요"}]}""");

        CallRecorder.Recorded<InfluencerRecommendExplainUseCase.Explained> recorded =
                CallRecorder.record(() -> explainUseCase.recommend(1L, null));

        InfluencerRecommendExplainUseCase.Explained explained = recorded.value();
        assertThat(explained.fallback()).isFalse();
        List<Candidate> items = explained.recommendation().items();
        assertThat(items).extracting(Candidate::memberId).containsExactly(2L, 3L, 4L, 8L, 7L);
        assertThat(explained.explanations()).containsEntry(2L, "같은 게임을 해요");
        assertThat(chatModel.lastPrompt().getContents())
                .contains("완료한 출연: 1회 | 평균 별점: 4.0 | 추천 근거: ")
                .contains("완료한 출연: 0회 | 평균 별점: 리뷰 없음 | 추천 근거: ");
        assertCalls(recorded.calls());
    }

    // SQL: 요청 회원 보임, 함께 마친 상대, 내·상대 프로필, 전체 평균 별점, 후보 추천 가능(프로필), 후보 인기 재료,
    // 인기순 채우기 인기 재료, 채운 회원 프로필. ES: 내·상대 벡터, kNN, 후보 벡터
    private static void assertCalls(CallRecorder.Calls calls) {
        assertThat(calls.sql()).as(calls.toString()).hasSize(8);
        assertThat(calls.es()).as(calls.toString()).containsExactly("search", "search", "search");
    }

    // 3·2·4는 맞춤 후보(유사도와 인기를 섞은 순서), 8·7은 인기순 채우기(인기가 같아 회원 ID 내림차순)
    private static void assertResult(List<Candidate> items) {
        assertThat(items).extracting(Candidate::memberId, Candidate::categories, Candidate::completedAppearances,
                        Candidate::averageRating)
                .containsExactly(
                        tuple(3L, List.of("MUSIC"), 1L, 4.0),
                        tuple(2L, List.of("GAME"), 0L, null),
                        tuple(4L, List.of("COOKING"), 0L, null),
                        tuple(8L, List.of(), 0L, null),
                        tuple(7L, List.of(), 0L, null));
        assertThat(items).extracting(Candidate::reasons)
                .containsExactly(
                        Set.of(InfluencerReason.SIMILAR_TO_PARTNERS, InfluencerReason.PAST_PARTNER),
                        Set.of(InfluencerReason.SIMILAR_CATEGORY),
                        Set.of(InfluencerReason.SIMILAR_CATEGORY),
                        Set.of(InfluencerReason.POPULAR),
                        Set.of(InfluencerReason.POPULAR));
        assertThat(items.get(0).score()).isGreaterThan(items.get(1).score());
        assertThat(items.get(3).score()).isZero();
    }

    private void influencer(long id, String category, String bio) {
        member(id, Role.INFLUENCER, UserStatus.ACTIVE, List.of(category), bio);
    }

    private void member(long id, Role role, UserStatus status, List<String> categories, String bio) {
        long version = versions.merge(id, 1L, Long::sum);
        copyApplier.apply(new UserDto(id, "회원" + id, role, status, version, categories, bio));
    }

    private void index(long... ids) {
        SequencedMap<String, IndexAction> docs = new LinkedHashMap<>();
        for (long id : ids) {
            docs.put(String.valueOf(id), IndexAction.UPSERT);
        }
        assertThat(handler.handle(docs)).isEmpty();
        index.refresh(IndexType.INFLUENCER);
    }

    private static void deleteIndex() throws Exception {
        ElasticsearchTestcontainersConfig.deleteIndex("chatbot-influencers-test-8");
    }
}
