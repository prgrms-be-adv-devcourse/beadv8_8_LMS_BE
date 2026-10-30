package com.hapbang.chat.app;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
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
import com.hapbang.chat.out.ChatRoomRepository;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 읽음 처리의 세대 확인과 조건부 갱신 사이에 퇴장·재입장·다른 기기의 읽음이 끼어드는 경합(읽음 처리 계약).
 * 갱신 직전에 다른 스레드에서 끼어드는 작업을 커밋하고 이어서 실제 갱신을 실행한다.
 */
@IntegrationTest
class ChatReadRaceTest {

    @Autowired
    private ChatReadService chatReadService;

    @Autowired
    private ChatRoomUseCase chatRoomUseCase;

    @Autowired
    private ChatMessageUseCase chatMessageUseCase;

    @Autowired
    private ChatUserUseCase chatUserUseCase;

    @Autowired
    private ChatRoomRepository chatRoomRepository;

    @MockitoSpyBean
    private ChatRoomMemberRepository chatRoomMemberRepository;

    private Long chatRoomId;

    @BeforeEach
    void setUp() {
        ChatUserSeeder.seedDefaultUsers(chatUserUseCase);
        chatRoomId = chatRoomUseCase.createOrGet(HOST_ID, GUEST_ID).room().chatRoomId();
        for (int i = 0; i < 3; i++) {
            chatMessageUseCase.send(chatRoomId, HOST_ID, "메시지 " + i, null);
        }
    }

    @Test
    void 세대를_보낸_읽음은_확인_뒤_재입장이_끼어들면_STALE_PARTICIPATION이고_반영하지_않는다() {
        interleaveBeforeUpdate(this::leaveAndRejoinGuest);

        assertRejected(() -> chatReadService.markRead(chatRoomId, GUEST_ID, 3, 1), ChatErrorCode.STALE_PARTICIPATION);

        ChatRoomMember guest = member(GUEST_ID);
        assertThat(guest.getParticipationVersion()).isEqualTo(2);
        assertThat(guest.getLastReadSequence()).isEqualTo(lastSequence());
    }

    @Test
    void 세대를_보내지_않은_읽음도_확인_뒤_재입장이_끼어들면_STALE_PARTICIPATION이다() {
        interleaveBeforeUpdate(this::leaveAndRejoinGuest);

        assertRejected(() -> chatReadService.markRead(chatRoomId, GUEST_ID, 3, null),
                ChatErrorCode.STALE_PARTICIPATION);

        assertThat(member(GUEST_ID).getLastReadSequence()).isEqualTo(lastSequence());
    }

    @Test
    void 확인_뒤_퇴장이_끼어들면_참여자가_아니고_퇴장한_행을_바꾸지_않는다() {
        chatReadService.markRead(chatRoomId, GUEST_ID, 1, 1);
        interleaveBeforeUpdate(() -> chatRoomUseCase.leave(chatRoomId, GUEST_ID, null));

        assertRejected(() -> chatReadService.markRead(chatRoomId, GUEST_ID, 3, 1),
                ChatErrorCode.NOT_CHAT_ROOM_MEMBER);

        ChatRoomMember guest = member(GUEST_ID);
        assertThat(guest.isActive()).isFalse();
        assertThat(guest.getLastReadSequence()).isEqualTo(1);
    }

    @Test
    void 확인_뒤_다른_기기가_더_높게_읽으면_바꾸지_않고_성공한다() {
        interleaveBeforeUpdate(() -> chatReadService.markRead(chatRoomId, GUEST_ID, 3, 1));

        chatReadService.markRead(chatRoomId, GUEST_ID, 2, 1);

        assertThat(member(GUEST_ID).getLastReadSequence()).isEqualTo(3);
    }

    private void leaveAndRejoinGuest() {
        chatRoomUseCase.leave(chatRoomId, GUEST_ID, null);
        chatRoomUseCase.createOrGet(GUEST_ID, HOST_ID);
    }

    /**
     * 처음 호출된 읽음 갱신 직전에 다른 스레드(별도 트랜잭션)에서 작업을 커밋한다. 그 뒤의 호출은 그대로 실행한다.
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
        }).when(chatRoomMemberRepository).advanceLastReadSequenceInParticipation(anyLong(), anyLong(), anyInt(),
                anyLong());
    }

    private long lastSequence() {
        return chatRoomRepository.findById(chatRoomId).orElseThrow().getLastSequence();
    }

    private ChatRoomMember member(Long userId) {
        return chatRoomMemberRepository.findByChatRoom_IdAndUserIdAndDeletedAtIsNull(chatRoomId, userId)
                .orElseThrow();
    }

    private static void assertRejected(ThrowingCallable call,
            ChatErrorCode errorCode) {
        assertThatThrownBy(call)
                .isInstanceOf(ChatException.class)
                .extracting("errorCode").isEqualTo(errorCode);
    }
}
