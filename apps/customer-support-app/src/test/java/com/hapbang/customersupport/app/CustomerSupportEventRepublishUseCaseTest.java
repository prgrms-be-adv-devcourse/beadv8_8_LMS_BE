package com.hapbang.customersupport.app;

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

import org.junit.jupiter.api.Test;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.IncompleteEventPublications;

import com.hapbang.customersupport.fixture.LectureObjectionResults;
import com.hapbang.shared.customersupport.event.LectureObjectionResolutionRequestedEvent;
import com.hapbang.shared.customersupport.event.LectureObjectionWithdrawalRequestedEvent;

/** 고객센터 이벤트 Kafka 재발행 배치가 고르는 조건 (#279). 미완료 기록은 Modulith처럼 발행 시각 순으로 조건에 넘긴다 */
class CustomerSupportEventRepublishUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-11-02T03:00:00Z");

    private final IncompleteEventPublications incompleteEventPublications = mock(IncompleteEventPublications.class);
    private final CustomerSupportEventRepublishUseCase useCase = new CustomerSupportEventRepublishUseCase(
            incompleteEventPublications, new CustomerSupportBatchProperties(Duration.ofMinutes(1), 2),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void 대기_시간이_지난_고객센터_이벤트만_오래된_것부터_batchSize건_고른다() {
        EventPublication first = publication(new LectureObjectionResolutionRequestedEvent("RESOLVE:1", 1L, 10L, true,
                9L), 10);
        // 고객센터가 받기만 하는 강의 이벤트는 다시 보내지 않는다
        EventPublication received = publication(LectureObjectionResults.returnCompleted(1L, 10L), 9);
        EventPublication second = publication(new LectureObjectionWithdrawalRequestedEvent("WITHDRAW:2", 2L, 20L, 1L), 8);
        EventPublication overLimit = publication(new LectureObjectionWithdrawalRequestedEvent("WITHDRAW:3", 3L, 30L, 1L),
                7);
        EventPublication justPublished = publication(new LectureObjectionWithdrawalRequestedEvent("WITHDRAW:4", 4L,
                40L, 1L), 0);
        List<EventPublication> selected = resubmitted(List.of(first, received, second, overLimit, justPublished));

        assertThat(useCase.republishDue()).isEqualTo(2);
        assertThat(selected).containsExactly(first, second);
    }

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

    private static EventPublication publication(Object event, long minutesAgo) {
        EventPublication publication = mock(EventPublication.class);
        when(publication.getEvent()).thenReturn(event);
        when(publication.getPublicationDate()).thenReturn(NOW.minus(Duration.ofMinutes(minutesAgo)));
        return publication;
    }
}
