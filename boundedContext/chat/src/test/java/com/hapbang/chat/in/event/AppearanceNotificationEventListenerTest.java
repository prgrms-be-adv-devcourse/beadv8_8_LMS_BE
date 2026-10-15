package com.hapbang.chat.in.event;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.chat.app.ChatUserUseCase;
import com.hapbang.chat.domain.ChatMessage;
import com.hapbang.chat.domain.NotificationKind;
import com.hapbang.chat.fixture.ChatUserSeeder;
import com.hapbang.chat.out.ChatMessageRepository;
import com.hapbang.shared.appearance.dto.AppearanceApplicationDto;
import com.hapbang.shared.appearance.dto.AppearanceRequestDto;
import com.hapbang.shared.appearance.event.AppearanceApplicationSubmittedEvent;
import com.hapbang.shared.appearance.event.AppearanceRequestSentEvent;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 출연 이벤트가 커밋되면 채팅 알림이 남는지 검증한다. 리스너는 커밋 후 비동기로 실행되므로 기다려서 확인한다.
 */
@IntegrationTest
class AppearanceNotificationEventListenerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final LocalDateTime SCHEDULED_AT = LocalDateTime.of(2026, 11, 20, 21, 0);

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ChatUserUseCase chatUserUseCase;

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    @BeforeEach
    void setUp() {
        ChatUserSeeder.seedDefaultUsers(chatUserUseCase);
    }

    @Test
    void 출연_요청_도착_이벤트가_커밋되면_게스트에게_알림이_남는다() {
        publishInTransaction(new AppearanceRequestSentEvent(new AppearanceRequestDto(10L, HOST_ID, GUEST_ID,
                "금요일 밤 합방", 50_000L, SCHEDULED_AT, SCHEDULED_AT.minusDays(7))));

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(chatMessageRepository.findAll())
                .singleElement()
                .satisfies(message -> {
                    assertThat(message.getNotificationKind()).isEqualTo(NotificationKind.APPEARANCE_REQUEST_SENT);
                    assertThat(message.getReferenceId()).isEqualTo(10L);
                    assertThat(message.getSenderUserId()).isEqualTo(HOST_ID);
                }));
    }

    @Test
    void 모집_지원_도착_이벤트가_커밋되면_호스트에게_알림이_남는다() {
        publishInTransaction(new AppearanceApplicationSubmittedEvent(
                new AppearanceApplicationDto(20L, 7L, "주말 게임 합방 모집", HOST_ID, GUEST_ID, SCHEDULED_AT)));

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(chatMessageRepository.findAll())
                .singleElement()
                .extracting(ChatMessage::getNotificationKind)
                .isEqualTo(NotificationKind.APPEARANCE_APPLICATION_SUBMITTED));
    }

    @Test
    void 커밋되지_않은_출연_이벤트는_알림을_남기지_않는다() {
        transactionTemplate.executeWithoutResult(status -> {
            eventPublisher.publishEvent(new AppearanceRequestSentEvent(new AppearanceRequestDto(10L, HOST_ID,
                    GUEST_ID, "금요일 밤 합방", 50_000L, SCHEDULED_AT, SCHEDULED_AT.minusDays(7))));
            status.setRollbackOnly();
        });

        await().during(Duration.ofMillis(500)).atMost(TIMEOUT)
                .untilAsserted(() -> assertThat(chatMessageRepository.count()).isZero());
    }

    private void publishInTransaction(Object event) {
        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(event));
    }
}
