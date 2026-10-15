package com.hapbang.chat.app;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.hapbang.chat.app.dto.ChatMessageHistoryResponse;
import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.app.dto.ChatNotificationResult;
import com.hapbang.chat.app.dto.ChatRoomResponse;
import com.hapbang.chat.app.exception.ChatErrorCode;
import com.hapbang.chat.app.exception.ChatException;
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
        assertThat(message.content()).isEqualTo("""
                호스트님이 출연을 요청했습니다.
                제목: 금요일 밤 합방
                출연료: 50,000 HP
                방송 예정: 2026-11-20 21:00
                수락 기한: 2026-11-13 23:59""");
        assertThat(result.sent().deliverToUserIds()).containsExactlyInAnyOrder(HOST_ID, GUEST_ID);
        assertThat(result.sent().notifyUserIds()).containsExactly(GUEST_ID);

        ChatRoomResponse guestRoom = chatRoomSupporter.getMyRooms(GUEST_ID).getFirst();
        assertThat(guestRoom.chatRoomId()).isEqualTo(message.chatRoomId());
        assertThat(guestRoom.unreadCount()).isEqualTo(1);
        assertThat(guestRoom.lastMessage().notificationKind()).isEqualTo(NotificationKind.APPEARANCE_REQUEST_SENT);
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
        assertThat(message.content()).isEqualTo("""
                게스트님이 출연 모집에 지원했습니다.
                모집글: 주말 게임 합방 모집""");
        assertThat(result.sent().notifyUserIds()).containsExactly(HOST_ID);
    }

    @Test
    void 관리자_기록_조회에도_알림_종류와_출연_건_ID가_담긴다() {
        Long chatRoomId = chatNotificationUseCase.notifyAppearanceApplicationSubmitted(
                new AppearanceApplicationDto(20L, 7L, "주말 게임 합방 모집", HOST_ID, GUEST_ID, SCHEDULED_AT))
                .orElseThrow().sent().message().chatRoomId();

        assertThat(chatRoomSupporter.getHistory(chatRoomId, ADMIN_ID).messages()).singleElement()
                .extracting(ChatMessageHistoryResponse::notificationKind, ChatMessageHistoryResponse::referenceId,
                        ChatMessageHistoryResponse::parentReferenceId)
                .containsExactly(NotificationKind.APPEARANCE_APPLICATION_SUBMITTED, 20L, 7L);
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
