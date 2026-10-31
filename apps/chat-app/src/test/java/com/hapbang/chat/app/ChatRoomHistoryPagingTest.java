package com.hapbang.chat.app;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import com.hapbang.chat.app.dto.ChatRoomHistoryResponse;
import com.hapbang.chat.app.exception.ChatErrorCode;
import com.hapbang.chat.app.exception.ChatException;
import com.hapbang.chat.fixture.ChatUserSeeder;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 관리자 채팅 기록을 커서로 나눠 조회해도 전체 기록이 누락·중복 없이 시간순으로 나오는지 검증한다 (#263).
 */
@IntegrationTest
class ChatRoomHistoryPagingTest {

    @Autowired
    private ChatRoomUseCase chatRoomUseCase;

    @Autowired
    private ChatRoomSupporter chatRoomSupporter;

    @Autowired
    private ChatMessageUseCase chatMessageUseCase;

    @Autowired
    private ChatUserUseCase chatUserUseCase;

    private Long chatRoomId;

    /** 페이지를 합쳐 비교하기 위한 기록·메시지 한 줄 (시각, 종류 L/M, ID) */
    private record Entry(LocalDateTime at, String kind, Long id) {
    }

    private static final Comparator<Entry> ORDER = Comparator.comparing(Entry::at).thenComparing(Entry::kind)
            .thenComparing(Entry::id);

    @BeforeEach
    void setUp() {
        ChatUserSeeder.seedDefaultUsers(chatUserUseCase);
        // 생성·입장 기록 3개 → 메시지 3개 → 퇴장(기록과 안내 메시지가 같은 시각) → 재입장(안내 메시지와 기록이 같은 시각)
        chatRoomId = chatRoomUseCase.createOrGet(HOST_ID, GUEST_ID).chatRoomId();
        chatMessageUseCase.send(chatRoomId, HOST_ID, "첫 번째", null);
        chatMessageUseCase.send(chatRoomId, GUEST_ID, "두 번째", null);
        chatMessageUseCase.send(chatRoomId, HOST_ID, "세 번째", null);
        chatRoomUseCase.leave(chatRoomId, GUEST_ID, null);
        chatRoomUseCase.createOrGet(HOST_ID, GUEST_ID);
        chatMessageUseCase.send(chatRoomId, GUEST_ID, "다시 왔어요", null);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 7})
    void 커서로_이어_조회하면_전체_기록이_누락_중복_없이_시간순으로_나온다(int size) {
        List<Entry> all = entriesOf(chatRoomSupporter.getHistory(chatRoomId, ADMIN_ID, null, 100));
        assertThat(all).hasSize(5 + 6); // 기록: 생성·입장 2·퇴장·재입장, 메시지: 4 + 퇴장·재입장 안내
        assertThat(all).isSortedAccordingTo(ORDER);

        List<Entry> paged = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            ChatRoomHistoryResponse page = chatRoomSupporter.getHistory(chatRoomId, ADMIN_ID, cursor, size);
            List<Entry> entries = entriesOf(page);
            assertThat(entries.size()).isLessThanOrEqualTo(size);
            paged.addAll(entries);
            cursor = page.nextCursor();
            pages++;
        } while (cursor != null && pages < 100);

        assertThat(paged).containsExactlyElementsOf(all);
    }

    @Test
    void 마지막_페이지는_다음_커서가_없다() {
        assertThat(chatRoomSupporter.getHistory(chatRoomId, ADMIN_ID, null, 100).nextCursor()).isNull();
        assertThat(chatRoomSupporter.getHistory(chatRoomId, ADMIN_ID, null, 11).nextCursor()).isNull();
        assertThat(chatRoomSupporter.getHistory(chatRoomId, ADMIN_ID, null, 10).nextCursor()).isNotNull();
    }

    @Test
    void 형식이_틀린_커서는_거절한다() {
        assertThatThrownBy(() -> chatRoomSupporter.getHistory(chatRoomId, ADMIN_ID, "not-a-cursor", 10))
                .isInstanceOf(ChatException.class)
                .extracting("errorCode").isEqualTo(ChatErrorCode.INVALID_REQUEST);
    }

    private static List<Entry> entriesOf(ChatRoomHistoryResponse page) {
        List<Entry> entries = new ArrayList<>();
        page.logs().forEach(log -> entries.add(new Entry(log.occurredAt(), "L", log.logId())));
        page.messages().forEach(message -> entries.add(new Entry(message.sentAt(), "M", message.messageId())));
        entries.sort(ORDER);
        return entries;
    }
}
