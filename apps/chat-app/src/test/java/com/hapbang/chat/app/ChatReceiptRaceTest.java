package com.hapbang.chat.app;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.hapbang.chat.app.exception.ChatErrorCode;
import com.hapbang.chat.app.exception.ChatException;
import com.hapbang.chat.domain.ChatRoomMember;
import com.hapbang.chat.fixture.ChatUserSeeder;
import com.hapbang.chat.out.ChatRoomMemberRepository;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 수신 확인의 사전 확인과 조건부 갱신 사이에 퇴장·재입장·다른 기기의 확인이 끼어드는 경합.
 * 갱신 직전에 다른 스레드에서 끼어드는 작업을 커밋하고 이어서 실제 갱신을 실행한다.
 */
@IntegrationTest
class ChatReceiptRaceTest {

    @Autowired
    private ChatReceiptService chatReceiptService;

    @Autowired
    private ChatRoomUseCase chatRoomUseCase;

    @Autowired
    private ChatMessageUseCase chatMessageUseCase;

    @Autowired
    private ChatUserUseCase chatUserUseCase;

    @MockitoSpyBean
    private ChatRoomMemberRepository chatRoomMemberRepository;

    private Long chatRoomId;

    @BeforeEach
    void setUp() {
        ChatUserSeeder.seedDefaultUsers(chatUserUseCase);
        chatRoomId = chatRoomUseCase.createOrGet(HOST_ID, GUEST_ID).chatRoomId();
        for (int i = 0; i < 3; i++) {
            chatMessageUseCase.send(chatRoomId, HOST_ID, "메시지 " + i, null);
        }
    }

    @Test
    void 확인_뒤_재입장이_끼어들면_옛_세대의_수신_확인은_새_세대에_반영되지_않는다() {
        interleaveBeforeUpdate(() -> {
            chatRoomUseCase.leave(chatRoomId, GUEST_ID, null);
            chatRoomUseCase.createOrGet(GUEST_ID, HOST_ID);
        });

        assertThatThrownBy(() -> chatReceiptService.markReceived(chatRoomId, GUEST_ID, 1, 3))
                .isInstanceOf(ChatException.class)
                .extracting("errorCode").isEqualTo(ChatErrorCode.STALE_PARTICIPATION);

        ChatRoomMember guest = member(GUEST_ID);
        assertThat(guest.getParticipationVersion()).isEqualTo(2);
        assertThat(guest.getLastReceivedSequence()).isNull();
    }

    @Test
    void 확인_뒤_퇴장이_끼어들면_참여자가_아니다() {
        interleaveBeforeUpdate(() -> chatRoomUseCase.leave(chatRoomId, GUEST_ID, null));

        assertThatThrownBy(() -> chatReceiptService.markReceived(chatRoomId, GUEST_ID, 1, 3))
                .isInstanceOf(ChatException.class)
                .extracting("errorCode").isEqualTo(ChatErrorCode.NOT_CHAT_ROOM_MEMBER);

        assertThat(member(GUEST_ID).getLastReceivedSequence()).isNull();
    }

    @Test
    void 확인_뒤_다른_기기가_더_높게_올리면_그_값으로_200이다() {
        interleaveBeforeUpdate(() -> chatReceiptService.markReceived(chatRoomId, GUEST_ID, 1, 3));

        assertThat(chatReceiptService.markReceived(chatRoomId, GUEST_ID, 1, 2).lastReceivedSequence()).isEqualTo(3);
        assertThat(member(GUEST_ID).getLastReceivedSequence()).isEqualTo(3L);
    }

    /**
     * 처음 호출된 수신 확인 갱신 직전에 다른 스레드(별도 트랜잭션)에서 작업을 커밋한다. 그 뒤의 호출은 그대로 실행한다.
     */
    private void interleaveBeforeUpdate(Runnable interleaved) {
        AtomicBoolean first = new AtomicBoolean(true);
        // Repository 스파이는 원본 Repository에 위임하는 기본 응답으로 만들어진다. 실제 갱신은 그 응답으로 실행한다
        Answer<?> realRepository = mockingDetails(chatRoomMemberRepository).getMockCreationSettings()
                .getDefaultAnswer();
        doAnswer(invocation -> {
            if (first.getAndSet(false)) {
                ExecutorService executor = Executors.newSingleThreadExecutor();
                try {
                    executor.submit(interleaved).get(10, TimeUnit.SECONDS);
                } finally {
                    executor.shutdownNow();
                }
            }
            return realRepository.answer(invocation);
        }).when(chatRoomMemberRepository).advanceLastReceivedSequence(anyLong(), anyLong(), anyInt(), anyLong());
    }

    private ChatRoomMember member(Long userId) {
        return chatRoomMemberRepository.findByChatRoom_IdAndUserIdAndDeletedAtIsNull(chatRoomId, userId)
                .orElseThrow();
    }
}
