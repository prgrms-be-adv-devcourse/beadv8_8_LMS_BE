package com.hapbang.chat.app;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.hapbang.chat.domain.ChatMessage;
import com.hapbang.chat.domain.ChatMessageType;
import com.hapbang.chat.domain.ChatRoomEventType;
import com.hapbang.chat.domain.ChatRoomLog;
import com.hapbang.chat.fixture.ChatUserSeeder;
import com.hapbang.chat.out.ChatMessageRepository;
import com.hapbang.chat.out.ChatRoomLogRepository;
import com.hapbang.chat.out.ChatRoomMemberRepository;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 수용 테스트 7: 퇴장 중 저장이 실패하면 참여 상태·퇴장 안내·방 로그가 모두 롤백된다.
 */
@IntegrationTest
class ChatRoomLeaveRollbackTest {

    @Autowired
    private ChatRoomService chatRoomService;

    @Autowired
    private ChatUserService chatUserService;

    @MockitoSpyBean
    private ChatMessageRepository chatMessageRepository;

    @Autowired
    private ChatRoomLogRepository chatRoomLogRepository;

    @Autowired
    private ChatRoomMemberRepository chatRoomMemberRepository;

    private Long chatRoomId;

    @BeforeEach
    void setUp() {
        ChatUserSeeder.seedDefaultUsers(chatUserService);
        chatRoomId = chatRoomService.createOrGet(HOST_ID, GUEST_ID).room().chatRoomId();
    }

    @Test
    void 퇴장_안내_저장이_실패하면_퇴장도_로그도_남지_않는다() {
        doThrow(new IllegalStateException("저장 실패"))
                .when(chatMessageRepository)
                .save(argThat((ChatMessage message) -> message.getMessageType() == ChatMessageType.SYSTEM));

        assertThatThrownBy(() -> chatRoomService.leave(chatRoomId, GUEST_ID, null))
                .isInstanceOf(IllegalStateException.class);

        assertThat(chatRoomMemberRepository.findByChatRoom_IdAndUserIdAndDeletedAtIsNull(chatRoomId, GUEST_ID))
                .hasValueSatisfying(member -> assertThat(member.isActive()).isTrue());
        assertThat(chatRoomLogRepository.findByChatRoom_IdOrderByIdAsc(chatRoomId))
                .extracting(ChatRoomLog::getEventType)
                .doesNotContain(ChatRoomEventType.MEMBER_LEFT);
        assertThat(chatMessageRepository.findByChatRoom_IdOrderByRoomSequenceAsc(chatRoomId)).isEmpty();
    }
}
