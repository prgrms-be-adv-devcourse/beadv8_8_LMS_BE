package com.hapbang.chat.app;

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

import com.hapbang.shared.chat.event.GroupMemberKickedEvent;
import com.hapbang.shared.chat.event.GroupRoomClosedEvent;
import com.hapbang.shared.member.event.UserMaskedEvent;

/** 채팅 이벤트 Kafka 재발행이 고르는 조건. 미완료 기록은 Modulith처럼 발행 시각 순으로 조건에 넘긴다 */
class ChatEventRepublisherTest {

    private static final Instant NOW = Instant.parse("2026-11-12T03:00:00Z");

    private final IncompleteEventPublications incompleteEventPublications = mock(IncompleteEventPublications.class);
    private final ChatEventRepublisher republisher = new ChatEventRepublisher(incompleteEventPublications,
            Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(1), 2);

    @Test
    void 대기_시간이_지난_채팅_이벤트만_오래된_것부터_batchSize건_고른다() {
        EventPublication first = publication(kicked(1L), 10);
        // 채팅이 받기만 하는 다른 컨텍스트의 shared 이벤트는 다시 보내지 않는다
        EventPublication received = publication(new UserMaskedEvent(9L, NOW), 9);
        EventPublication second = publication(new GroupRoomClosedEvent(2L, 70L, "모집", 1L, List.of(1L), NOW), 8);
        EventPublication overLimit = publication(kicked(3L), 7);
        // 발행한 지 1분이 안 된 기록은 커밋 직후 보내는 중일 수 있어 고르지 않는다
        EventPublication justPublished = publication(kicked(4L), 0);
        List<EventPublication> selected = resubmitted(List.of(first, received, second, overLimit, justPublished));

        assertThat(republisher.republishDue()).isEqualTo(2);
        assertThat(selected).containsExactly(first, second);
    }

    @Test
    void 고를_기록이_없으면_0이다() {
        List<EventPublication> selected = resubmitted(List.of(publication(kicked(1L), 0)));

        assertThat(republisher.republishDue()).isZero();
        assertThat(selected).isEmpty();
    }

    @Test
    void 운영_값은_매분_발행_후_1분_100건이다() {
        assertThat(ChatExternalEvents.REPUBLISH_CRON).isEqualTo("5 * * * * *");
        assertThat(ChatExternalEvents.REPUBLISH_PENDING_GRACE).isEqualTo(Duration.ofMinutes(1));
        assertThat(ChatExternalEvents.REPUBLISH_BATCH_SIZE).isEqualTo(100);
    }

    private static GroupMemberKickedEvent kicked(Long chatRoomId) {
        return new GroupMemberKickedEvent(chatRoomId, 70L, "모집", 1L, 2L, 1, NOW);
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
