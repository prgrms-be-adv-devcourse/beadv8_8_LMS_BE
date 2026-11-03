package com.hapbang.chat.app;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import com.hapbang.chat.app.dto.ChatRoomHistoryResponse;
import com.hapbang.chat.app.dto.ChatRoomLogResponse;
import com.hapbang.chat.domain.ChatGroupInviteOutcome;
import com.hapbang.chat.domain.ChatGroupInviteRequest;
import com.hapbang.chat.domain.ChatRoom;
import com.hapbang.chat.domain.ChatRoomEventType;
import com.hapbang.chat.domain.ChatRoomStatus;
import com.hapbang.chat.domain.ChatRoomStatusReason;
import com.hapbang.chat.fixture.ChatNativeSql;
import com.hapbang.chat.fixture.ChatUserSeeder;
import com.hapbang.chat.out.ChatGroupInviteRequestRepository;
import com.hapbang.chat.out.ChatRoomRepository;
import com.hapbang.testsupport.IntegrationTest;

import jakarta.persistence.EntityManager;

/**
 * 모집용 단체방 정리 배치(조회 만료 상태 기록, 초대 결과 기록 정리)를 실제 DB로 검증한다. 묶음 크기를 2로 줄여 여러 묶음에 걸친 처리를
 * 확인한다(운영 기본값은 코드에 있다). 배치 빈은 켜지 않고 처리 빈을 직접 부른다.
 */
@IntegrationTest
@Import(ChatGroupTestConfig.class)
@TestPropertySource(properties = {
        "hapbang.chat.group.history-expiry-batch-size=2",
        "hapbang.chat.group.invite-request-cleanup-batch-size=2"
})
class ChatGroupRoomBatchTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 11, 1, 12, 0);
    private static final long FIRST_RECRUIT_ID = 80L;

    @Autowired
    private ChatGroupHistoryExpiryService chatGroupHistoryExpiryService;

    @Autowired
    private ChatGroupInviteRequestCleaner chatGroupInviteRequestCleaner;

    @Autowired
    private ChatGroupRoomFacade chatGroupRoomFacade;

    @Autowired
    private ChatRoomFacade chatRoomFacade;

    @Autowired
    private ChatRoomSupporter chatRoomSupporter;

    @Autowired
    private ChatUserUseCase chatUserUseCase;

    @Autowired
    private ChatRoomRepository chatRoomRepository;

    @Autowired
    private ChatGroupInviteRequestRepository chatGroupInviteRequestRepository;

    @Autowired
    private ChatGroupProperties properties;

    @Autowired
    private InMemoryChatRecruitSource recruits;

    private ChatNativeSql sql;

    private long nextRecruitId = FIRST_RECRUIT_ID;

    @Autowired
    void setUpSql(EntityManager entityManager, PlatformTransactionManager transactionManager) {
        sql = ChatNativeSql.of(entityManager, transactionManager);
    }

    @BeforeEach
    void setUp() {
        ChatUserSeeder.seedDefaultUsers(chatUserUseCase);
        recruits.reset();
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("test", null, "ROLE_INFLUENCER"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ===== 조회 만료 =====

    @Test
    void 만료_시각에_도달한_읽기_전용_방만_여러_묶음에_걸쳐_만료로_바꾸고_방마다_기록을_하나_남긴다() {
        // 만료 대상은 지난 방 5개와 만료 시각이 기준 시각과 같은 방(그 시각 포함)이다. 묶음 크기 2로 세 묶음이다
        List<Long> due = IntStream.range(0, 5).mapToObj(i -> closedRoomExpiringAt(BASE.minusMinutes(i + 1))).toList();
        Long boundary = closedRoomExpiringAt(BASE);
        Long notDue = closedRoomExpiringAt(BASE.plusNanos(1000));
        Long active = openRoom();
        Long direct = chatRoomFacade.createOrGet(HOST_ID, GUEST_ID).room().chatRoomId();

        int expired = chatGroupHistoryExpiryService.expireDue(BASE);

        assertThat(expired).isEqualTo(6);
        for (Long roomId : concat(due, boundary)) {
            ChatRoom room = chatRoomRepository.findById(roomId).orElseThrow();
            assertThat(room.getStatus()).isEqualTo(ChatRoomStatus.EXPIRED);
            assertThat(room.getStatusVersion()).isEqualTo(2);
            assertThat(room.getStatusReason()).isEqualTo(ChatRoomStatusReason.CLOSED_BY_HOST);
            assertThat(expiryLogs(roomId)).singleElement().satisfies(log -> {
                assertThat(log.actorUserId()).isEqualTo(HOST_ID);
                assertThat(log.targetUserId()).isNull();
                assertThat(log.occurredAt()).isEqualTo(room.getHistoryExpiresAt());
            });
        }
        assertThat(statusOf(notDue)).isEqualTo(ChatRoomStatus.READ_ONLY);
        assertThat(chatRoomRepository.findById(notDue).orElseThrow().getStatusVersion()).isEqualTo(1);
        assertThat(statusOf(active)).isEqualTo(ChatRoomStatus.ACTIVE);
        assertThat(statusOf(direct)).isEqualTo(ChatRoomStatus.ACTIVE);
        assertThat(expiryLogs(notDue)).isEmpty();
        assertThat(expiryLogs(active)).isEmpty();
    }

    @Test
    void 다시_실행해도_결과가_같고_기록을_더_남기지_않는다() {
        Long roomId = closedRoomExpiringAt(BASE);
        chatGroupHistoryExpiryService.expireDue(BASE);

        assertThat(chatGroupHistoryExpiryService.expireDue(BASE)).isZero();
        assertThat(chatGroupHistoryExpiryService.expireDue(BASE.plusDays(1))).isZero();

        assertThat(chatRoomRepository.findById(roomId).orElseThrow().getStatusVersion()).isEqualTo(2);
        assertThat(expiryLogs(roomId)).hasSize(1);
    }

    @Test
    void 저장한_만료_상태도_참여자에게_보이지_않고_관리자는_기록을_보며_메시지와_참여_행은_남는다() {
        recruits.putRecruit(FIRST_RECRUIT_ID, HOST_ID, "모집", "RECRUITING");
        recruits.addApplicants(FIRST_RECRUIT_ID, GUEST_ID);
        Long roomId = chatGroupRoomFacade.createRoom(HOST_ID, FIRST_RECRUIT_ID).room().chatRoomId();
        chatGroupRoomFacade.invite(roomId, HOST_ID, UUID.randomUUID(), List.of(GUEST_ID));
        chatGroupRoomFacade.close(roomId, HOST_ID);
        // 폐쇄 뒤의 시각으로 둬 조회 만료 기록이 관리자 기록의 마지막이 되게 한다
        setHistoryExpiresAt(roomId, LocalDateTime.now());
        long messages = countOf("chat_message", roomId);
        long members = countOf("chat_room_member", roomId);

        assertThat(chatGroupHistoryExpiryService.expireDue(LocalDateTime.now())).isEqualTo(1);

        assertThat(chatRoomFacade.getMyRooms(GUEST_ID)).isEmpty();
        assertThat(chatGroupRoomFacade.getRooms(GUEST_ID, FIRST_RECRUIT_ID).previousRooms()).isEmpty();
        ChatRoomHistoryResponse history = chatRoomSupporter.getHistory(roomId, ADMIN_ID, null, 100);
        assertThat(history.status()).isEqualTo(ChatRoomStatus.EXPIRED);
        assertThat(history.logs()).extracting(ChatRoomLogResponse::eventType).last()
                .isEqualTo(ChatRoomEventType.HISTORY_EXPIRED);
        assertThat(countOf("chat_message", roomId)).isEqualTo(messages);
        assertThat(countOf("chat_room_member", roomId)).isEqualTo(members);
        // 만료된 방은 현재 방이 아니므로 같은 모집글에 새 방을 만들 수 있다
        assertThat(chatGroupRoomFacade.createRoom(HOST_ID, FIRST_RECRUIT_ID).room().chatRoomId()).isNotEqualTo(roomId);
    }

    // ===== 초대 결과 기록 정리 =====

    @Test
    void 보존_기간이_지난_초대_결과_기록만_여러_묶음에_걸쳐_지우고_다시_실행해도_같다() {
        Long roomId = openRoom();
        LocalDateTime cutoff = BASE.minus(properties.inviteRequestRetention());
        // 지울 행 5개(묶음 크기 2로 세 묶음). 하나는 보존 기간이 끝나는 시각과 같다(그 시각부터 보존하지 않는다)
        List<Long> expired = IntStream.range(0, 5).mapToObj(i -> inviteRequest(roomId, cutoff.minusMinutes(i))).toList();
        Long retainedAtEdge = inviteRequest(roomId, cutoff.plusNanos(1000));
        Long retainedRecent = inviteRequest(roomId, BASE);
        // 지우는 기준은 초대가 보존 기간 안으로 보는지와 같다
        for (ChatGroupInviteRequest request : chatGroupInviteRequestRepository.findAll()) {
            assertThat(request.isRetained(BASE, properties.inviteRequestRetention()))
                    .isEqualTo(!expired.contains(request.getId()));
        }

        assertThat(chatGroupInviteRequestCleaner.deleteExpired(BASE)).isEqualTo(5);

        assertThat(chatGroupInviteRequestRepository.findAll()).extracting(ChatGroupInviteRequest::getId)
                .containsExactlyInAnyOrder(retainedAtEdge, retainedRecent);
        assertThat(chatGroupInviteRequestCleaner.deleteExpired(BASE)).isZero();
        assertThat(chatGroupInviteRequestRepository.count()).isEqualTo(2);
    }

    @Test
    void 지운_요청_ID가_다시_오면_새_요청으로_초대한다() {
        recruits.putRecruit(FIRST_RECRUIT_ID, HOST_ID, "모집", "RECRUITING");
        recruits.addApplicants(FIRST_RECRUIT_ID, GUEST_ID);
        Long roomId = chatGroupRoomFacade.createRoom(HOST_ID, FIRST_RECRUIT_ID).room().chatRoomId();
        UUID requestId = UUID.randomUUID();
        chatGroupRoomFacade.invite(roomId, HOST_ID, requestId, List.of(GUEST_ID));
        chatGroupRoomFacade.kick(roomId, HOST_ID, GUEST_ID, 1);
        sql.update("update chats.chat_group_invite_request set recorded_at = recorded_at - interval '25 hours'");

        assertThat(chatGroupInviteRequestCleaner.deleteExpired(LocalDateTime.now())).isEqualTo(1);
        List<ChatGroupInviteOutcome> results = chatGroupRoomFacade.invite(roomId, HOST_ID, requestId,
                List.of(GUEST_ID)).results();

        assertThat(results).containsExactly(ChatGroupInviteOutcome.joined(GUEST_ID));
        assertThat(chatGroupInviteRequestRepository.count()).isEqualTo(1);
    }

    private Long closedRoomExpiringAt(LocalDateTime historyExpiresAt) {
        Long roomId = openRoom();
        chatGroupRoomFacade.close(roomId, HOST_ID);
        setHistoryExpiresAt(roomId, historyExpiresAt);
        return roomId;
    }

    /** 모집글마다 사용 중인 방 하나 */
    private Long openRoom() {
        long recruitId = ++nextRecruitId;
        recruits.putRecruit(recruitId, HOST_ID, "모집 " + recruitId, "RECRUITING");
        return chatGroupRoomFacade.createRoom(HOST_ID, recruitId).room().chatRoomId();
    }

    private void setHistoryExpiresAt(Long roomId, LocalDateTime at) {
        sql.update("update chats.chat_room set history_expires_at = :at where id = :id", Map.of("at", at, "id", roomId));
    }

    private Long inviteRequest(Long roomId, LocalDateTime recordedAt) {
        return sql.single("""
                insert into chats.chat_group_invite_request (chat_room_id, request_id, requester_user_id, results,
                                                             recorded_at)
                values (:roomId, :requestId, :requester, cast('[]' as jsonb), :recordedAt)
                returning id
                """, Long.class, Map.of("roomId", roomId, "requestId", UUID.randomUUID(), "requester", HOST_ID,
                "recordedAt", recordedAt));
    }

    private ChatRoomStatus statusOf(Long roomId) {
        return chatRoomRepository.findById(roomId).orElseThrow().getStatus();
    }

    private List<ChatRoomLogResponse> expiryLogs(Long roomId) {
        return chatRoomSupporter.getHistory(roomId, ADMIN_ID, null, 100).logs().stream()
                .filter(log -> log.eventType() == ChatRoomEventType.HISTORY_EXPIRED)
                .toList();
    }

    private long countOf(String table, Long roomId) {
        return sql.single("select count(*) from chats." + table + " where chat_room_id = :id", Long.class,
                Map.of("id", roomId));
    }

    private static List<Long> concat(List<Long> ids, Long id) {
        return Stream.concat(ids.stream(), Stream.of(id)).toList();
    }
}
