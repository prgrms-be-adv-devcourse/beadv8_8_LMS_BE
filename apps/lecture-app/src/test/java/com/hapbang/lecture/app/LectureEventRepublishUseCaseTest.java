package com.hapbang.lecture.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.IncompleteEventPublications;

import com.hapbang.lecture.config.LectureEventRepublishProperties;
import com.hapbang.shared.customersupport.event.LectureObjectionResolutionRequestedEvent;
import com.hapbang.shared.lecture.event.LectureObjectionResolvedEvent;
import com.hapbang.shared.lecture.event.LectureObjectionReturnCompletedEvent;

/**
 * 강의 이벤트 Kafka 재발행 배치가 고르는 조건 (#278). 미완료 기록 목록은 Modulith처럼 발행 시각 순으로 조건에 넘긴다.
 * 실제 재발행(Kafka로 다시 나감)은 출연 앱의 같은 구조 테스트(AppearanceEventKafkaTest)가 확인한 방식이다.
 */
class LectureEventRepublishUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-11-02T03:00:00Z");

    private final IncompleteEventPublications incompleteEventPublications = mock(IncompleteEventPublications.class);
    private final LectureEventRepublishUseCase useCase = new LectureEventRepublishUseCase(incompleteEventPublications,
            new LectureEventRepublishProperties(Duration.ofMinutes(1), 2), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void 대기_시간이_지난_강의_이벤트만_오래된_것부터_batchSize건_고른다() {
        EventPublication first = publication(resolved(1L, 10L), 10);
        // 강의 앱이 받기만 하는 고객센터 이벤트는 다시 보내지 않는다
        EventPublication received = publication(new LectureObjectionResolutionRequestedEvent("RESOLVE:2", 2L, 20L,
                true, 3L), 9);
        EventPublication second = publication(returnCompleted(1L, 10L), 8);
        EventPublication overLimit = publication(returnCompleted(4L, 40L), 7);
        EventPublication justPublished = publication(returnCompleted(5L, 50L), 0);
        List<EventPublication> selected = resubmitted(List.of(first, received, second, overLimit, justPublished));

        assertThat(useCase.republishDue()).isEqualTo(2);
        assertThat(selected).containsExactly(first, second);
    }

    @Test
    void 고를_기록이_없으면_0이다() {
        List<EventPublication> selected = resubmitted(List.of(
                publication(returnCompleted(5L, 50L), 0)));

        assertThat(useCase.republishDue()).isZero();
        assertThat(selected).isEmpty();
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

    private static LectureObjectionResolvedEvent resolved(Long inquiryId, Long orderItemId) {
        return new LectureObjectionResolvedEvent("RESOLVE:" + inquiryId, inquiryId, orderItemId, true, 2L, 1L, "강의",
                NOW.atOffset(ZoneOffset.UTC));
    }

    private static LectureObjectionReturnCompletedEvent returnCompleted(Long inquiryId, Long orderItemId) {
        return new LectureObjectionReturnCompletedEvent(inquiryId, orderItemId, 2L, 1L, "강의",
                NOW.atOffset(ZoneOffset.UTC));
    }

    private static EventPublication publication(Object event, long minutesAgo) {
        EventPublication publication = mock(EventPublication.class);
        org.mockito.Mockito.when(publication.getEvent()).thenReturn(event);
        org.mockito.Mockito.when(publication.getPublicationDate()).thenReturn(NOW.minus(Duration.ofMinutes(minutesAgo)));
        return publication;
    }
}
