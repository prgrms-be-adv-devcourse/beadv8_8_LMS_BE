package com.hapbang.auxiliary.chatbot.app;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doThrow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.hapbang.auxiliary.chatbot.out.InfluencerRecommendStore;
import com.hapbang.auxiliary.chatbot.support.FakeEmbeddingModel;

/**
 * 인플루언서 추천의 검색 장애 범위 (#720). 인기순으로 답하는 "검색 장애"는 ES·임베딩 호출 실패뿐이다.
 * 검색 구간 안의 DB 조회가 실패하면 삼키지 않고 그대로 올라간다. 저장소를 스파이로 바꿔 Spring 문맥을 따로 쓰므로 클래스를 나눴다.
 * <p>
 * 검색 구간 안의 DB 조회는 두 곳이다: 함께 마친 상대의 추천 가능 확인(상대 평균 벡터를 만들 때)과 kNN 뒤 후보의 추천 가능 확인.
 * 관심사가 있으면 상대 평균을 만들지 않으므로 kNN 뒤 확인만 지난다.
 */
class InfluencerRecommendSearchErrorTest extends InfluencerIntegrationTestBase {

    private static final String INTEREST = "롤 방송";

    @Autowired
    private InfluencerRecommendUseCase useCase;

    @MockitoSpyBean
    private InfluencerRecommendStore store;

    private final DataAccessResourceFailureException failure = new DataAccessResourceFailureException("DB 장애");

    @BeforeEach
    void setUpInfluencers() {
        embeddingModel.put(FakeEmbeddingModel.query(INTEREST), FakeEmbeddingModel.axis(0));
        influencer(1L, "GAME", "롤");
        influencer(2L, "GAME", "발로란트");
        index(1L, 2L);
        doThrow(failure).when(store).recommendableOf(anyCollection());
    }

    @Test
    @DisplayName("함께 마친 상대의 추천 가능 확인(DB, 상대 평균 벡터)이 실패하면 인기순으로 가리지 않고 예외가 그대로 올라간다")
    void dbErrorInPartnerMeanIsNotSwallowed() {
        assertThatThrownBy(() -> useCase.recommend(1L, null)).isSameAs(failure);
    }

    @Test
    @DisplayName("kNN 뒤 후보의 추천 가능 확인(DB)이 실패하면 인기순으로 가리지 않고 예외가 그대로 올라간다(관심사 요청은 상대 평균을 건너뛴다)")
    void dbErrorAfterKnnIsNotSwallowed() {
        assertThatThrownBy(() -> useCase.recommend(1L, INTEREST)).isSameAs(failure);
    }
}
