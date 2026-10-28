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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.hapbang.chat.app.dto.ChatMessageHistoryResponse;
import com.hapbang.chat.app.dto.ChatRoomHistoryResponse;
import com.hapbang.chat.app.dto.ChatRoomLogResponse;
import com.hapbang.chat.domain.ChatMessageType;
import com.hapbang.chat.domain.ChatRoomEventType;
import com.hapbang.chat.fixture.ChatUserSeeder;
import com.hapbang.chat.out.ChatRoomLogRepository;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 관리자 기록 조회의 기록 조회와 메시지 조회 사이에 퇴장이 커밋돼도, 한 페이지는 같은 DB 스냅샷을 본다 (#263).
 * 다른 스냅샷을 보면 퇴장 안내 메시지만 페이지에 들어가고, 커서가 그 메시지를 지나며 같은 시각의 퇴장 기록이 영구히 빠진다.
 */
@IntegrationTest
class ChatRoomHistorySnapshotTest {

    @MockitoSpyBean
    private ChatRoomLogRepository chatRoomLogRepository;

    @Autowired
    private ChatRoomSupporter chatRoomSupporter;

    @Autowired
    private ChatRoomUseCase chatRoomUseCase;

    @Autowired
    private ChatUserUseCase chatUserUseCase;

    private Long chatRoomId;

    @BeforeEach
    void setUp() {
        ChatUserSeeder.seedDefaultUsers(chatUserUseCase);
        chatRoomId = chatRoomUseCase.createOrGet(HOST_ID, GUEST_ID).room().chatRoomId();
    }

    @Test
    void 기록_조회와_메시지_조회_사이에_퇴장이_커밋돼도_퇴장_안내만_페이지에_들어가지_않는다() throws Exception {
        AtomicBoolean leaveOnce = new AtomicBoolean(true);
        // repository는 인터페이스 프록시라 callRealMethod 대신 스파이 빈의 기본 동작(실제 빈 위임)을 부른다
        Answer<?> realRepository = mockingDetails(chatRoomLogRepository).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            Object logs = realRepository.answer(invocation);
            if (leaveOnce.getAndSet(false)) {
                // 다른 트랜잭션에서 퇴장을 커밋한다 (퇴장 기록과 안내 메시지가 같은 시각)
                Thread leave = new Thread(() -> chatRoomUseCase.leave(chatRoomId, GUEST_ID, null));
                leave.start();
                leave.join();
            }
            return logs;
        }).when(chatRoomLogRepository).findByChatRoom_IdOrderByOccurredAtAscIdAsc(anyLong(), any());

        ChatRoomHistoryResponse page = chatRoomSupporter.getHistory(chatRoomId, ADMIN_ID, null, 4);

        boolean hasLeftLog = page.logs().stream().map(ChatRoomLogResponse::eventType)
                .anyMatch(ChatRoomEventType.MEMBER_LEFT::equals);
        boolean hasLeftNotice = page.messages().stream().map(ChatMessageHistoryResponse::messageType)
                .anyMatch(ChatMessageType.SYSTEM::equals);
        assertThat(leaveOnce).isFalse();
        assertThat(hasLeftNotice).isEqualTo(hasLeftLog);
        // 퇴장이 커밋된 뒤 처음부터 다시 조회하면 둘 다 나온다
        ChatRoomHistoryResponse after = chatRoomSupporter.getHistory(chatRoomId, ADMIN_ID, null, 100);
        assertThat(after.logs()).extracting(ChatRoomLogResponse::eventType).contains(ChatRoomEventType.MEMBER_LEFT);
        assertThat(after.messages()).extracting(ChatMessageHistoryResponse::messageType)
                .contains(ChatMessageType.SYSTEM);
    }
}
