package com.hapbang.auxiliary.chatbot.llm;

import org.junit.jupiter.api.Tag;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import com.hapbang.auxiliary.chatbot.support.ElasticsearchTestcontainersConfig;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 로컬 Ollama 임베딩(nomic-embed-text)으로 FAQ 검색을 평가한다(#715). compose의 Ollama가 떠 있고 모델을 받아 둬야 한다.
 * 실행: {@code ./gradlew :chatbot:llmTest --tests '*FaqRetrievalEvaluationOllamaTest'}
 * <p>
 * 로컬은 호출이 되는지만 보는 개발용이라 품질 목표를 걸지 않는다(운영은 OpenAI). 2026-11-09 결과 hit@3 0.399, hit@1 0.169로
 * 낮다. nomic-embed-text가 영어 위주로 학습된 모델이라 한국어 문장의 뜻을 잘 가르지 못하는 것으로 본다(README "테스트와 평가").
 */
@Tag("llm")
@IntegrationTest
@Import(ElasticsearchTestcontainersConfig.class)
@TestPropertySource(properties = {
        "spring.ai.model.embedding=ollama",
        "spring.ai.ollama.base-url=http://localhost:11434",
        "spring.ai.ollama.init.pull-model-strategy=never",
        "spring.ai.ollama.embedding.model=nomic-embed-text",
        "hapbang.chatbot.faq.index-name=chatbot-faq-ollama-768-eval",
        "hapbang.chatbot.faq.dimensions=768",
        "hapbang.chatbot.faq.document-prefix=search_document: ",
        "hapbang.chatbot.faq.query-prefix=search_query: ",
        // 순위를 그대로 보려고 기준값을 0으로 둔다. 기준값 후보별 결과는 표로 찍는다
        "hapbang.chatbot.faq.similarity-threshold=0"
})
class FaqRetrievalEvaluationOllamaTest extends FaqRetrievalEvaluation {

    @Override
    String modelLabel() {
        return "Ollama nomic-embed-text (768)";
    }

    @Override
    double minHit3() {
        return 0;
    }
}
