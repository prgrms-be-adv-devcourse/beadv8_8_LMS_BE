package com.hapbang.auxiliary.chatbot.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.hapbang.auxiliary.chatbot.app.LectureIndexReconciler;
import com.hapbang.auxiliary.chatbot.config.ChatbotLectureRecommendProperties;

/** 강의 추천 색인 정합 점검의 연속 실패 알림 (#835). 설정은 운영과 같이 기본값으로 묶는다(인플루언서 점검과 같은 3번) */
@ExtendWith(OutputCaptureExtension.class)
class LectureIndexCheckBatchTest {

    private static final String ALERT = "[CHATBOT_LECTURE_INDEX_CHECK_FAILED]";
    private static final String PREFIX = "hapbang.chatbot.recommend.lecture";

    private final LectureIndexReconciler reconciler = mock(LectureIndexReconciler.class);
    private final ChatbotLectureRecommendProperties properties = new Binder(new MapConfigurationPropertySource(Map.of()))
            .bindOrCreate(PREFIX, ChatbotLectureRecommendProperties.class);
    private final LectureIndexCheckBatch batch = new LectureIndexCheckBatch(reconciler, properties);

    @Test
    @DisplayName("기본 3번 연속 실패하면(기동 점검 포함) ERROR 알림, 그 전은 WARN. 성공하면 다시 0부터 센다. 예외는 밖으로 나가지 않는다")
    void alertsAfterConsecutiveFailures(CapturedOutput output) {
        doReturn(true).when(reconciler).available();
        doThrow(new IllegalStateException("ES 장애")).when(reconciler).check();

        batch.run("startup");
        batch.run("scheduled");
        assertThat(output).doesNotContain(ALERT)
                .contains("강의 추천 색인 점검이 실패했다. 다음 점검에서 다시 한다 trigger=scheduled, failures=2");

        batch.run("scheduled");
        assertThat(output).contains(ALERT + " 강의 추천 색인 정합 점검이 연속으로 실패했다 trigger=scheduled, failures=3");

        doReturn(0).when(reconciler).check();
        batch.run("scheduled");
        doThrow(new IllegalStateException("ES 장애")).when(reconciler).check();
        int alertsBefore = count(output.getAll(), ALERT);
        batch.run("scheduled");
        assertThat(count(output.getAll(), ALERT)).isEqualTo(alertsBefore);
        assertThat(output.getAll()).contains("trigger=scheduled, failures=1");
    }

    private static int count(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) {
            count++;
        }
        return count;
    }
}
