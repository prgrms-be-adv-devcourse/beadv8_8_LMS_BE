package com.hapbang.chat.app;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.hapbang.chat.app.dto.ChatMessageHistoryResponse;
import com.hapbang.chat.app.dto.ChatRoomCreateResult;
import com.hapbang.chat.app.dto.ChatRoomHistoryResponse;
import com.hapbang.chat.app.dto.ChatRoomLogResponse;
import com.hapbang.chat.app.dto.ChatRoomMemberResponse;
import com.hapbang.chat.domain.ChatErrorCode;
import com.hapbang.chat.domain.ChatException;
import com.hapbang.chat.domain.ChatRoomEventType;
import com.hapbang.chat.domain.ChatRoomLog;
import com.hapbang.chat.domain.ChatRoomStatus;
import com.hapbang.chat.fixture.ChatUserSeeder;
import com.hapbang.chat.out.ChatRoomLogRepository;
import com.hapbang.chat.out.ChatRoomRepository;
import com.hapbang.testsupport.IntegrationTest;

@IntegrationTest
class ChatRoomServiceTest {

    @Autowired
    private ChatRoomService chatRoomService;

    @Autowired
    private ChatMessageService chatMessageService;

    @Autowired
    private ChatUserService chatUserService;

    @Autowired
    private ChatRoomRepository chatRoomRepository;

    @Autowired
    private ChatRoomLogRepository chatRoomLogRepository;

    @BeforeEach
    void setUp() {
        ChatUserSeeder.seedDefaultUsers(chatUserService);
    }

    @Test
    void 인플루언서끼리_채팅방을_만들고_생성과_입장을_기록한다() {
        ChatRoomCreateResult result = chatRoomService.createOrGet(HOST_ID, GUEST_ID);

        assertThat(result.created()).isTrue();
        assertThat(result.room().status()).isEqualTo(ChatRoomStatus.ACTIVE);
        assertThat(result.room().members())
                .extracting(ChatRoomMemberResponse::userId, ChatRoomMemberResponse::nickname)
                .containsExactly(tuple(HOST_ID, "호스트"), tuple(GUEST_ID, "게스트"));
        assertThat(eventsOf(result.room().chatRoomId()))
                .containsExactly(ChatRoomEventType.ROOM_CREATED, ChatRoomEventType.MEMBER_JOINED,
                        ChatRoomEventType.MEMBER_JOINED);
    }

    @Test
    void 두_사람의_방이_있으면_재사용한다() {
        Long chatRoomId = createRoom();

        ChatRoomCreateResult result = chatRoomService.createOrGet(GUEST_ID, HOST_ID);

        assertThat(result.created()).isFalse();
        assertThat(result.room().chatRoomId()).isEqualTo(chatRoomId);
        assertThat(chatRoomRepository.count()).isEqualTo(1);
    }

    @Test
    void 다른_사람과는_새_방을_만든다() {
        Long first = createRoom();

        Long second = chatRoomService.createOrGet(HOST_ID, OTHER_INFLUENCER_ID).room().chatRoomId();

        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void 관리자도_채팅방을_만들_수_있다() {
        assertThat(chatRoomService.createOrGet(ADMIN_ID, HOST_ID).created()).isTrue();
    }

    @Test
    void 일반_유저는_채팅방을_만들_수_없다() {
        assertThatThrownBy(() -> chatRoomService.createOrGet(USER_ID, HOST_ID))
                .isInstanceOf(ChatException.class)
                .extracting("errorCode").isEqualTo(ChatErrorCode.CHAT_NOT_ALLOWED);
    }

    @Test
    void 일반_유저에게는_채팅을_걸_수_없다() {
        assertThatThrownBy(() -> chatRoomService.createOrGet(HOST_ID, USER_ID))
                .isInstanceOf(ChatException.class)
                .extracting("errorCode").isEqualTo(ChatErrorCode.CHAT_NOT_ALLOWED);
    }

    @Test
    void 탈퇴한_회원과는_채팅방을_만들_수_없다() {
        chatUserService.withdraw(GUEST_ID);

        assertThatThrownBy(() -> chatRoomService.createOrGet(HOST_ID, GUEST_ID))
                .isInstanceOf(ChatException.class)
                .extracting("errorCode").isEqualTo(ChatErrorCode.CHAT_NOT_ALLOWED);
    }

    @Test
    void 자기_자신과는_채팅방을_만들_수_없다() {
        assertThatThrownBy(() -> chatRoomService.createOrGet(HOST_ID, HOST_ID))
                .isInstanceOf(ChatException.class)
                .extracting("errorCode").isEqualTo(ChatErrorCode.CANNOT_CHAT_WITH_SELF);
    }

    @Test
    void 복제된_회원_정보가_없으면_채팅방을_만들_수_없다() {
        assertThatThrownBy(() -> chatRoomService.createOrGet(HOST_ID, UNKNOWN_ID))
                .isInstanceOf(ChatException.class)
                .extracting("errorCode").isEqualTo(ChatErrorCode.CHAT_USER_NOT_FOUND);
    }

    @Test
    void 관리자는_방의_로그와_메시지_기록을_조회한다() {
        Long chatRoomId = createRoom();
        chatMessageService.send(chatRoomId, HOST_ID, "방송 일정 맞춰요");

        ChatRoomHistoryResponse history = chatRoomService.getHistory(chatRoomId, ADMIN_ID);

        assertThat(history.status()).isEqualTo(ChatRoomStatus.ACTIVE);
        assertThat(history.messages())
                .extracting(ChatMessageHistoryResponse::roomSequence, ChatMessageHistoryResponse::content)
                .containsExactly(tuple(1L, "방송 일정 맞춰요"));
        assertThat(history.logs()).extracting(ChatRoomLogResponse::eventType)
                .containsExactly(ChatRoomEventType.ROOM_CREATED, ChatRoomEventType.MEMBER_JOINED,
                        ChatRoomEventType.MEMBER_JOINED);
    }

    @Test
    void 관리자가_아니면_기록을_조회할_수_없다() {
        Long chatRoomId = createRoom();

        assertThatThrownBy(() -> chatRoomService.getHistory(chatRoomId, HOST_ID))
                .isInstanceOf(ChatException.class)
                .extracting("errorCode").isEqualTo(ChatErrorCode.ADMIN_ONLY);
    }

    private Long createRoom() {
        return chatRoomService.createOrGet(HOST_ID, GUEST_ID).room().chatRoomId();
    }

    private List<ChatRoomEventType> eventsOf(Long chatRoomId) {
        return chatRoomLogRepository.findByChatRoom_IdOrderByIdAsc(chatRoomId).stream()
                .map(ChatRoomLog::getEventType)
                .toList();
    }

}
