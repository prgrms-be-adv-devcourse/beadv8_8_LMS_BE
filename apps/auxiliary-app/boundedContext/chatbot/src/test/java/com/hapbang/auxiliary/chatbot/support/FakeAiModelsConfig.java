package com.hapbang.auxiliary.chatbot.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 가짜 채팅·임베딩 모델 빈(#715). 테스트 설정이 {@code spring.ai.model.*=none}이라 실제 공급자 빈은 없고, 이 빈만 있다.
 * Spring AI의 {@code ChatClient.Builder} 자동 설정은 이 {@code ChatModel}로 만들어진다. 테스트마다 {@code reset()}으로 되돌린다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FakeAiModelsConfig {

    @Bean
    FakeChatModel fakeChatModel() {
        return new FakeChatModel();
    }

    @Bean
    FakeEmbeddingModel fakeEmbeddingModel() {
        return new FakeEmbeddingModel();
    }
}
