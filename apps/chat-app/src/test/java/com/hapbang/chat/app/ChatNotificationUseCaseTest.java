package com.hapbang.chat.app;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.hapbang.chat.app.dto.ChatMessageHistoryResponse;
import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.app.dto.ChatNotificationResult;
import com.hapbang.chat.app.dto.ChatRoomResponse;
import com.hapbang.chat.app.exception.ChatErrorCode;
import com.hapbang.chat.app.exception.ChatException;
import com.hapbang.chat.domain.AppearanceApplicationSubmittedData;
import com.hapbang.chat.domain.AppearanceRequestSentData;
import com.hapbang.chat.domain.ChatMessage;
import com.hapbang.chat.domain.ChatMessageType;
import com.hapbang.chat.domain.NotificationKind;
import com.hapbang.chat.fixture.ChatUserSeeder;
import com.hapbang.chat.out.ChatMessageRepository;
import com.hapbang.shared.appearance.dto.AppearanceApplicationDto;
import com.hapbang.shared.appearance.dto.AppearanceRequestDto;
import com.hapbang.testsupport.IntegrationTest;

@IntegrationTest
class ChatNotificationUseCaseTest {

    private static final LocalDateTime SCHEDULED_AT = LocalDateTime.of(2026, 11, 20, 21, 0);
    private static final LocalDateTime ACCEPT_DEADLINE = LocalDateTime.of(2026, 11, 13, 23, 59);

    /** 출연 요청 도착 알림의 값. 호스트 닉네임은 채팅 회원 복제본, 나머지는 이벤트 값이고 일시는 KST 문자열이다 */
    private static final AppearanceRequestSentData REQUEST_SENT_DATA = new AppearanceRequestSentData(1, "호스트",
            "금요일 밤 합방", 50_000L, "2026-11-20T21:00:00", "2026-11-13T23:59:00");

    @Autowired
    private ChatNotificationUseCase chatNotificationUseCase;

    @Autowired
    private ChatRoomUseCase chatRoomUseCase;

    @Autowired
    private ChatRoomSupporter chatRoomSupporter;

    @Autowired
    private ChatMessageSupporter chatMessageSupporter;

    @Autowired
    private ChatUserUseCase chatUserUseCase;

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        ChatUserSeeder.seedDefaultUsers(chatUserUseCase);
    }

    @Test
    void 출연_요청이_도착하면_호스트와_게스트의_방을_만들고_게스트에게_알림을_남긴다() {
        ChatNotificationResult result = chatNotificationUseCase.notifyAppearanceRequestSent(request(10L, GUEST_ID))
                .orElseThrow();

        ChatMessageResponse message = result.sent().message();
        assertThat(message.messageType()).isEqualTo(ChatMessageType.NOTIFICATION);
        assertThat(message.notificationKind()).isEqualTo(NotificationKind.APPEARANCE_REQUEST_SENT);
        assertThat(message.referenceId()).isEqualTo(10L);
        assertThat(message.parentReferenceId()).isNull();
        assertThat(message.senderUserId()).isEqualTo(HOST_ID);
        assertThat(message.appearanceRequestSent()).isEqualTo(REQUEST_SENT_DATA);
        assertThat(message.appearanceApplicationSubmitted()).isNull();
        assertThat(message.content()).isEmpty();
        assertThat(result.sent().deliverToUserIds()).containsExactlyInAnyOrder(HOST_ID, GUEST_ID);
        assertThat(result.sent().notifyUserIds()).containsExactly(GUEST_ID);

        ChatRoomResponse guestRoom = chatRoomSupporter.getMyRooms(GUEST_ID).getFirst();
        assertThat(guestRoom.chatRoomId()).isEqualTo(message.chatRoomId());
        assertThat(guestRoom.unreadCount()).isEqualTo(1);
        assertThat(guestRoom.lastMessage().notificationKind()).isEqualTo(NotificationKind.APPEARANCE_REQUEST_SENT);
        assertThat(guestRoom.lastMessage().appearanceRequestSent()).isEqualTo(REQUEST_SENT_DATA);
    }

    @Test
    void 알림_값은_jsonb로_저장되고_다시_읽어도_같다() {
        Long messageId = chatNotificationUseCase.notifyAppearanceRequestSent(request(10L, GUEST_ID)).orElseThrow()
                .sent().message().messageId();

        ChatMessage stored = chatMessageRepository.findById(messageId).orElseThrow();
        assertThat(stored.getContent()).isEmpty();
        assertThat(stored.readAppearanceRequestSent()).isEqualTo(REQUEST_SENT_DATA);
        assertThat(jdbcTemplate.queryForMap("""
                select pg_typeof(notification_data)::text as column_type,
                       jsonb_typeof(notification_data -> 'v') as v_type,
                       jsonb_typeof(notification_data -> 'fee') as fee_type,
                       notification_data ->> 'fee' as fee,
                       notification_data ->> 'scheduledAt' as scheduled_at
                from chats.chat_message where id = ?""", messageId))
                .containsEntry("column_type", "jsonb")
                .containsEntry("v_type", "number")
                .containsEntry("fee_type", "number")
                .containsEntry("fee", "50000")
                .containsEntry("scheduled_at", "2026-11-20T21:00:00");
    }

    @Test
    void 일반_메시지의_알림_값은_null로_저장된다() {
        Long chatRoomId = chatRoomUseCase.createOrGet(HOST_ID, GUEST_ID).room().chatRoomId();
        chatRoomUseCase.leave(chatRoomId, GUEST_ID, null);

        assertThat(chatMessageRepository.findAll()).singleElement()
                .extracting(ChatMessage::getNotificationDataJson).isNull();
    }

    @Test
    void 저장한_알림_값을_읽을_수_없어도_이력_조회는_되고_값은_null이다() {
        ChatMessageResponse sent = chatNotificationUseCase.notifyAppearanceRequestSent(request(10L, GUEST_ID))
                .orElseThrow().sent().message();
        jdbcTemplate.update("update chats.chat_message set notification_data = '{\"fee\":\"x\"}'::jsonb where id = ?",
                sent.messageId());

        assertThat(chatMessageSupporter.getMessages(sent.chatRoomId(), GUEST_ID, null, 30).messages()).singleElement()
                .satisfies(message -> {
                    assertThat(message.messageId()).isEqualTo(sent.messageId());
                    assertThat(message.appearanceRequestSent()).isNull();
                    assertThat(message.appearanceApplicationSubmitted()).isNull();
                });
    }

    @Test
    void 모집_지원이_들어오면_호스트에게_알림을_남긴다() {
        ChatNotificationResult result = chatNotificationUseCase.notifyAppearanceApplicationSubmitted(
                new AppearanceApplicationDto(20L, 7L, "주말 게임 합방 모집", HOST_ID, GUEST_ID, SCHEDULED_AT))
                .orElseThrow();

        ChatMessageResponse message = result.sent().message();
        assertThat(message.notificationKind()).isEqualTo(NotificationKind.APPEARANCE_APPLICATION_SUBMITTED);
        assertThat(message.referenceId()).isEqualTo(20L);
        assertThat(message.parentReferenceId()).isEqualTo(7L);
        assertThat(message.senderUserId()).isEqualTo(GUEST_ID);
        assertThat(message.appearanceApplicationSubmitted())
                .isEqualTo(new AppearanceApplicationSubmittedData(1, "게스트", "주말 게임 합방 모집"));
        assertThat(message.appearanceRequestSent()).isNull();
        assertThat(message.content()).isEmpty();
        assertThat(result.sent().notifyUserIds()).containsExactly(HOST_ID);
    }

    @Test
    void 관리자_기록_조회에도_알림_종류와_출연_건_ID와_값이_담긴다() {
        Long chatRoomId = chatNotificationUseCase.notifyAppearanceApplicationSubmitted(
                new AppearanceApplicationDto(20L, 7L, "주말 게임 합방 모집", HOST_ID, GUEST_ID, SCHEDULED_AT))
                .orElseThrow().sent().message().chatRoomId();

        assertThat(chatRoomSupporter.getHistory(chatRoomId, ADMIN_ID, null, 100).messages()).singleElement()
                .extracting(ChatMessageHistoryResponse::notificationKind, ChatMessageHistoryResponse::referenceId,
                        ChatMessageHistoryResponse::parentReferenceId, ChatMessageHistoryResponse::content)
                .containsExactly(NotificationKind.APPEARANCE_APPLICATION_SUBMITTED, 20L, 7L, "");
        assertThat(chatRoomSupporter.getHistory(chatRoomId, ADMIN_ID, null, 100).messages().getFirst()
                .appearanceApplicationSubmitted())
                .isEqualTo(new AppearanceApplicationSubmittedData(1, "게스트", "주말 게임 합방 모집"));
    }

    @Test
    void 이미_대화하던_방이_있으면_그_방에_남긴다() {
        Long chatRoomId = chatRoomUseCase.createOrGet(HOST_ID, GUEST_ID).room().chatRoomId();

        ChatNotificationResult result = chatNotificationUseCase.notifyAppearanceRequestSent(request(10L, GUEST_ID))
                .orElseThrow();

        assertThat(result.sent().message().chatRoomId()).isEqualTo(chatRoomId);
        assertThat(result.rejoinNotices()).isEmpty();
    }

    @Test
    void 같은_이벤트가_다시_와도_알림은_하나만_남긴다() {
        chatNotificationUseCase.notifyAppearanceRequestSent(request(10L, GUEST_ID));

        assertThat(chatNotificationUseCase.notifyAppearanceRequestSent(request(10L, GUEST_ID))).isEmpty();
        assertThat(chatMessageRepository.count()).isEqualTo(1);
    }

    @Test
    void 받는_사람이_방을_나갔으면_재입장시키고_남아_있던_사람에게_재입장_안내를_보낸다() {
        Long chatRoomId = chatRoomUseCase.createOrGet(HOST_ID, GUEST_ID).room().chatRoomId();
        chatRoomUseCase.leave(chatRoomId, GUEST_ID, null);

        ChatNotificationResult result = chatNotificationUseCase.notifyAppearanceRequestSent(request(10L, GUEST_ID))
                .orElseThrow();

        assertThat(result.sent().deliverToUserIds()).containsExactlyInAnyOrder(HOST_ID, GUEST_ID);
        assertThat(result.rejoinNotices()).extracting(ChatMessageResponse::content)
                .containsExactly("게스트님이 다시 들어왔습니다.");
        assertThat(result.remainingUserIds()).containsExactly(HOST_ID);
        assertThat(chatMessageSupporter.getMessages(chatRoomId, GUEST_ID, null, 30).messages())
                .extracting(ChatMessageResponse::messageType).containsExactly(ChatMessageType.NOTIFICATION);
    }

    @Test
    void 받는_사람이_채팅할_수_없는_회원이면_알림을_남기지_않는다() {
        assertThat(chatNotificationUseCase.notifyAppearanceRequestSent(request(10L, USER_ID))).isEmpty();
        assertThat(chatMessageRepository.count()).isZero();
        assertThat(chatRoomSupporter.getMyRooms(HOST_ID)).isEmpty();
    }

    @Test
    void 채팅_회원_복제본이_아직_없으면_실패해서_재처리를_기다린다() {
        assertThatThrownBy(() -> chatNotificationUseCase.notifyAppearanceRequestSent(request(10L, UNKNOWN_ID)))
                .isInstanceOf(ChatException.class)
                .extracting("errorCode").isEqualTo(ChatErrorCode.CHAT_USER_NOT_FOUND);
    }

    private static AppearanceRequestDto request(Long requestId, Long guestId) {
        return new AppearanceRequestDto(requestId, HOST_ID, guestId, "금요일 밤 합방", 50_000L, SCHEDULED_AT,
                ACCEPT_DEADLINE);
    }
}
