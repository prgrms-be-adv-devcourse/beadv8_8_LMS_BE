package com.hapbang.auxiliary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import com.hapbang.auxiliary.chatbot.config.ChatbotInfluencerRecommendProperties;
import com.hapbang.auxiliary.chatbot.config.ChatbotLectureRecommendProperties;
import com.hapbang.auxiliary.chatbot.config.ChatbotRecommendLlmProperties;
import com.hapbang.auxiliary.chatbot.config.ChatbotRecommendLlmProperties.Taste;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

/**
 * 운영 프로필의 추천 가중치(#867)와 리랭킹 설정(#948)이 설정 record에 그대로 묶이는지 확인한다.
 * <p>
 * 운영 동작을 바꾸는 값이 {@code application-prod.yaml}에만 있고 다른 테스트는 이 파일을 읽지 않는다. 키를 잘못 쓰면(예:
 * {@code rating-1}) 오류 없이 무시되고 기본값으로 배포되므로, 알 수 없는 키가 있으면 실패하게 묶는다({@link NoUnboundElementsBindHandler}).
 * 앱 전체를 띄우지 않고 실행 설정 파일(공통 + prod)만 읽는다.
 */
class ProdRecommendWeightsTest {

    private static final String LECTURE = "hapbang.chatbot.recommend.lecture";
    private static final String INFLUENCER = "hapbang.chatbot.recommend.influencer";
    private static final String RERANK = "hapbang.chatbot.recommend.rerank";
    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void 강의_가중치는_평가에서_정한_값이고_나머지는_기본값이다() throws IOException {
        ChatbotLectureRecommendProperties lecture = bind(LECTURE, ChatbotLectureRecommendProperties.class);

        assertThat(VALIDATOR.validate(lecture)).isEmpty();
        assertThat(lecture.popularityWeight()).isEqualTo(0.2);
        assertThat(lecture.interestWeight()).isEqualTo(0.3);
        assertThat(lecture.weights().rating1()).isEqualTo(-1.0);
        assertThat(lecture.weights().rating2()).isEqualTo(-0.6);
        assertThat(lecture.weights().rating3()).isEqualTo(0.0);
        assertThat(lecture.weights().rating4()).isEqualTo(0.3);
        assertThat(lecture.weights().rating5()).isEqualTo(0.5);
        assertThat(lecture.signalLimit()).isEqualTo(50);
    }

    // #1007: 임시값(평가 없이 정함). 제목 비율은 바꾸면 모든 강의를 다시 임베딩하므로 배포되는 값을 고정해 둔다
    @Test
    void 자유_질문_관심사_비율은_0_7이고_강의_제목_비율은_0_3이다() throws IOException {
        ChatbotLectureRecommendProperties lecture = bind(LECTURE, ChatbotLectureRecommendProperties.class);

        assertThat(lecture.chatInterestWeight()).isEqualTo(0.7);
        assertThat(lecture.chatInterestWeightOrDefault()).isEqualTo(0.7);
        assertThat(lecture.interestWeight()).isEqualTo(0.3);
        assertThat(lecture.titleWeight()).isEqualTo(0.3);
    }

    // 1점 강의를 취향에서 빼려면 구매 + 1점 보정이 0 이하여야 한다(LectureSignals는 0보다 큰 신호만 남긴다). 2점은 0.4로 남긴다
    @Test
    void 구매와_1점_보정이_상쇄되어_1점_강의는_신호에서_빠지고_2점은_약하게_남는다() throws IOException {
        ChatbotLectureRecommendProperties lecture = bind(LECTURE, ChatbotLectureRecommendProperties.class);

        assertThat(lecture.weights().owned()).isEqualTo(1.0);
        assertThat(lecture.weights().cart()).isEqualTo(0.6);
        assertThat(lecture.weights().owned() + lecture.weights().rating1()).isLessThanOrEqualTo(0.0);
        assertThat(lecture.weights().owned() + lecture.weights().rating2()).isCloseTo(0.4, offset(1e-9));
    }

    @Test
    void 인플루언서_가중치는_평가에서_정한_값이고_나머지는_기본값이다() throws IOException {
        ChatbotInfluencerRecommendProperties influencer = bind(INFLUENCER, ChatbotInfluencerRecommendProperties.class);

        assertThat(VALIDATOR.validate(influencer)).isEmpty();
        assertThat(influencer.popularityWeight()).isEqualTo(0.1);
        assertThat(influencer.partnerWeight()).isEqualTo(0.5);
        assertThat(influencer.popularity().completedWeight()).isEqualTo(1.0);
        assertThat(influencer.popularity().ratingWeight()).isEqualTo(1.0);
        assertThat(influencer.popularity().priorReviews()).isEqualTo(5.0);
    }

    @Test
    void 리랭킹은_공개_취향과_온도_0과_15초_상한이고_나머지는_기본값이다() throws IOException {
        ChatbotRecommendLlmProperties.Rerank rerank = bind(RERANK, ChatbotRecommendLlmProperties.Rerank.class);

        assertThat(VALIDATOR.validate(rerank)).isEmpty();
        assertThat(rerank.taste()).isEqualTo(Taste.PUBLIC);
        assertThat(rerank.temperature()).isEqualTo(0.0);
        assertThat(rerank.timeout()).isEqualTo(Duration.ofSeconds(15));
        assertThat(rerank.enabled()).isTrue();
        assertThat(rerank.model()).isEmpty();
        assertThat(rerank.poolSize()).isEqualTo(20);
    }

    // 설정 record가 모르는 키가 있으면 실패한다(오타가 조용히 기본값으로 배포되지 않게)
    private static <T> T bind(String name, Class<T> type) throws IOException {
        return prodBinder().bindOrCreate(name, Bindable.of(type), new NoUnboundElementsBindHandler(BindHandler.DEFAULT));
    }

    // prod가 공통 설정보다 앞선다(Spring Boot 프로필 순서와 같다)
    private static Binder prodBinder() throws IOException {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = new ArrayList<>();
        sources.addAll(loader.load("prod", new ClassPathResource("application-prod.yaml")));
        sources.addAll(loader.load("default", new ClassPathResource("application.yaml")));
        List<ConfigurationPropertySource> configurationSources = new ArrayList<>();
        ConfigurationPropertySources.from(sources).forEach(configurationSources::add);
        return new Binder(configurationSources);
    }
}
