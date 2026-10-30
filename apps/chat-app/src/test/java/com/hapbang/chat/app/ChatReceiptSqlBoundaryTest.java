package com.hapbang.chat.app;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.hapbang.chat.fixture.ChatUserSeeder;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 수신 확인이 실제로 실행하는 SQL과 전달 호출을 기록해 부하 경계를 확인한다(test-criteria "수신 부하"의 서버 부분).
 * <ul>
 *     <li>방 행 잠금(FOR UPDATE 계열)을 걸지 않는다. 방 순번 잠금을 쥔 트랜잭션과 겹쳐도 기다리지 않는 것은
 *     {@code ChatReceiptTest}에서 따로 확인한다.</li>
 *     <li>참여 행을 추가·삭제하지 않고(메시지 × 수신자 행 INSERT 없음), 방 참여자 전체를 읽지 않으며, 자기 참여 행 하나만 갱신한다.</li>
 *     <li>ACK마다 STOMP로 아무에게도 보내지 않는다(방 전체 재방송 없음).</li>
 * </ul>
 * 같은 기록기로 메시지 전송을 기록해, 잠금·방 전체 조회를 기록기가 실제로 잡아내는지 대조군으로 확인한다.
 */
@IntegrationTest
@TestPropertySource(properties = "spring.jpa.properties.hibernate.session_factory.statement_inspector="
        + "com.hapbang.chat.app.ChatReceiptSqlBoundaryTest$SqlRecorder")
class ChatReceiptSqlBoundaryTest {

    private static final Pattern ROW_LOCK = Pattern.compile("\\bfor\\s+(update|no\\s+key\\s+update|share|key\\s+share)\\b");

    @Autowired
    private ChatReceiptService chatReceiptService;

    @Autowired
    private ChatRoomUseCase chatRoomUseCase;

    @Autowired
    private ChatMessageUseCase chatMessageUseCase;

    @Autowired
    private ChatUserUseCase chatUserUseCase;

    @MockitoSpyBean
    private SimpMessagingTemplate messagingTemplate;

    private Long chatRoomId;

    @BeforeEach
    void setUp() {
        ChatUserSeeder.seedDefaultUsers(chatUserUseCase);
        chatRoomId = chatRoomUseCase.createOrGet(HOST_ID, GUEST_ID).room().chatRoomId();
        chatMessageUseCase.send(chatRoomId, HOST_ID, "메시지", null);
    }

    @AfterEach
    void tearDown() {
        SqlRecorder.stop();
    }

    @Test
    void 기록기는_메시지_전송의_방_잠금과_방_참여자_전체_조회를_잡아낸다() {
        List<String> sql = SqlRecorder.record(() -> chatMessageUseCase.send(chatRoomId, HOST_ID, "대조군", null));

        assertThat(sql).anyMatch(statement -> ROW_LOCK.matcher(statement).find() && statement.contains("chat_room "));
        assertThat(sql).anyMatch(ChatReceiptSqlBoundaryTest::isRoomWideMemberRead);
        assertThat(sql).anyMatch(statement -> statement.startsWith("insert"));
    }

    @Test
    void 수신_확인은_행_잠금_없이_자기_참여_행_하나만_갱신하고_행을_추가하거나_방_참여자_전체를_읽지_않는다() {
        List<String> sql = SqlRecorder.record(() -> chatReceiptService.markReceived(chatRoomId, GUEST_ID, 1, 1));

        assertThat(sql).isNotEmpty();
        assertThat(sql).noneMatch(statement -> ROW_LOCK.matcher(statement).find());
        assertThat(sql).noneMatch(statement -> statement.startsWith("insert") || statement.startsWith("delete"));
        assertThat(sql).noneMatch(ChatReceiptSqlBoundaryTest::isRoomWideMemberRead);
        List<String> updates = sql.stream().filter(statement -> statement.startsWith("update")).toList();
        assertThat(updates).singleElement().satisfies(update -> {
            assertThat(update).startsWith("update chats.chat_room_member ").doesNotContain("chat_room ");
            assertThat(update).contains("last_received_sequence", "user_id", "participation_version");
        });
    }

    @Test
    void 수신_확인은_STOMP로_아무에게도_보내지_않는다() {
        clearInvocations(messagingTemplate);

        chatReceiptService.markReceived(chatRoomId, GUEST_ID, 1, 1);
        chatReceiptService.markReceived(chatRoomId, HOST_ID, 1, 1);

        verifyNoInteractions(messagingTemplate);
    }

    /** 회원 조건 없이 방의 참여 행을 읽는 조회(방 참여자 전체 순회) */
    private static boolean isRoomWideMemberRead(String statement) {
        return statement.startsWith("select") && statement.contains("chat_room_member")
                && !statement.contains("user_id=?");
    }

    /**
     * Hibernate가 실행하는 SQL을 기록한다. 테스트 스레드에서 {@link #record} 동안 실행한 SQL만 모은다.
     */
    public static class SqlRecorder implements StatementInspector {

        private static final ThreadLocal<List<String>> RECORDED = new ThreadLocal<>();

        static List<String> record(Runnable action) {
            List<String> recorded = new ArrayList<>();
            RECORDED.set(recorded);
            try {
                action.run();
            } finally {
                RECORDED.remove();
            }
            return recorded;
        }

        static void stop() {
            RECORDED.remove();
        }

        @Override
        public String inspect(String sql) {
            List<String> recorded = RECORDED.get();
            if (recorded != null) {
                recorded.add(sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim());
            }
            return sql;
        }
    }
}
