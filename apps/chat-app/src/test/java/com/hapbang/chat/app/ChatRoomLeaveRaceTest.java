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

import com.hapbang.chat.domain.ChatRoomEventType;
import com.hapbang.chat.domain.ChatRoomLog;
import com.hapbang.chat.domain.ChatRoomMember;
import com.hapbang.chat.fixture.ChatUserSeeder;
import com.hapbang.chat.out.ChatRoomLogRepository;
import com.hapbang.chat.out.ChatRoomMemberRepository;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 퇴장이 참여 행을 읽은 뒤 저장하기 전에, 방을 잠그지 않는 조건부 갱신(수신 확인·읽음)이 먼저 커밋되는 경합.
 * 퇴장은 바꾼 컬럼만 저장해야 하며, 읽어 둔 옛 수신 확인 순번·읽음 순번으로 되돌리면 안 된다(같은 참여 세대 안 단조 증가).
 * 퇴장 기록 저장 직전에 다른 스레드(별도 트랜잭션)에서 갱신을 커밋한다. 퇴장의 참여 행 UPDATE는 그 뒤 flush 때 나간다.
 */
@IntegrationTest
class ChatRoomLeaveRaceTest {

    @Autowired
    private ChatReceiptService chatReceiptService;

    @Autowired
    private ChatRoomUseCase chatRoomUseCase;

    @Autowired
    private ChatReadService chatReadService;

    @Autowired
    private ChatMessageUseCase chatMessageUseCase;

    @Autowired
    private ChatUserUseCase chatUserUseCase;

    @Autowired
    private ChatRoomMemberRepository chatRoomMemberRepository;

    @MockitoSpyBean
    private ChatRoomLogRepository chatRoomLogRepository;

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
    void 퇴장이_읽은_뒤_커밋된_수신_확인을_옛_값으로_덮지_않는다() {
        chatReceiptService.markReceived(chatRoomId, GUEST_ID, 1, 1);
        interleaveBeforeLeaveLog(() -> chatReceiptService.markReceived(chatRoomId, GUEST_ID, 1, 3));

        assertThat(chatRoomUseCase.leave(chatRoomId, GUEST_ID, null).left()).isTrue();

        ChatRoomMember guest = member(GUEST_ID);
        assertThat(guest.isActive()).isFalse();
        assertThat(guest.getLastReceivedSequence()).isEqualTo(3L);
    }

    @Test
    void 퇴장이_읽은_뒤_커밋된_읽음_처리를_옛_값으로_덮지_않는다() {
        chatReadService.markRead(chatRoomId, GUEST_ID, 1, null);
        interleaveBeforeLeaveLog(() -> chatReadService.markRead(chatRoomId, GUEST_ID, 3, null));

        assertThat(chatRoomUseCase.leave(chatRoomId, GUEST_ID, null).left()).isTrue();

        ChatRoomMember guest = member(GUEST_ID);
        assertThat(guest.isActive()).isFalse();
        assertThat(guest.getLastReadSequence()).isEqualTo(3);
    }

    /**
     * 처음 저장되는 퇴장 기록 직전에 다른 스레드에서 작업을 커밋한다. 이때 퇴장 트랜잭션은 참여 행을 읽고 퇴장으로 바꿨지만 아직
     * DB에 쓰지 않았다(참여 행 잠금 없음).
     */
    private void interleaveBeforeLeaveLog(Runnable interleaved) {
        AtomicBoolean first = new AtomicBoolean(true);
        // Repository 스파이는 원본 Repository에 위임하는 기본 응답으로 만들어진다. 실제 저장은 그 응답으로 실행한다
        Answer<?> realRepository = mockingDetails(chatRoomLogRepository).getMockCreationSettings()
                .getDefaultAnswer();
        doAnswer(invocation -> {
            ChatRoomLog log = invocation.getArgument(0);
            if (log.getEventType() == ChatRoomEventType.MEMBER_LEFT && first.getAndSet(false)) {
                ExecutorService executor = Executors.newSingleThreadExecutor();
                try {
                    executor.submit(interleaved).get(10, TimeUnit.SECONDS);
                } finally {
                    executor.shutdownNow();
                }
            }
            return realRepository.answer(invocation);
        }).when(chatRoomLogRepository).save(any(ChatRoomLog.class));
    }

    private ChatRoomMember member(Long userId) {
        return chatRoomMemberRepository.findByChatRoom_IdAndUserIdAndDeletedAtIsNull(chatRoomId, userId)
                .orElseThrow();
    }
}
