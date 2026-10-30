package com.hapbang.chat.app;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.hapbang.chat.app.dto.ChatReceiptResponse;
import com.hapbang.chat.domain.ChatRoomMember;
import com.hapbang.chat.fixture.ChatUserSeeder;
import com.hapbang.chat.out.ChatMessageRepository;
import com.hapbang.chat.out.ChatRoomMemberRepository;
import com.hapbang.chat.out.ChatRoomRepository;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 수신 확인의 DB 실패·응답 유실 뒤 재시도(설계 문서 4.4 "클라이언트 ACK와 서버 갱신" 6).
 * 실패하면 수신 순번은 그대로이고 메시지 원본·읽음도 바뀌지 않으며, 같은 세대의 같은 값으로 다시 보내면 반영된다.
 */
@IntegrationTest
class ChatReceiptFailureTest {

    @Autowired
    private ChatReceiptService chatReceiptService;

    @Autowired
    private ChatRoomUseCase chatRoomUseCase;

    @Autowired
    private ChatMessageUseCase chatMessageUseCase;

    @Autowired
    private ChatUserUseCase chatUserUseCase;

    @Autowired
    private ChatRoomRepository chatRoomRepository;

    @Autowired
    private ChatMessageRepository chatMessageRepository;

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
    void 갱신_뒤_커밋_전에_DB가_실패하면_수신_순번은_롤백되고_같은_값으로_다시_보내면_반영된다() {
        failOnceAfterRealUpdate();

        assertThatThrownBy(() -> chatReceiptService.markReceived(chatRoomId, GUEST_ID, 1, 3))
                .isInstanceOf(TransientDataAccessResourceException.class);

        // 실제 UPDATE는 실행됐지만 트랜잭션이 롤백돼 미확인으로 남는다. 메시지 원본·방 순번·읽음은 바뀌지 않는다
        ChatRoomMember afterFailure = member(GUEST_ID);
        assertThat(afterFailure.getLastReceivedSequence()).isNull();
        assertThat(afterFailure.getLastReadSequence()).isZero();
        assertThat(chatMessageRepository.findByChatRoom_IdOrderByRoomSequenceAsc(chatRoomId)).hasSize(3);
        assertThat(chatRoomRepository.findById(chatRoomId).orElseThrow().getLastSequence()).isEqualTo(3);

        assertThat(chatReceiptService.markReceived(chatRoomId, GUEST_ID, 1, 3))
                .isEqualTo(new ChatReceiptResponse(chatRoomId, 1, 3));
        assertThat(member(GUEST_ID).getLastReceivedSequence()).isEqualTo(3L);
    }

    @Test
    void 커밋_뒤_응답을_잃어_같은_값을_다시_보내면_상태를_바꾸지_않고_같은_결과로_응답한다() {
        ChatReceiptResponse first = chatReceiptService.markReceived(chatRoomId, GUEST_ID, 1, 2);
        clearInvocations(chatRoomMemberRepository);

        ChatReceiptResponse retried = chatReceiptService.markReceived(chatRoomId, GUEST_ID, 1, 2);

        assertThat(retried).isEqualTo(first).isEqualTo(new ChatReceiptResponse(chatRoomId, 1, 2));
        // 재시도는 조건부 갱신에서 바뀐 행이 없다
        verify(chatRoomMemberRepository).advanceLastReceivedSequence(chatRoomId, GUEST_ID, 1, 2L);
        assertThat(member(GUEST_ID).getLastReceivedSequence()).isEqualTo(2L);
        assertThat(chatMessageRepository.findByChatRoom_IdOrderByRoomSequenceAsc(chatRoomId)).hasSize(3);
    }

    /** 처음 호출된 수신 확인 갱신을 실제로 실행한 뒤 DB 실패를 던진다. 그 뒤의 호출은 그대로 실행한다 */
    private void failOnceAfterRealUpdate() {
        AtomicBoolean first = new AtomicBoolean(true);
        Answer<?> realRepository = mockingDetails(chatRoomMemberRepository).getMockCreationSettings()
                .getDefaultAnswer();
        doAnswer(invocation -> {
            Object updated = realRepository.answer(invocation);
            if (first.getAndSet(false)) {
                assertThat(updated).isEqualTo(1);
                throw new TransientDataAccessResourceException("DB 실패");
            }
            return updated;
        }).when(chatRoomMemberRepository).advanceLastReceivedSequence(anyLong(), anyLong(), anyInt(), anyLong());
    }

    private ChatRoomMember member(Long userId) {
        return chatRoomMemberRepository.findByChatRoom_IdAndUserIdAndDeletedAtIsNull(chatRoomId, userId)
                .orElseThrow();
    }
}
