package com.hapbang.auxiliary.chatbot.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.hapbang.auxiliary.chatbot.config.ChatbotInfluencerRecommendProperties;
import com.hapbang.auxiliary.chatbot.support.ChatbotIntegrationTestBase;

/**
 * 분야 이름 표 (#719). 테스트 시계로 보관 시간(10분)을 넘긴다(시간에 기대지 않는다). 테스트마다 새 객체를 만들어 앞 테스트의 표를 쓰지 않는다.
 */
class ContentCategoryNamesTest extends ChatbotIntegrationTestBase {

    private static final Duration AFTER_TTL = Duration.ofMinutes(11);

    @Autowired
    private ChatbotInfluencerRecommendProperties properties;

    private ContentCategoryNames names;

    @BeforeEach
    void setUp() {
        names = new ContentCategoryNames(memberSource, properties, clockBean);
    }

    @Test
    @DisplayName("보관 시간 안에는 회원 API를 다시 부르지 않고, 지나면 다시 받는다")
    void cachesUntilTtl() {
        assertThat(names.current()).containsEntry("GAME", "게임");
        names.current();
        assertThat(memberSource.categoryCalls()).isEqualTo(1);

        memberSource.putCategoryName("GAME", "게임 방송");
        clock.advance(AFTER_TTL);

        assertThat(names.current()).containsEntry("GAME", "게임 방송");
        assertThat(memberSource.categoryCalls()).isEqualTo(2);
    }

    @Test
    @DisplayName("다시 받지 못하면 들고 있던 표를 쓰고, 보관 시간 동안 다시 부르지 않는다. 회복되면 새 표")
    void keepsTableWhileFailing() {
        names.current();
        memberSource.failCategoryNames(new IllegalStateException("회원 API 장애"));
        clock.advance(AFTER_TTL);

        assertThat(names.current()).containsEntry("GAME", "게임");
        assertThat(names.current()).containsEntry("GAME", "게임");
        assertThat(memberSource.categoryCalls()).isEqualTo(2);

        memberSource.reset();
        memberSource.putCategoryName("GAME", "게임 방송");
        clock.advance(AFTER_TTL);
        assertThat(names.current()).containsEntry("GAME", "게임 방송");
    }

    @Test
    @DisplayName("한 번도 받지 못했으면 current()는 예외(색인 처리기는 다시 시도), namesOf는 코드를 그대로 보여 준다")
    void neverFetched() {
        memberSource.failCategoryNames(new IllegalStateException("회원 API 장애"));

        assertThatThrownBy(names::current).isInstanceOf(IllegalStateException.class);
        assertThat(names.namesOf(List.of("GAME", "MUSIC"))).containsExactly("GAME", "MUSIC");
    }
}
