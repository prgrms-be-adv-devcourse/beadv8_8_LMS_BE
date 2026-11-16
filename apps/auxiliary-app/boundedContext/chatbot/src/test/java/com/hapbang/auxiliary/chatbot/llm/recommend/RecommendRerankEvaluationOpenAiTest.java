package com.hapbang.auxiliary.chatbot.llm.recommend;

import java.nio.file.Path;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import com.hapbang.auxiliary.chatbot.llm.recommend.RerankEvaluation.Mode;
import com.hapbang.auxiliary.chatbot.llm.recommend.RerankEvaluation.Settings;
import com.hapbang.auxiliary.chatbot.support.ElasticsearchTestcontainersConfig;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 운영 OpenAI 모델(임베딩 text-embedding-3-small, 대화 gpt-4o-mini)로 버튼 경로 리랭킹을 평가한다(#867 2단계, #948).
 * 환경 변수 {@code OPENAI_API_KEY}가 있을 때만 돈다. <b>LLM 비용이 든다.</b> 본체는 {@link RerankEvaluation}.
 * <ul>
 *     <li>{@link #rerank()}: 조정용 80명, 기본 3회.</li>
 *     <li>{@link #rerankHoldout()}: 확인용 20명, 기본 1회. {@value RecommendEvaluation#HOLDOUT_ENV}{@code =yes}일 때만 돈다.</li>
 * </ul>
 * 환경 변수: {@value #MODES_ENV}(방식, 쉼표: none·public·public-t0·public-noexp, 비우면 모두), {@value #RUNS_ENV}(회차),
 * {@value #MEMBERS_ENV}(앞에서 N명만, 시험 실행), {@value #MODEL_ENV}(리랭킹 모델, 비우면 gpt-4o-mini), {@value #POOL_ENV}(풀 크기, 기본 20),
 * {@value RecommendEvalGrid#BASE_ENV}(운영 가중치 위에 더 덮을 값, 비우면 운영 값 그대로).
 */
@Tag("llm")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
@IntegrationTest
@Import(ElasticsearchTestcontainersConfig.class)
@TestPropertySource(properties = {
        "spring.ai.model.embedding=openai",
        "spring.ai.model.chat=openai",
        "spring.ai.openai.api-key=${OPENAI_API_KEY}",
        "spring.ai.openai.embedding.model=text-embedding-3-small",
        // 운영(application-prod.yaml)과 같은 대화 모델·온도·시간 상한·재시도
        "spring.ai.openai.chat.model=gpt-4o-mini",
        "spring.ai.openai.chat.temperature=0.2",
        "spring.ai.openai.chat.timeout=50s",
        "spring.ai.openai.chat.max-retries=0",
        "hapbang.chatbot.llm.max-concurrent-calls=" + RerankEvaluation.THREADS,
        "hapbang.chatbot.index.lecture-name=chatbot-lectures-openai-1536-eval",
        "hapbang.chatbot.index.influencer-name=chatbot-influencers-openai-1536-eval",
        "hapbang.chatbot.index.dimensions=1536",
        "hapbang.chatbot.index.document-prefix=",
        "hapbang.chatbot.index.query-prefix="
})
class RecommendRerankEvaluationOpenAiTest extends RecommendEvaluation {

    static final String MODES_ENV = "RECOMMEND_RERANK_MODES";
    static final String RUNS_ENV = "RECOMMEND_RERANK_RUNS";
    static final String MEMBERS_ENV = "RECOMMEND_RERANK_MEMBERS";
    static final String MODEL_ENV = "RECOMMEND_RERANK_MODEL";
    static final String POOL_ENV = "RECOMMEND_RERANK_POOL";

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
    void rerank() throws Exception {
        print(Group.TUNING, 3);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = HOLDOUT_ENV, matches = "yes")
    void rerankHoldout() throws Exception {
        print(Group.HOLDOUT, 1);
    }

    private void print(Group group, int defaultRuns) throws Exception {
        Settings settings = new Settings(Mode.parse(System.getenv(MODES_ENV)), Integer.parseInt(env(RUNS_ENV, defaultRuns + "")),
                Integer.parseInt(env(MEMBERS_ENV, "0")), env(MODEL_ENV, ""), Integer.parseInt(env(POOL_ENV, "20")),
                env(RecommendEvalGrid.BASE_ENV, ""));
        System.out.println(rerankEvaluation().run(group, settings).text());
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
