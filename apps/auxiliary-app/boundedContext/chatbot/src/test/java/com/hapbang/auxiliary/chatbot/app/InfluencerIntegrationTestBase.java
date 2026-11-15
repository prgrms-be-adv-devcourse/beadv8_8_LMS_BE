package com.hapbang.auxiliary.chatbot.app;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.hapbang.auxiliary.chatbot.app.InfluencerIndexPlan.IndexedProfile;
import com.hapbang.auxiliary.chatbot.domain.IndexAction;
import com.hapbang.auxiliary.chatbot.domain.IndexType;
import com.hapbang.auxiliary.chatbot.out.RecommendVectorIndex;
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
 * 인플루언서 추천 통합 테스트 기반 (#719). 실제 PostgreSQL·Elasticsearch(Testcontainers)와 가짜 임베딩·채팅 모델을 쓴다.
 * <p>
 * 가짜 임베딩 표: 분야 글이 "분야: 게임"으로 시작하면 축 0, "분야: 음악"은 축 1, "분야: 요리"는 축 2다(가짜 회원 API의 분야 이름).
 * 테스트가 더 자세한 글(소개 포함)을 {@link FakeEmbeddingModel#put}으로 정하면 그것이 이긴다(가장 긴 열쇠).
 */
@Import({FakeAiModelsConfig.class, ElasticsearchTestcontainersConfig.class})
abstract class InfluencerIntegrationTestBase extends ChatbotIntegrationTestBase {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired
    protected CopyApplier copyApplier;

    @Autowired
    protected InfluencerIndexTaskHandler handler;

    @Autowired
    protected RecommendVectorIndex index;

    @Autowired
    protected FakeEmbeddingModel embeddingModel;

    @Autowired
    protected FakeChatModel chatModel;

    private final Map<Long, Long> versions = new HashMap<>();

    @BeforeEach
    void resetInfluencer() throws Exception {
        embeddingModel.reset();
        chatModel.reset();
        versions.clear();
        embeddingModel.put("분야: 게임", FakeEmbeddingModel.axis(0));
        embeddingModel.put("분야: 음악", FakeEmbeddingModel.axis(1));
        embeddingModel.put("분야: 요리", FakeEmbeddingModel.axis(2));
        deleteIndex();
    }

    @AfterEach
    void deleteInfluencerIndex() throws Exception {
        deleteIndex();
    }

    /** 회원 사본을 반영한다(이벤트·싱크와 같은 적용기). version은 회원마다 1씩 올린다 */
    protected void member(long id, Role role, UserStatus status, List<String> categories, String bio) {
        long version = versions.merge(id, 1L, Long::sum);
        copyApplier.apply(new UserDto(id, "회원" + id, role, status, version, categories, bio));
    }

    /** 추천 가능한 인플루언서 */
    protected void influencer(long id, String category, String bio) {
        member(id, Role.INFLUENCER, UserStatus.ACTIVE, List.of(category), bio);
    }

    /** 처리기로 색인하고 바로 검색되게 한다. 실패한 문서를 돌려준다 */
    protected Map<String, RuntimeException> index(long... ids) {
        SequencedMap<String, IndexAction> docs = new LinkedHashMap<>();
        for (long id : ids) {
            docs.put(String.valueOf(id), IndexAction.UPSERT);
        }
        Map<String, RuntimeException> failures = handler.handle(docs);
        index.refresh(IndexType.INFLUENCER);
        return failures;
    }

    /** 색인 문서(벡터 밖 필드). 없으면 null */
    protected IndexedProfile document(long id) {
        return index.documents(IndexType.INFLUENCER, List.of(String.valueOf(id)), IndexedProfile.class)
                .get(String.valueOf(id));
    }

    /** 두 회원이 함께 마친 출연 */
    protected void completed(long appearanceId, long hostId, long guestId) {
        copyApplier.apply(new AppearanceSyncDto(appearanceId, hostId, guestId, AppearanceResult.COMPLETED,
                CopyRows.SCHEDULED_AT, 0L), null);
    }

    /** 받은 출연 리뷰 */
    protected void review(long reviewId, long appearanceId, long revieweeId, int rating) {
        copyApplier.apply(new AppearanceReviewSyncDto(reviewId, appearanceId, revieweeId, rating), null);
    }

    private void deleteIndex() throws Exception {
        HTTP.send(HttpRequest.newBuilder(URI.create(ElasticsearchTestcontainersConfig.uri() + "/chatbot-influencers-test-8"))
                .DELETE().build(), HttpResponse.BodyHandlers.discarding());
    }
}
