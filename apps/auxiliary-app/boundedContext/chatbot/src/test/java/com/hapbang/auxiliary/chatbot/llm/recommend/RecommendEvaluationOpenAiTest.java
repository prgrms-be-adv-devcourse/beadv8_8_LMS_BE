package com.hapbang.auxiliary.chatbot.llm.recommend;

import java.nio.file.Path;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import com.hapbang.auxiliary.chatbot.support.ElasticsearchTestcontainersConfig;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 운영 임베딩(OpenAI text-embedding-3-small, 1536차원)으로 추천 가중치를 평가한다(#867). 환경 변수 {@code OPENAI_API_KEY}가 있을 때만 돈다.
 * 처음 한 번만 임베딩 API를 부르고, 그 뒤에는 캐시({@link CachingEmbeddingModel})를 읽는다. 실행 방법은 auxiliary-app README "테스트와 평가".
 * <ul>
 *     <li>{@link #tuning()}: 조정용 80명. {@value RecommendEvalGrid#GRID_ENV}·{@value RecommendEvalGrid#BASE_ENV}로 조합을 준다.</li>
 *     <li>{@link #holdout()}: 확인용 20명. {@value RecommendEvaluation#HOLDOUT_ENV}{@code =yes}일 때만 돌고, 그때는 {@link #tuning()}이 꺼진다.
 *     고정값 한 줄만 재고, 격자를 주면 실패한다(20명에 맞춰 값을 고르지 않게, 마지막에 한 번).</li>
 * </ul>
 */
@Tag("llm")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
@IntegrationTest
@Import(ElasticsearchTestcontainersConfig.class)
@TestPropertySource(properties = {
        "spring.ai.model.embedding=openai",
        "spring.ai.openai.api-key=${OPENAI_API_KEY}",
        "spring.ai.openai.embedding.model=text-embedding-3-small",
        // 운영(application-prod.yaml)과 같은 차원·접두어. index는 평가 전용 이름이고 끝나면 지운다
        "hapbang.chatbot.index.lecture-name=chatbot-lectures-openai-1536-eval",
        "hapbang.chatbot.index.influencer-name=chatbot-influencers-openai-1536-eval",
        "hapbang.chatbot.index.dimensions=1536",
        "hapbang.chatbot.index.document-prefix=",
        "hapbang.chatbot.index.query-prefix="
})
class RecommendEvaluationOpenAiTest extends RecommendEvaluation {

    @Override
    String modelName() {
        return "openai-text-embedding-3-small-1536";
    }

    @Override
    Path cacheDir() {
        return CachingEmbeddingModel.defaultDir();
    }

    @Test
    @DisabledIfEnvironmentVariable(named = HOLDOUT_ENV, matches = "yes")
    void tuning() {
        String base = System.getenv(RecommendEvalGrid.BASE_ENV);
        evaluateAndPrint(Group.TUNING, RecommendEvalGrid.combos(System.getenv(RecommendEvalGrid.GRID_ENV), base), base);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = HOLDOUT_ENV, matches = "yes")
    void holdout() {
        String grid = System.getenv(RecommendEvalGrid.GRID_ENV);
        if (grid != null && !grid.isBlank()) {
            throw new IllegalStateException("확인용 20명은 격자 없이 고정값 한 줄만 잽니다. " + RecommendEvalGrid.GRID_ENV
                    + "를 지우고 정한 값을 " + RecommendEvalGrid.BASE_ENV + "로 주세요");
        }
        String base = System.getenv(RecommendEvalGrid.BASE_ENV);
        evaluateAndPrint(Group.HOLDOUT, RecommendEvalGrid.combos(null, base), base);
    }
}
