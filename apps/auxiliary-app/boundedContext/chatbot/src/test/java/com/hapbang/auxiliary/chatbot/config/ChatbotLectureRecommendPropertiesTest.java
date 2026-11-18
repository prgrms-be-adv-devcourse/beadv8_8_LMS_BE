package com.hapbang.auxiliary.chatbot.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** 강의 추천의 제목 비율·자유 질문 관심사 비율 (#1007). 따로 정하지 않으면 #1007 전과 같게 동작한다 */
class ChatbotLectureRecommendPropertiesTest {

    private static final String PREFIX = "hapbang.chatbot.recommend.lecture.";

    // 기본값이 없는 두 값(프로필 yaml에 둔다)
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(LectureRecommendPropertiesConfig.class)
            .withPropertyValues(PREFIX + "interest-weight=0.3", PREFIX + "popularity-weight=0.05");

    @Test
    void 정하지_않으면_제목_비율은_0이고_자유_질문은_위젯과_같은_관심사_비율을_쓴다() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            ChatbotLectureRecommendProperties properties = context.getBean(ChatbotLectureRecommendProperties.class);
            assertThat(properties.titleWeight()).isZero();
            assertThat(properties.chatInterestWeight()).isNull();
            assertThat(properties.chatInterestWeightOrDefault()).isEqualTo(0.3);
        });
    }

    @Test
    void 자유_질문_관심사_비율을_정하면_그_값을_쓴다() {
        runner.withPropertyValues(PREFIX + "chat-interest-weight=0.7", PREFIX + "title-weight=1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ChatbotLectureRecommendProperties properties = context.getBean(ChatbotLectureRecommendProperties.class);
                    assertThat(properties.chatInterestWeightOrDefault()).isEqualTo(0.7);
                    assertThat(properties.titleWeight()).isEqualTo(1.0);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"title-weight=-0.1", "title-weight=1.1", "chat-interest-weight=-0.1", "chat-interest-weight=1.1"})
    void 범위를_벗어나면_뜨지_않는다(String value) {
        runner.withPropertyValues(PREFIX + value).run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ChatbotLectureRecommendProperties.class)
    static class LectureRecommendPropertiesConfig {
    }
}
