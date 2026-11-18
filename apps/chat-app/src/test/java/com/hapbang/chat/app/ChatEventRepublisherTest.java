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

import com.hapbang.chat.app.port.ChatExternalEvents;
import com.hapbang.shared.chat.event.GroupMemberKickedEvent;
import com.hapbang.shared.chat.event.GroupRoomClosedEvent;
import com.hapbang.shared.member.event.UserMaskedEvent;

/** 채팅 이벤트 Kafka 재발행이 고르는 조건. 미완료 기록은 Modulith처럼 발행 시각 순으로 조건에 넘긴다 */
class ChatEventRepublisherTest {

    private static final Instant NOW = Instant.parse("2026-11-12T03:00:00Z");

    private static final Duration GRACE = ChatExternalEvents.REPUBLISH_PENDING_GRACE;
    private static final int BATCH_SIZE = ChatExternalEvents.REPUBLISH_BATCH_SIZE;

    private final IncompleteEventPublications incompleteEventPublications = mock(IncompleteEventPublications.class);
    private final ChatEventRepublisher republisher = new ChatEventRepublisher(incompleteEventPublications,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void 대기_시간이_지난_채팅_이벤트만_오래된_것부터_batchSize건_고른다() {
        List<EventPublication> incomplete = new ArrayList<>();
        List<EventPublication> due = new ArrayList<>();
        // 채팅이 받기만 하는 다른 컨텍스트의 shared 이벤트는 다시 보내지 않는다
        incomplete.add(publication(new UserMaskedEvent(9L, NOW), GRACE.plusHours(1)));
        EventPublication closed = publication(new GroupRoomClosedEvent(2L, 70L, "모집", 1L, List.of(1L), NOW),
                GRACE.plusMinutes(30));
        incomplete.add(closed);
        due.add(closed);
        // 대기 시간이 지난 기록이 한 번에 보낼 수보다 많으면 오래된 것부터 그 수만 보내고 나머지는 다음 주기로 넘긴다
        for (long i = 0; i < BATCH_SIZE; i++) {
            EventPublication kicked = publication(kicked(100L + i), GRACE.plusSeconds(BATCH_SIZE - i));
            incomplete.add(kicked);
            due.add(kicked);
        }
        // 발행한 지 대기 시간이 안 된 기록은 커밋 직후 보내는 중일 수 있어 고르지 않는다
        incomplete.add(publication(kicked(4L), GRACE.minusSeconds(1)));
        List<EventPublication> selected = resubmitted(incomplete);

        assertThat(republisher.republishDue()).isEqualTo(BATCH_SIZE);
        assertThat(selected).containsExactlyElementsOf(due.subList(0, BATCH_SIZE));
    }

    @Test
    void 발행한_지_대기_시간이_지나지_않은_기록만_있으면_0이다() {
        List<EventPublication> selected = resubmitted(List.of(publication(kicked(1L), GRACE),
                publication(kicked(2L), GRACE.minusSeconds(1))));

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

    private static EventPublication publication(Object event, Duration age) {
        EventPublication publication = mock(EventPublication.class);
        when(publication.getEvent()).thenReturn(event);
        when(publication.getPublicationDate()).thenReturn(NOW.minus(age));
        return publication;
    }
}
