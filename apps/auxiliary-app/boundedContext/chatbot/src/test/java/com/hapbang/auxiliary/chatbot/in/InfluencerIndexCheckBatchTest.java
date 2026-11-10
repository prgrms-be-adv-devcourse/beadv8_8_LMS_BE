package com.hapbang.auxiliary.chatbot.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.hapbang.auxiliary.chatbot.app.InfluencerIndexChecker;
import com.hapbang.auxiliary.chatbot.config.ChatbotInfluencerRecommendProperties;

/** 인플루언서 색인 정합 점검 배치 (#719): 연속 실패 알림 */
@ExtendWith(OutputCaptureExtension.class)
class InfluencerIndexCheckBatchTest {

    private static final String ALERT = "[CHATBOT_INFLUENCER_CHECK_FAILED]";

    private final InfluencerIndexChecker checker = mock(InfluencerIndexChecker.class);
    private final InfluencerIndexCheckBatch batch = new InfluencerIndexCheckBatch(checker, properties(3));

    @Test
    @DisplayName("연속 3번 실패하면 ERROR 알림, 그 전은 WARN. 성공하면 다시 0부터 센다")
    void alertsAfterConsecutiveFailures(CapturedOutput output) {
        doThrow(new IllegalStateException("ES 장애")).when(checker).check();
        batch.check();
        batch.check();
        assertThat(output).doesNotContain(ALERT).contains("failures=2");

        batch.check();
        assertThat(output).contains(ALERT + " 인플루언서 색인 정합 점검이 연속으로 실패했다 failures=3");

        doReturn(0).when(checker).check();
        batch.check();
        doThrow(new IllegalStateException("ES 장애")).when(checker).check();
        int alertsBefore = count(output.getAll(), ALERT);
        batch.check();
        assertThat(count(output.getAll(), ALERT)).isEqualTo(alertsBefore);
        assertThat(output.getAll()).contains("failures=1");
    }

    private static int count(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) {
            count++;
        }
        return count;
    }

    private static ChatbotInfluencerRecommendProperties properties(int alertAfter) {
        return new ChatbotInfluencerRecommendProperties(5, 50, 200, 0.5, 0.2,
                new ChatbotInfluencerRecommendProperties.Popularity(1.0, 1.0, 5),
                new ChatbotInfluencerRecommendProperties.RateLimit(10, Duration.ofMinutes(1), 10000),
                Duration.ofSeconds(15), 100, Duration.ofMinutes(10), 500, alertAfter);
    }
}
