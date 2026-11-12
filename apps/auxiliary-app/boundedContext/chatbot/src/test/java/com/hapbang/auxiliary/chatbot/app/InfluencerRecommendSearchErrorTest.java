package com.hapbang.auxiliary.chatbot.app;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doThrow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.hapbang.auxiliary.chatbot.out.InfluencerRecommendStore;

/**
 * 인플루언서 추천의 검색 장애 범위 (#720). 인기순으로 답하는 "검색 장애"는 ES·임베딩 호출 실패뿐이다.
 * 검색 구간 안의 DB 조회가 실패하면 삼키지 않고 그대로 올라간다. 저장소를 스파이로 바꿔 Spring 문맥을 따로 쓰므로 클래스를 나눴다.
 */
class InfluencerRecommendSearchErrorTest extends InfluencerIntegrationTestBase {

    @Autowired
    private InfluencerRecommendUseCase useCase;

    @MockitoSpyBean
    private InfluencerRecommendStore store;

    @Test
    @DisplayName("kNN 뒤 추천 가능 확인(DB)이 실패하면 인기순으로 가리지 않고 예외가 그대로 올라간다")
    void dbErrorInSearchIsNotSwallowed() {
        influencer(1L, "GAME", "롤");
        influencer(2L, "GAME", "발로란트");
        index(1L, 2L);
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("DB 장애");
        doThrow(failure).when(store).recommendableOf(anyCollection());

        assertThatThrownBy(() -> useCase.recommend(1L, null)).isSameAs(failure);
    }
}
