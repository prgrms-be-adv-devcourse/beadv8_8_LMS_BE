package com.hapbang.auxiliary.chatbot.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.hapbang.auxiliary.chatbot.config.ChatbotInfluencerRecommendProperties;
import com.hapbang.auxiliary.chatbot.out.IndexTaskStore;
import com.hapbang.auxiliary.chatbot.out.InfluencerRecommendStore;
import com.hapbang.auxiliary.chatbot.out.RecommendVectorIndex;

/** 인플루언서 색인 정시 점검 (#719): 연속 실패 알림. 설정은 운영과 같이 기본값으로 묶고 알림 횟수만 정한다 */
@ExtendWith(OutputCaptureExtension.class)
class InfluencerIndexCheckerScheduleTest {

    private static final String ALERT = "[CHATBOT_INFLUENCER_CHECK_FAILED]";
    private static final String PREFIX = "hapbang.chatbot.recommend.influencer";

    private final InfluencerIndexTaskHandler handler = mock(InfluencerIndexTaskHandler.class);
    private final ChatbotInfluencerRecommendProperties properties = properties(Map.of("check-alert-after-failures", "3"));
    private final InfluencerIndexChecker checker = new InfluencerIndexChecker(handler, mock(RecommendVectorIndex.class),
            mock(InfluencerRecommendStore.class), mock(ContentCategoryNames.class), mock(IndexTaskStore.class), properties,
            Clock.fixed(Instant.parse("2026-11-11T00:00:00Z"), ZoneOffset.UTC));

    @Test
    @DisplayName("설정한 횟수(3번) 연속 실패하면 ERROR 알림, 그 전은 WARN. 성공하면 다시 0부터 센다. 예외는 밖으로 나가지 않는다")
    void alertsAfterConsecutiveFailures(CapturedOutput output) {
        doReturn(true).when(handler).available();
        doThrow(new IllegalStateException("ES 장애")).when(handler).ensureIndex();
        for (int i = 1; i < properties.checkAlertAfterFailures(); i++) {
            checker.checkOnSchedule();
        }
        assertThat(output).doesNotContain(ALERT).contains("failures=" + (properties.checkAlertAfterFailures() - 1));

        checker.checkOnSchedule();
        assertThat(output).contains(ALERT + " 인플루언서 색인 정합 점검이 연속으로 실패했다 failures="
                + properties.checkAlertAfterFailures());

        doReturn(false).when(handler).ensureIndex();
        checker.checkOnSchedule();
        doThrow(new IllegalStateException("ES 장애")).when(handler).ensureIndex();
        int alertsBefore = count(output.getAll(), ALERT);
        checker.checkOnSchedule();
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

    // 운영과 같은 바인딩(@DefaultValue)으로 만든다. 생성자 인자를 순서대로 늘어놓지 않는다
    private static ChatbotInfluencerRecommendProperties properties(Map<String, String> values) {
        Map<String, String> prefixed = new HashMap<>();
        values.forEach((key, value) -> prefixed.put(PREFIX + "." + key, value));
        return new Binder(new MapConfigurationPropertySource(prefixed))
                .bind(PREFIX, ChatbotInfluencerRecommendProperties.class).get();
    }
}
