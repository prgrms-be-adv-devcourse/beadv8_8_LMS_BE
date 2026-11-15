package com.hapbang.auxiliary.chatbot.in.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 운영 기본값의 재시도 방식 (#835). 리스너 테스트는 간격을 줄인 설정을 끼우므로 운영 값은 여기서 본다 */
class ChatbotKafkaSettingsTest {

    @Test
    @DisplayName("회원 토픽은 DLT로 보내지 않고 10초마다 성공할 때까지 다시 시도한다")
    void memberRetriesUntilSuccess() {
        assertThat(ChatbotKafkaSettings.memberDefaults().retry())
                .isEqualTo(new ChatbotKafkaSettings.Retry.UntilSuccess(Duration.ofSeconds(10)));
    }

    @Test
    @DisplayName("출연·강의 토픽은 1초부터 두 배씩(최대 10초) 3번 다시 시도한 뒤 DLT로 보낸다")
    void appearanceAndLectureGoToDeadLetter() {
        ChatbotKafkaSettings.Retry thenDeadLetter =
                new ChatbotKafkaSettings.Retry.ThenDeadLetter(3, Duration.ofSeconds(1), Duration.ofSeconds(10));

        assertThat(ChatbotKafkaSettings.appearanceDefaults().retry()).isEqualTo(thenDeadLetter);
        assertThat(ChatbotKafkaSettings.lectureDefaults().retry()).isEqualTo(thenDeadLetter);
    }
}
