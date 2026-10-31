package com.hapbang.chat.domain;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ChatMessageTest {

    @Test
    void 텍스트_메시지는_방_순번을_받는다() {
        ChatRoom room = directRoom();

        ChatMessage first = ChatMessage.text(room, HOST_ID, "안녕하세요", null, NOW);
        ChatMessage second = ChatMessage.text(room, GUEST_ID, "반갑습니다", null, NOW);

        assertThat(first.getMessageType()).isEqualTo(ChatMessageType.TEXT);
        assertThat(first.getRoomSequence()).isEqualTo(1);
        assertThat(second.getRoomSequence()).isEqualTo(2);
        assertThat(first.isDeleted()).isFalse();
    }

    @Test
    void 퇴장_안내_시스템_메시지를_만든다() {
        ChatMessage message = ChatMessage.leftNotice(directRoom(), GUEST_ID, "게스트", NOW);

        assertThat(message.getMessageType()).isEqualTo(ChatMessageType.SYSTEM);
        assertThat(message.getContent()).isEqualTo("게스트님이 나갔습니다.");
        assertThat(message.getSenderUserId()).isEqualTo(GUEST_ID);
        assertThat(message.getRoomSequence()).isEqualTo(1);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n"})
    void 내용이_비어_있으면_보낼_수_없고_순번도_쓰지_않는다(String content) {
        ChatRoom room = directRoom();

        assertThatThrownBy(() -> ChatMessage.text(room, HOST_ID, content, null, NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("메시지 내용이 비어 있습니다.");
        assertThat(room.getLastSequence()).isZero();
    }

    @Test
    void 재입장_안내는_재입장한_회원이_보낸_시스템_메시지다() {
        ChatMessage notice = ChatMessage.rejoinNotice(directRoom(), GUEST_ID, "게스트", NOW);

        assertThat(notice.getMessageType()).isEqualTo(ChatMessageType.SYSTEM);
        assertThat(notice.getSenderUserId()).isEqualTo(GUEST_ID);
        assertThat(notice.getContent()).isEqualTo("게스트님이 다시 들어왔습니다.");
        assertThat(notice.getRoomSequence()).isEqualTo(1);
    }

    @Test
    void 알림_메시지는_종류와_출연_건_ID와_값을_가지고_문구는_만들지_않는다() {
        AppearanceApplicationSubmittedData data = new AppearanceApplicationSubmittedData(1, "게스트", "주말 게임 합방 모집");

        ChatMessage notification = ChatMessage.appearanceApplicationSubmitted(directRoom(), GUEST_ID, 20L, 7L, data,
                NOW);

        assertThat(notification.getMessageType()).isEqualTo(ChatMessageType.NOTIFICATION);
        assertThat(notification.getNotificationKind()).isEqualTo(NotificationKind.APPEARANCE_APPLICATION_SUBMITTED);
        assertThat(notification.getReferenceId()).isEqualTo(20L);
        assertThat(notification.getParentReferenceId()).isEqualTo(7L);
        assertThat(notification.getSenderUserId()).isEqualTo(GUEST_ID);
        assertThat(notification.readAppearanceApplicationSubmitted()).isEqualTo(data);
        assertThat(notification.readAppearanceRequestSent()).isNull();
        assertThat(notification.getContent()).isEqualTo(ChatMessage.NOTIFICATION_CONTENT).isEmpty();
    }

    @Test
    void 출연_요청_알림은_요청_record로만_다시_읽는다() {
        AppearanceRequestSentData data = new AppearanceRequestSentData(1, "호스트", "금요일 밤 합방", 50_000L,
                "2026-11-20T21:00:00", "2026-11-13T23:59:00");

        ChatMessage notification = ChatMessage.appearanceRequestSent(directRoom(), HOST_ID, 10L, data, NOW);

        assertThat(notification.getNotificationKind()).isEqualTo(NotificationKind.APPEARANCE_REQUEST_SENT);
        assertThat(notification.getParentReferenceId()).isNull();
        assertThat(notification.readAppearanceRequestSent()).isEqualTo(data);
        assertThat(notification.readAppearanceApplicationSubmitted()).isNull();
    }

    @Test
    void 일반_메시지에는_알림_값이_없다() {
        ChatMessage text = ChatMessage.text(directRoom(), HOST_ID, "안녕하세요", null, NOW);
        assertThat(text.readAppearanceRequestSent()).isNull();
        assertThat(text.readAppearanceApplicationSubmitted()).isNull();
        assertThat(ChatMessage.leftNotice(directRoom(), GUEST_ID, "게스트", NOW).getNotificationDataJson()).isNull();
    }

    @Test
    void 출연_건_ID나_값이_없으면_만들_수_없다() {
        AppearanceRequestSentData data = new AppearanceRequestSentData(1, "호스트", "금요일 밤 합방", 50_000L,
                "2026-11-20T21:00:00", "2026-11-13T23:59:00");

        assertThatIllegalStateException().isThrownBy(() -> ChatMessage.appearanceRequestSent(directRoom(), HOST_ID,
                null, data, NOW));
        assertThatIllegalStateException().isThrownBy(() -> ChatMessage.appearanceRequestSent(directRoom(), HOST_ID,
                10L, null, NOW));
        assertThatIllegalStateException().isThrownBy(() -> ChatMessage.appearanceApplicationSubmitted(directRoom(),
                GUEST_ID, 20L, 7L, null, NOW));
    }

    @Test
    void 본문은_2000자까지_보낼_수_있다() {
        String content = "가".repeat(ChatMessage.MAX_CONTENT_LENGTH);

        assertThat(ChatMessage.text(directRoom(), HOST_ID, content, null, NOW).getContent()).hasSize(2000);
        assertThatIllegalStateException()
                .isThrownBy(() -> ChatMessage.text(directRoom(), HOST_ID, content + "가", null, NOW));
    }
}
