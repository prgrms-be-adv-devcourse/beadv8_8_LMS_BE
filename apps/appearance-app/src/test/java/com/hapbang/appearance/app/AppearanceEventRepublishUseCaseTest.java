package com.hapbang.appearance.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.IncompleteEventPublications;

import com.hapbang.appearance.config.AppearanceBatchProperties;
import com.hapbang.shared.appearance.event.AppearanceCancelRequestedEvent;
import com.hapbang.shared.appearance.event.AppearanceRequestSentEvent;

/**
 * 이벤트 Kafka 재발행 배치의 고르는 조건 (#393). 미완료 기록 목록은 Modulith처럼 발행 시각 순으로 조건에 넘긴다.
 * 실제 재발행(Kafka로 다시 나감)은 {@code AppearanceEventKafkaTest}가 확인한다.
 */
@ExtendWith(OutputCaptureExtension.class)
class AppearanceEventRepublishUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-10-30T03:00:00Z");
    private static final int BATCH_SIZE = 3;

    private final IncompleteEventPublications incompleteEventPublications = mock(IncompleteEventPublications.class);
    private final AppearanceEventRepublishUseCase useCase = new AppearanceEventRepublishUseCase(incompleteEventPublications,
            new AppearanceBatchProperties(BATCH_SIZE, Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofHours(1), 10, 0.2),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("대기 시간(1분)이 지난 내보내는 이벤트만 오래된 것부터 한 번에 batchSize건 고르고, 남은 건수를 batchSize와 함께 로그로 남긴다")
    void selectsOldestUpToBatchSize(CapturedOutput output) {
        EventPublication first = publication(new AppearanceRequestSentEvent(null), 10);
        EventPublication notExternalized = publication(new AppearanceCancelRequestedEvent(null), 9);
        EventPublication second = publication(new AppearanceRequestSentEvent(null), 8);
        EventPublication third = publication(new AppearanceRequestSentEvent(null), 7);
        EventPublication overLimit1 = publication(new AppearanceRequestSentEvent(null), 6);
        EventPublication overLimit2 = publication(new AppearanceRequestSentEvent(null), 5);
        EventPublication justPublished = publication(new AppearanceRequestSentEvent(null), 0);
        List<EventPublication> selected = resubmitted(List.of(first, notExternalized, second, third, overLimit1, overLimit2,
                justPublished));

        int republished = useCase.republishDue();

        assertThat(selected).containsExactly(first, second, third);
        assertThat(republished).isEqualTo(BATCH_SIZE);
        assertThat(output).contains("count=3, waiting=2, batchSize=3");
    }

    @Test
    @DisplayName("고를 기록이 없으면 0을 돌려주고 로그를 남기지 않는다")
    void nothingDue(CapturedOutput output) {
        List<EventPublication> selected = resubmitted(List.of(
                publication(new AppearanceCancelRequestedEvent(null), 10),
                publication(new AppearanceRequestSentEvent(null), 0)));

        assertThat(useCase.republishDue()).isZero();
        assertThat(selected).isEmpty();
        assertThat(output).doesNotContain("재발행 count=");
    }

    /** Modulith처럼 미완료 기록을 발행 시각 순으로 조건에 넘기고, 조건을 통과한 기록을 모은다 */
    @SuppressWarnings("unchecked")
    private List<EventPublication> resubmitted(List<EventPublication> incomplete) {
        List<EventPublication> selected = new ArrayList<>();
        doAnswer(invocation -> {
            Predicate<EventPublication> filter = invocation.getArgument(0);
            incomplete.stream().filter(filter).forEach(selected::add);
            return null;
        }).when(incompleteEventPublications).resubmitIncompletePublications(any(Predicate.class));
        return selected;
    }

    /** 지금보다 minutesAgo분 전에 발행한 미완료 기록 (0이면 30초 전, 대기 시간 안) */
    private static EventPublication publication(Object event, int minutesAgo) {
        EventPublication publication = mock(EventPublication.class);
        when(publication.getEvent()).thenReturn(event);
        when(publication.getPublicationDate())
                .thenReturn(minutesAgo == 0 ? NOW.minusSeconds(30) : NOW.minus(Duration.ofMinutes(minutesAgo)));
        return publication;
    }
}
