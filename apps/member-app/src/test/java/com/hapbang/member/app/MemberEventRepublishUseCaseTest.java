package com.hapbang.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.IncompleteEventPublications;

import com.hapbang.member.domain.User;
import com.hapbang.shared.appearance.event.AppearanceReviewPublishedEvent;
import com.hapbang.shared.member.event.UserJoinedEvent;
import com.hapbang.shared.member.event.UserLoggedOutEvent;
import com.hapbang.shared.member.event.UserModifiedEvent;

/**
 * 회원 이벤트 Kafka 재발행이 고르는 조건 (#421). 미완료 기록 목록은 Modulith처럼 발행 시각 순으로 조건에 넘긴다.
 * 실제로 Kafka에 다시 나가는지는 MemberEventKafkaRecoveryIntegrationTest가 확인한다
 */
class MemberEventRepublishUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-11-03T03:00:00Z");

    private final IncompleteEventPublications incompleteEventPublications = mock(IncompleteEventPublications.class);
    private final MemberEventRepublishUseCase useCase =
            new MemberEventRepublishUseCase(incompleteEventPublications, Duration.ofMinutes(1), 2);

    @Test
    @DisplayName("대기 시간(1분)이 지난 회원 이벤트만 오래된 것부터 batchSize건 고른다")
    void selectsDueMemberEvents() {
        EventPublication first = publication(new UserJoinedEvent(user().toDto()), 10);
        // 회원 앱이 받기만 하는 출연 리뷰 공개 이벤트는 다시 보내지 않는다
        EventPublication received = publication(new AppearanceReviewPublishedEvent(1L, 1L, 999L, 2L, null, 5,
                LocalDateTime.now()), 9);
        EventPublication second = publication(new UserModifiedEvent(user().toDto()), 8);
        EventPublication overLimit = publication(new UserLoggedOutEvent(3L, NOW), 7);
        EventPublication justPublished = publication(new UserModifiedEvent(user().toDto()), 0);
        List<EventPublication> selected = resubmitted(List.of(first, received, second, overLimit, justPublished));

        assertThat(useCase.republishDue(NOW)).isEqualTo(2);
        assertThat(selected).containsExactly(first, second);
    }

    @Test
    @DisplayName("고를 기록이 없으면 0이다")
    void nothingDue() {
        List<EventPublication> selected = resubmitted(List.of(publication(new UserModifiedEvent(user().toDto()), 0)));

        assertThat(useCase.republishDue(NOW)).isZero();
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

    private static EventPublication publication(Object event, long minutesAgo) {
        EventPublication publication = mock(EventPublication.class);
        when(publication.getEvent()).thenReturn(event);
        when(publication.getPublicationDate()).thenReturn(NOW.minus(Duration.ofMinutes(minutesAgo)));
        return publication;
    }

    private static User user() {
        return User.join("tester@example.com", "tester");
    }
}
