package com.hapbang.chat.app;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.hapbang.chat.app.dto.ChatMessagePageResponse;
import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.app.dto.SentChatMessage;
import com.hapbang.chat.domain.ChatErrorCode;
import com.hapbang.chat.domain.ChatException;
import com.hapbang.chat.domain.ChatMessageType;
import com.hapbang.chat.domain.UserRole;
import com.hapbang.chat.fixture.ChatUserSeeder;
import com.hapbang.testsupport.IntegrationTest;

@IntegrationTest
class ChatMessageServiceTest {

    @Autowired
    private ChatMessageService chatMessageService;

    @Autowired
    private ChatRoomService chatRoomService;

    @Autowired
    private ChatUserService chatUserService;

    private Long chatRoomId;

    @BeforeEach
    void setUp() {
        ChatUserSeeder.seedDefaultUsers(chatUserService);
        chatRoomId = chatRoomService.createOrGet(HOST_ID, GUEST_ID).room().chatRoomId();
    }

    @Test
    void 참여자가_보내면_두_사람_모두에게_전달되고_상대만_알림을_받는다() {
        SentChatMessage sent = chatMessageService.send(chatRoomId, HOST_ID, "다음 주 금요일 어떠세요?");

        assertThat(sent.message().messageId()).isNotNull();
        assertThat(sent.message().roomSequence()).isEqualTo(1);
        assertThat(sent.message().messageType()).isEqualTo(ChatMessageType.TEXT);
        assertThat(sent.deliverToUserIds()).containsExactlyInAnyOrder(HOST_ID, GUEST_ID);
        assertThat(sent.notifyUserIds()).containsExactly(GUEST_ID);
    }

    @Test
    void 상대가_나간_동안에는_메시지를_보낼_수_없다() {
        chatRoomService.leave(chatRoomId, GUEST_ID, null);

        assertThatThrownBy(() -> chatMessageService.send(chatRoomId, HOST_ID, "아직 계세요?"))
                .isInstanceOf(ChatException.class)
                .extracting("errorCode").isEqualTo(ChatErrorCode.CHAT_PARTNER_LEFT);
    }

    @Test
    void 다시_시작해_상대가_재입장하면_다시_보낼_수_있다() {
        chatRoomService.leave(chatRoomId, GUEST_ID, null);
        chatRoomService.createOrGet(HOST_ID, GUEST_ID);

        SentChatMessage sent = chatMessageService.send(chatRoomId, HOST_ID, "다시 이야기해요");

        assertThat(sent.notifyUserIds()).containsExactly(GUEST_ID);
    }

    @Test
    void 참여자가_아니면_메시지를_보낼_수_없다() {
        assertThatThrownBy(() -> chatMessageService.send(chatRoomId, OTHER_INFLUENCER_ID, "끼어들기"))
                .isInstanceOf(ChatException.class)
                .extracting("errorCode").isEqualTo(ChatErrorCode.NOT_CHAT_ROOM_MEMBER);
    }

    @Test
    void 나간_방에는_메시지를_보낼_수_없다() {
        chatRoomService.leave(chatRoomId, HOST_ID, null);

        assertThatThrownBy(() -> chatMessageService.send(chatRoomId, HOST_ID, "다시 왔어요"))
                .isInstanceOf(ChatException.class)
                .extracting("errorCode").isEqualTo(ChatErrorCode.NOT_CHAT_ROOM_MEMBER);
    }

    @Test
    void 종료된_방에는_메시지를_보낼_수_없다() {
        chatRoomService.leave(chatRoomId, GUEST_ID, null);
        chatRoomService.leave(chatRoomId, HOST_ID, null);

        assertThatThrownBy(() -> chatMessageService.send(chatRoomId, HOST_ID, "안녕"))
                .isInstanceOf(ChatException.class)
                .extracting("errorCode").isEqualTo(ChatErrorCode.CHAT_ROOM_NOT_FOUND);
    }

    @Test
    void 일반_유저로_강등된_참여자는_보내지도_조회하지도_못한다() {
        chatUserService.sync(HOST_ID, "호스트", UserRole.USER);

        assertThatThrownBy(() -> chatMessageService.send(chatRoomId, HOST_ID, "안녕하세요"))
                .isInstanceOf(ChatException.class)
                .extracting("errorCode").isEqualTo(ChatErrorCode.CHAT_NOT_ALLOWED);
        assertThatThrownBy(() -> chatMessageService.getMessages(chatRoomId, HOST_ID, null, 30))
                .isInstanceOf(ChatException.class)
                .extracting("errorCode").isEqualTo(ChatErrorCode.CHAT_NOT_ALLOWED);
    }

    @Test
    void 빈_메시지는_보낼_수_없다() {
        assertThatThrownBy(() -> chatMessageService.send(chatRoomId, HOST_ID, "  "))
                .isInstanceOf(ChatException.class)
                .extracting("errorCode").isEqualTo(ChatErrorCode.EMPTY_MESSAGE);
    }

    @Test
    void 메시지를_최신순으로_순번_커서_페이지_조회한다() {
        for (int i = 1; i <= 5; i++) {
            chatMessageService.send(chatRoomId, HOST_ID, "메시지" + i);
        }

        ChatMessagePageResponse first = chatMessageService.getMessages(chatRoomId, GUEST_ID, null, 3);
        ChatMessagePageResponse second = chatMessageService.getMessages(chatRoomId, GUEST_ID, first.nextCursor(), 3);

        assertThat(first.messages()).extracting(ChatMessageResponse::content)
                .containsExactly("메시지5", "메시지4", "메시지3");
        assertThat(first.nextCursor()).isEqualTo(3);
        assertThat(second.messages()).extracting(ChatMessageResponse::content)
                .containsExactly("메시지2", "메시지1");
        assertThat(second.nextCursor()).isNull();
    }

    // 수용 테스트 4: 재입장 뒤 새 메시지는 양쪽에 보이고, 오래된 cursor를 보내도 이전 기록은 나오지 않는다.
    @Test
    void 재입장한_사람은_오래된_커서로도_재입장_이전_메시지를_볼_수_없다() {
        chatMessageService.send(chatRoomId, HOST_ID, "나가기 전 메시지");
        chatMessageService.send(chatRoomId, GUEST_ID, "저도 나가기 전");
        chatRoomService.leave(chatRoomId, GUEST_ID, null);
        chatRoomService.createOrGet(GUEST_ID, HOST_ID);
        chatMessageService.send(chatRoomId, GUEST_ID, "다시 왔어요");

        assertThat(chatMessageService.getMessages(chatRoomId, GUEST_ID, null, 30).messages())
                .extracting(ChatMessageResponse::content)
                .containsExactly("다시 왔어요");
        assertThat(chatMessageService.getMessages(chatRoomId, GUEST_ID, 3L, 30).messages()).isEmpty();
        assertThat(chatMessageService.getMessages(chatRoomId, HOST_ID, null, 30).messages())
                .extracting(ChatMessageResponse::content)
                .containsExactly("다시 왔어요", "게스트님이 나갔습니다.", "저도 나가기 전", "나가기 전 메시지");
    }

    @Test
    void 참여자가_아니면_메시지를_조회할_수_없다() {
        assertThatThrownBy(() -> chatMessageService.getMessages(chatRoomId, OTHER_INFLUENCER_ID, null, 30))
                .isInstanceOf(ChatException.class)
                .extracting("errorCode").isEqualTo(ChatErrorCode.NOT_CHAT_ROOM_MEMBER);
    }
}
