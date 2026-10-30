package com.hapbang.chat.app;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.hapbang.chat.fixture.ChatUserSeeder;
import com.hapbang.shared.appearance.dto.AppearanceRequestDto;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 잠금 구간 축소(설계 4.6 "전송의 세 구간", test-criteria "출시 전 급증·잠금 검증 > 잠금 구간")를 실제 SQL로 확인한다.
 * <p>
 * Hibernate가 실행한 SQL마다 그때의 트랜잭션 이름·읽기 전용 여부를 함께 기록한다. 그래서 어떤 조회가 어느 트랜잭션에서
 * 실행됐는지(저장 트랜잭션 안인지, 커밋 뒤 별도 읽기 트랜잭션인지) 구분할 수 있다.
 * <ul>
 *     <li>저장 트랜잭션은 방 참여자 엔티티 전체를 읽지 않고, 회원 닉네임을 여러 명 한꺼번에 읽지 않는다(목록·응답 조립 없음).
 *     참여 변경이 수신자 집합을 만들 때의 활성 참여자 집계(count)·DB 안 복사(insert ... select)는 설계상 이 구간의 비용이다.</li>
 *     <li>메시지 전송은 방 잠금 → 발신자 참여 행 하나 → 자격 → 저장·outbox만 한다. 방 단위 참여 조회는 집계도 하지 않는다.</li>
 *     <li>채팅 시작의 방 응답(참여자·닉네임·마지막 메시지·안 읽은 수)은 저장 트랜잭션이 끝난 뒤 별도 읽기 전용 트랜잭션에서 만든다.
 *     Facade는 트랜잭션을 열지 않으므로 재시도마다 UseCase 프록시가 새 저장 트랜잭션을 연다.</li>
 *     <li>저장 중에 STOMP 전송·Redis 호출이 없다(외부 I/O는 커밋 뒤 relay·consumer·구독자가 한다).</li>
 * </ul>
 */
@IntegrationTest
@TestPropertySource(properties = "spring.jpa.properties.hibernate.session_factory.statement_inspector="
        + "com.hapbang.chat.app.ChatSaveTransactionBoundaryTest$TxSqlRecorder")
class ChatSaveTransactionBoundaryTest {

    private static final Pattern ROW_LOCK = Pattern.compile("\\bfor\\s+(update|no\\s+key\\s+update)\\b");
    private static final String SEND_TX = ChatMessageUseCase.class.getName() + ".send";
    private static final String OPEN_TX = ChatRoomUseCase.class.getName() + ".createOrGet";
    private static final String LEAVE_TX = ChatRoomUseCase.class.getName() + ".leave";
    private static final String NOTIFY_TX = ChatNotificationUseCase.class.getName()
            + ".notifyAppearanceRequestSent";
    private static final String ROOM_RESPONSE_TX = ChatRoomSupporter.class.getName() + ".toResponse";

    @Autowired
    private ChatRoomFacade chatRoomFacade;

    @Autowired
    private ChatMessageFacade chatMessageFacade;

    @Autowired
    private ChatNotificationFacade chatNotificationFacade;

    @Autowired
    private ChatRoomUseCase chatRoomUseCase;

    @Autowired
    private ChatMessageUseCase chatMessageUseCase;

    @Autowired
    private ChatUserUseCase chatUserUseCase;

    @MockitoSpyBean
    private SimpMessagingTemplate messagingTemplate;

    @MockitoSpyBean
    private StringRedisTemplate stringRedisTemplate;

    private Long chatRoomId;

    @BeforeEach
    void setUp() {
        ChatUserSeeder.seedDefaultUsers(chatUserUseCase);
        chatRoomId = chatRoomUseCase.createOrGet(HOST_ID, GUEST_ID).chatRoomId();
        clearInvocations(messagingTemplate, stringRedisTemplate);
    }

    @AfterEach
    void tearDown() {
        TxSqlRecorder.stop();
    }

    @Test
    void 메시지_전송의_저장_트랜잭션은_방_잠금과_발신자_참여_행_하나만_읽고_방_참여자를_훑지_않는다() {
        List<TxSql> recorded = TxSqlRecorder.record(
                () -> chatMessageFacade.send(chatRoomId, HOST_ID, "잠금 구간 확인", "client-1"));

        List<String> sendTx = sqlIn(recorded, SEND_TX);
        assertThat(recorded).allMatch(sql -> SEND_TX.equals(sql.transaction()));
        assertThat(sendTx).anyMatch(sql -> ROW_LOCK.matcher(sql).find() && sql.contains("chat_room "));
        assertThat(sendTx).filteredOn(ChatSaveTransactionBoundaryTest::readsMembers)
                .singleElement().asString().contains("user_id=?").doesNotContain("count(");
        assertThat(sendTx).noneMatch(ChatSaveTransactionBoundaryTest::readsManyUsers);
        assertThat(sendTx).anyMatch(sql -> sql.startsWith("insert into chats.chat_message "));
        assertThat(sendTx).anyMatch(sql -> sql.startsWith("insert into chats.chat_outbox "));
        assertNoExternalIo();
    }

    @Test
    @WithMockUser(roles = "INFLUENCER")
    void 채팅_시작은_저장_트랜잭션에서_목록을_만들지_않고_방_응답은_커밋_뒤_별도_읽기_트랜잭션에서_만든다() {
        chatRoomUseCase.leave(chatRoomId, GUEST_ID, null);

        List<TxSql> recorded = TxSqlRecorder.record(() -> chatRoomFacade.createOrGet(GUEST_ID, HOST_ID));

        // Facade는 트랜잭션을 열지 않는다: 저장(UseCase)과 응답 조립(Supporter)이 서로 다른 트랜잭션이다
        assertThat(recorded).extracting(TxSql::transaction).containsOnly(OPEN_TX, ROOM_RESPONSE_TX);
        int lastWrite = lastIndexOf(recorded, OPEN_TX);
        int firstRead = firstIndexOf(recorded, ROOM_RESPONSE_TX);
        assertThat(lastWrite).as("저장 트랜잭션이 끝난 뒤에 응답을 조립한다").isLessThan(firstRead);
        assertThat(recorded).filteredOn(sql -> ROOM_RESPONSE_TX.equals(sql.transaction()))
                .allMatch(TxSql::readOnly);

        List<String> openTx = sqlIn(recorded, OPEN_TX);
        assertThat(openTx).anyMatch(sql -> ROW_LOCK.matcher(sql).find());
        assertThat(openTx).noneMatch(ChatSaveTransactionBoundaryTest::readsAllMemberRows);
        assertThat(openTx).noneMatch(ChatSaveTransactionBoundaryTest::readsManyUsers);
        // 방 응답 조립(참여자 전체·닉네임 일괄 조회)은 읽기 트랜잭션에만 있다
        List<String> responseTx = sqlIn(recorded, ROOM_RESPONSE_TX);
        assertThat(responseTx).anyMatch(ChatSaveTransactionBoundaryTest::readsAllMemberRows);
        assertThat(responseTx).anyMatch(ChatSaveTransactionBoundaryTest::readsManyUsers);
        assertNoExternalIo();
    }

    @Test
    void 퇴장의_저장_트랜잭션은_남은_참여자_목록을_만들지_않는다() {
        List<TxSql> recorded = TxSqlRecorder.record(() -> chatRoomFacade.leave(chatRoomId, GUEST_ID, null));

        assertThat(recorded).extracting(TxSql::transaction).containsOnly(LEAVE_TX);
        List<String> leaveTx = sqlIn(recorded, LEAVE_TX);
        assertThat(leaveTx).noneMatch(ChatSaveTransactionBoundaryTest::readsAllMemberRows);
        assertThat(leaveTx).noneMatch(ChatSaveTransactionBoundaryTest::readsManyUsers);
        assertThat(leaveTx).anyMatch(sql -> sql.startsWith("insert into chats.chat_outbox "));
        assertNoExternalIo();
    }

    @Test
    void 출연_알림의_저장_트랜잭션은_전달_대상_목록을_만들지_않는다() {
        chatRoomUseCase.leave(chatRoomId, GUEST_ID, null);

        List<TxSql> recorded = TxSqlRecorder.record(() -> chatNotificationFacade.notifyAppearanceRequestSent(
                new AppearanceRequestDto(10L, HOST_ID, GUEST_ID, "금요일 밤 합방", 50_000L,
                        LocalDateTime.of(2026, 11, 20, 21, 0), LocalDateTime.of(2026, 11, 13, 23, 59))));

        assertThat(recorded).extracting(TxSql::transaction).containsOnly(NOTIFY_TX);
        List<String> notifyTx = sqlIn(recorded, NOTIFY_TX);
        assertThat(notifyTx).noneMatch(ChatSaveTransactionBoundaryTest::readsAllMemberRows);
        assertThat(notifyTx).noneMatch(ChatSaveTransactionBoundaryTest::readsManyUsers);
        assertThat(notifyTx).filteredOn(sql -> sql.startsWith("insert into chats.chat_outbox ")).hasSize(2);
        assertNoExternalIo();
    }

    @Test
    void Facade는_트랜잭션을_열지_않고_UseCase는_프록시로_저장_트랜잭션을_연다() throws NoSuchMethodException {
        // Facade 재시도(유일 제약 충돌)는 UseCase 프록시를 다시 부르므로 시도마다 새 트랜잭션이다
        for (Class<?> facade : List.of(ChatRoomFacade.class, ChatNotificationFacade.class, ChatMessageFacade.class)) {
            assertThat(AnnotatedElementUtils.hasAnnotation(facade, Transactional.class)).as(facade.getSimpleName())
                    .isFalse();
            assertThat(List.of(facade.getDeclaredMethods()))
                    .noneMatch(method -> AnnotatedElementUtils.hasAnnotation(method, Transactional.class));
        }
        assertThat(AopUtils.isAopProxy(chatRoomUseCase)).isTrue();
        assertThat(AopUtils.isAopProxy(chatMessageUseCase)).isTrue();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    private void assertNoExternalIo() {
        verifyNoInteractions(messagingTemplate, stringRedisTemplate);
    }

    /** 방 참여 행을 읽는 조회(엔티티·집계 모두) */
    private static boolean readsMembers(String sql) {
        return sql.startsWith("select") && sql.contains("from chats.chat_room_member ");
    }

    /** 회원 조건 없이 방 참여 행 엔티티를 읽는 조회(방 참여자 전체 로딩). 집계(count)는 뺀다 */
    private static boolean readsAllMemberRows(String sql) {
        return readsMembers(sql) && !sql.contains("user_id=?") && !sql.contains("count(");
    }

    /** 여러 회원의 복제본을 한꺼번에 읽는 조회(닉네임 일괄 조회 등 응답 조립) */
    private static boolean readsManyUsers(String sql) {
        return sql.startsWith("select") && sql.contains("from chats.chat_user ") && sql.contains(" in (");
    }

    private static List<String> sqlIn(List<TxSql> recorded, String transaction) {
        return recorded.stream().filter(sql -> transaction.equals(sql.transaction())).map(TxSql::sql).toList();
    }

    private static int firstIndexOf(List<TxSql> recorded, String transaction) {
        for (int i = 0; i < recorded.size(); i++) {
            if (transaction.equals(recorded.get(i).transaction())) {
                return i;
            }
        }
        return -1;
    }

    private static int lastIndexOf(List<TxSql> recorded, String transaction) {
        int last = -1;
        for (int i = 0; i < recorded.size(); i++) {
            if (transaction.equals(recorded.get(i).transaction())) {
                last = i;
            }
        }
        return last;
    }

    record TxSql(String transaction, boolean readOnly, String sql) {
    }

    /**
     * Hibernate가 실행하는 SQL을 그때의 트랜잭션 이름·읽기 전용 여부와 함께 기록한다. {@link #record} 동안 테스트 스레드에서
     * 실행한 SQL만 모은다.
     */
    public static class TxSqlRecorder implements StatementInspector {

        private static final ThreadLocal<List<TxSql>> RECORDED = new ThreadLocal<>();

        static List<TxSql> record(Runnable action) {
            List<TxSql> recorded = new ArrayList<>();
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
            List<TxSql> recorded = RECORDED.get();
            if (recorded != null) {
                recorded.add(new TxSql(TransactionSynchronizationManager.getCurrentTransactionName(),
                        TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
                        sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim()));
            }
            return sql;
        }
    }
}
