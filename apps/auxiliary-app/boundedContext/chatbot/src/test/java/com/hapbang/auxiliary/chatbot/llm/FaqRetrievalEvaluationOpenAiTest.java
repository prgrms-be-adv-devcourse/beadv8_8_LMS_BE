package com.hapbang.auxiliary.chatbot.llm;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import com.hapbang.auxiliary.chatbot.support.ElasticsearchTestcontainersConfig;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 운영 임베딩(OpenAI text-embedding-3-small)으로 FAQ 검색을 평가한다(#715). 배포 전 한 번 돌려 운영 기준값
 * ({@code application-prod.yaml}의 similarity-threshold)을 정한다. 비용이 들어 환경 변수 {@code OPENAI_API_KEY}가 있을 때만 돈다.
 * 실행: {@code OPENAI_API_KEY=... ./gradlew :chatbot:llmTest --tests '*FaqRetrievalEvaluationOpenAiTest'} (FAQ 53건 + 질문 155개 임베딩)
 */
@Tag("llm")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
@IntegrationTest
@Import(ElasticsearchTestcontainersConfig.class)
@TestPropertySource(properties = {
        "spring.ai.model.embedding=openai",
        "spring.ai.openai.api-key=${OPENAI_API_KEY}",
        "spring.ai.openai.embedding.model=text-embedding-3-small",
        "hapbang.chatbot.faq.index-name=chatbot-faq-openai-1536-eval",
        "hapbang.chatbot.faq.dimensions=1536",
        "hapbang.chatbot.faq.similarity-threshold=0"
})
class FaqRetrievalEvaluationOpenAiTest extends FaqRetrievalEvaluation {

    @Override
    String modelLabel() {
        return "OpenAI text-embedding-3-small (1536)";
    }

    // 이슈 #715 목표. 운영 공급자라 목표에 못 미치면 실패한다
    @Override
    double minHit3() {
        return 0.85;
    }
}
