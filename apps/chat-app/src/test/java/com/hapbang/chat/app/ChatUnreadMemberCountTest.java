package com.hapbang.chat.app;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;

import com.hapbang.chat.app.ChatUnreadMemberCounter.RecentCounts;
import com.hapbang.chat.app.dto.ChatMessagePageResponse;
import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.app.dto.ReadChangedFanout;
import com.hapbang.chat.app.exception.ChatException;
import com.hapbang.chat.domain.ChatMessageType;
import com.hapbang.chat.fixture.ChatNativeSql;
import com.hapbang.chat.fixture.ChatUserSeeder;
import com.hapbang.chat.out.ChatMessageRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.IntegrationTest;

import jakarta.persistence.EntityManager;

/**
 * 메시지별 안 읽은 사람 수(설계 3.5, Q36)를 실제 DB로 검증한다.
 * <ul>
 *     <li>식: 현재 참여 중 AND 보낸 사람이 아님 AND 가시 범위 시작 ≤ s AND 읽음 순번 < s. 1:1 방은 0 또는 1이다.</li>
 *     <li>늦은 초대·재입장은 이전 메시지에 들지 않고, 나가기·강퇴는 빠진다. 값은 줄기만 한다.</li>
 *     <li>시스템 안내는 null이다. 페이지마다 집계 문장 하나와 버전(DB 시각)을 싣는다. 읽기 전용 방도 갱신한다.</li>
 *     <li>읽음·참여 변경은 커밋된 뒤에만 변경 신호를 낸다(롤백·변경 없음은 내지 않는다).</li>
 * </ul>
 */
@IntegrationTest
@Import(ChatGroupTestConfig.class)
class ChatUnreadMemberCountTest {

    private static final Long RECRUIT_ID = 80L;
    private static final Long GUEST_A = 11L;
    private static final Long GUEST_B = 12L;
    private static final long TIMEOUT_MILLIS = 2_000;

    @Autowired
    private ChatRoomUseCase chatRoomUseCase;

    @Autowired
    private ChatMessageUseCase chatMessageUseCase;

    @Autowired
    private ChatMessageSupporter chatMessageSupporter;

    @Autowired
    private ChatReadService chatReadService;

    @Autowired
    private ChatGroupRoomFacade chatGroupRoomFacade;

    @Autowired
    private ChatUnreadMemberCounter chatUnreadMemberCounter;

    @Autowired
    private ChatUserUseCase chatUserUseCase;

    @Autowired
    private InMemoryChatRecruitSource recruits;

    @MockitoSpyBean
    private ChatMessageRepository chatMessageRepository;

    @MockitoBean
    private ChatReadCountPublisher publisher;

    private ChatNativeSql sql;

    @Autowired
    void setUpSql(EntityManager entityManager, PlatformTransactionManager transactionManager) {
        sql = ChatNativeSql.of(entityManager, transactionManager);
    }

    @BeforeEach
    void setUp() {
        ChatUserSeeder.seedDefaultUsers(chatUserUseCase);
        chatUserUseCase.createIfAbsent(activeUser(GUEST_A, "게스트A", Role.INFLUENCER));
        chatUserUseCase.createIfAbsent(activeUser(GUEST_B, "게스트B", Role.INFLUENCER));
        recruits.reset();
        recruits.putRecruit(RECRUIT_ID, HOST_ID, "주말 합방 모집", "RECRUITING");
        recruits.addApplicants(RECRUIT_ID, GUEST_A, GUEST_B);
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("test", null, "ROLE_INFLUENCER"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ===== 1:1 방 =====

    @Test
    void 일대일_방은_보낸_사람을_빼고_상대가_읽기_전이면_1_읽으면_0이다() {
        Long roomId = chatRoomUseCase.createOrGet(HOST_ID, GUEST_ID).chatRoomId();
        chatMessageUseCase.send(roomId, HOST_ID, "첫 번째", null);
        chatMessageUseCase.send(roomId, HOST_ID, "두 번째", null);
        chatMessageUseCase.send(roomId, GUEST_ID, "답장", null);

        // 보는 회원과 관계없이 같은 값이다
        assertThat(counts(roomId, HOST_ID)).containsExactly(entry(3L, 1), entry(2L, 1), entry(1L, 1));
        assertThat(counts(roomId, GUEST_ID)).containsExactly(entry(3L, 1), entry(2L, 1), entry(1L, 1));

        chatReadService.markRead(roomId, GUEST_ID, 1, null);

        assertThat(counts(roomId, HOST_ID)).containsExactly(entry(3L, 1), entry(2L, 1), entry(1L, 0));
        verify(publisher, timeout(TIMEOUT_MILLIS)).publish(new ReadChangedFanout(roomId, 0));
    }

    @Test
    void 일대일_방에서_상대가_나가면_내_메시지는_0이다() {
        Long roomId = chatRoomUseCase.createOrGet(HOST_ID, GUEST_ID).chatRoomId();
        chatMessageUseCase.send(roomId, HOST_ID, "안녕하세요", null);

        chatRoomUseCase.leave(roomId, GUEST_ID, null);

        ChatMessagePageResponse page = chatMessageSupporter.getMessages(roomId, HOST_ID, null, 30);
        assertThat(page.messages()).extracting(ChatMessageResponse::messageType, ChatMessageResponse::unreadMemberCount)
                .containsExactly(tuple(ChatMessageType.SYSTEM, null), tuple(ChatMessageType.TEXT, 0));
        verify(publisher, timeout(TIMEOUT_MILLIS)).publish(argThat(signal -> signal.roomId().equals(roomId)
                && signal.recipientSetVersion() > 0));
    }

    @Test
    void 한_회원이_여러_기기에서_읽어도_한_명으로_한_번만_줄어든다() {
        Long roomId = chatRoomUseCase.createOrGet(HOST_ID, GUEST_ID).chatRoomId();
        chatMessageUseCase.send(roomId, HOST_ID, "첫 번째", null);
        chatMessageUseCase.send(roomId, HOST_ID, "두 번째", null);

        // 두 기기가 서로 다른 위치를 보낸다. 같은 값·낮은 값은 바꾸지 않고 신호도 내지 않는다
        chatReadService.markRead(roomId, GUEST_ID, 2, null);
        chatReadService.markRead(roomId, GUEST_ID, 1, null);
        chatReadService.markRead(roomId, GUEST_ID, 2, null);

        assertThat(counts(roomId, HOST_ID)).containsExactly(entry(2L, 0), entry(1L, 0));
        verify(publisher, timeout(TIMEOUT_MILLIS)).publish(any());
        verify(publisher, after(300).times(1)).publish(any());
    }

    // ===== 단체방 =====

    @Test
    void 단체방은_늦게_초대된_회원을_이전_메시지에_넣지_않고_보낸_사람을_뺀다() {
        Long roomId = createGroup();
        invite(roomId, GUEST_A);
        long m1 = send(roomId, HOST_ID, "A만 볼 수 있다");
        invite(roomId, GUEST_B);
        long m2 = send(roomId, HOST_ID, "둘 다 본다");
        long m3 = send(roomId, GUEST_A, "A가 보냄");

        Map<Long, Integer> counts = counts(roomId, HOST_ID);
        assertThat(counts).containsEntry(m1, 1).containsEntry(m2, 2).containsEntry(m3, 2);
        // 시스템 안내(초대)는 null이고 버전도 null이다
        assertThat(chatMessageSupporter.getMessages(roomId, GUEST_B, null, 30).messages())
                .filteredOn(message -> message.messageType() == ChatMessageType.SYSTEM)
                .isNotEmpty()
                .allSatisfy(message -> {
                    assertThat(message.unreadMemberCount()).isNull();
                    assertThat(message.readCountVersion()).isNull();
                });
    }

    @Test
    void 읽음_강퇴_나가기로만_줄고_재초대해도_이전_메시지의_값은_늘지_않는다() {
        Long roomId = createGroup();
        invite(roomId, GUEST_A, GUEST_B);
        long m1 = send(roomId, HOST_ID, "첫 번째");
        long m2 = send(roomId, GUEST_A, "두 번째");
        Map<Long, Integer> before = counts(roomId, HOST_ID);
        assertThat(before).containsEntry(m1, 2).containsEntry(m2, 2);

        chatReadService.markRead(roomId, GUEST_B, m2, null);
        Map<Long, Integer> afterRead = counts(roomId, HOST_ID);
        assertThat(afterRead).containsEntry(m1, 1).containsEntry(m2, 1);

        chatGroupRoomFacade.kick(roomId, HOST_ID, GUEST_A, 1);
        Map<Long, Integer> afterKick = counts(roomId, HOST_ID);
        assertThat(afterKick).containsEntry(m1, 0).containsEntry(m2, 1);

        // 재초대된 A는 새 가시 범위라 이전 메시지에 다시 들지 않는다
        invite(roomId, GUEST_A);
        Map<Long, Integer> afterRejoin = counts(roomId, HOST_ID);
        assertThat(afterRejoin).containsEntry(m1, 0).containsEntry(m2, 1);

        chatRoomUseCase.leave(roomId, GUEST_B, null);
        Map<Long, Integer> afterLeave = counts(roomId, HOST_ID);
        assertThat(afterLeave).containsEntry(m1, 0).containsEntry(m2, 1);

        for (Long messageId : List.of(m1, m2)) {
            assertThat(List.of(before, afterRead, afterKick, afterRejoin, afterLeave))
                    .extracting(snapshot -> snapshot.get(messageId))
                    .as("메시지 %d의 값은 줄기만 한다", messageId)
                    .isSortedAccordingTo((a, b) -> Integer.compare(b, a));
        }
    }

    @Test
    void 읽기_전용_방에서도_읽으면_값이_줄고_신호를_낸다() {
        Long roomId = createGroup();
        invite(roomId, GUEST_A);
        long m1 = send(roomId, HOST_ID, "폐쇄 전");
        chatGroupRoomFacade.close(roomId, HOST_ID);
        // 초대·폐쇄 신호가 다 나간 뒤에 비운다(발행은 실행기에서 따로 돈다)
        verify(publisher, timeout(TIMEOUT_MILLIS).times(2)).publish(any());
        clearInvocations(publisher);

        chatReadService.markRead(roomId, GUEST_A, Long.MAX_VALUE, null);

        assertThat(counts(roomId, HOST_ID)).containsEntry(m1, 0);
        verify(publisher, timeout(TIMEOUT_MILLIS)).publish(new ReadChangedFanout(roomId, 0));
    }

    @Test
    void 초대_강퇴_나가기_폐쇄는_커밋된_뒤_그_변경의_수신자_집합_버전으로_신호를_낸다() {
        Long roomId = createGroup();
        invite(roomId, GUEST_A, GUEST_B);
        chatGroupRoomFacade.kick(roomId, HOST_ID, GUEST_A, 1);
        chatRoomUseCase.leave(roomId, GUEST_B, null);
        chatGroupRoomFacade.close(roomId, HOST_ID);

        ArgumentCaptor<ReadChangedFanout> signals = ArgumentCaptor.forClass(ReadChangedFanout.class);
        verify(publisher, timeout(TIMEOUT_MILLIS).times(4)).publish(signals.capture());
        // 생성(1) → 초대(2) → 강퇴(3) → 나가기(4) → 폐쇄(참여는 그대로라 0)
        assertThat(signals.getAllValues()).containsExactly(new ReadChangedFanout(roomId, 2),
                new ReadChangedFanout(roomId, 3), new ReadChangedFanout(roomId, 4), new ReadChangedFanout(roomId, 0));
    }

    @Test
    void 실패한_변경은_신호를_내지_않는다() {
        Long roomId = createGroup();
        invite(roomId, GUEST_A);
        verify(publisher, timeout(TIMEOUT_MILLIS)).publish(any());
        clearInvocations(publisher);

        // 호스트는 강퇴할 수 없고, 옛 세대의 강퇴는 바꾸지 않는다. 둘 다 롤백된다(커밋 뒤 신호는 단위 시험이 따로 확인한다)
        assertThatThrownBy(() -> chatGroupRoomFacade.kick(roomId, HOST_ID, HOST_ID, 1))
                .isInstanceOf(ChatException.class);
        assertThatThrownBy(() -> chatGroupRoomFacade.kick(roomId, HOST_ID, GUEST_A, 9))
                .isInstanceOf(ChatException.class);

        verify(publisher, after(300).never()).publish(any());
    }

    // ===== 집계 비용과 버전 =====

    @Test
    void 페이지마다_그_페이지의_일반_메시지만_집계_문장_하나로_세고_버전을_싣는다() {
        Long roomId = createGroup();
        invite(roomId, GUEST_A, GUEST_B);
        sendForId(roomId, HOST_ID, "1");
        long m2 = sendForId(roomId, HOST_ID, "2");
        long m3 = sendForId(roomId, HOST_ID, "3");
        clearInvocations(chatMessageRepository);

        ChatMessagePageResponse first = chatMessageSupporter.getMessages(roomId, HOST_ID, null, 2);

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Collection<Long>> ids = (ArgumentCaptor) ArgumentCaptor.forClass(Collection.class);
        verify(chatMessageRepository, times(1)).countUnreadMembers(ids.capture());
        verify(chatMessageRepository, never()).currentReadCountVersion();
        assertThat(ids.getValue()).containsExactlyInAnyOrder(m3, m2);
        assertThat(first.messages()).extracting(ChatMessageResponse::unreadMemberCount).containsExactly(2, 2);
        assertThat(first.messages()).extracting(ChatMessageResponse::readCountVersion)
                .containsOnly(first.readCountVersion());
        assertThat(first.readCountVersion()).isPositive();

        chatReadService.markRead(roomId, GUEST_A, first.messages().getFirst().roomSequence(), null);
        ChatMessagePageResponse again = chatMessageSupporter.getMessages(roomId, HOST_ID, null, 2);
        assertThat(again.readCountVersion()).isGreaterThan(first.readCountVersion());
        assertThat(again.messages()).extracting(ChatMessageResponse::unreadMemberCount).containsExactly(1, 1);
    }

    @Test
    void 일반_메시지가_없는_페이지는_세지_않고_지금_시각을_버전으로_싣는다() {
        Long roomId = createGroup();
        invite(roomId, GUEST_A);
        clearInvocations(chatMessageRepository);

        ChatMessagePageResponse page = chatMessageSupporter.getMessages(roomId, GUEST_A, null, 30);

        assertThat(page.messages()).isNotEmpty()
                .allSatisfy(message -> assertThat(message.unreadMemberCount()).isNull());
        assertThat(page.readCountVersion()).isPositive();
        verify(chatMessageRepository, never()).countUnreadMembers(any());
        verify(chatMessageRepository, times(1)).currentReadCountVersion();
    }

    @Test
    void 최근_구간_집계는_마지막_순번부터_구간_크기만큼의_일반_메시지를_0을_포함해_센다() {
        Long roomId = createGroup();
        invite(roomId, GUEST_A);
        send(roomId, HOST_ID, "1");
        long m2 = send(roomId, GUEST_A, "2");
        long lastSequence = send(roomId, HOST_ID, "3");
        chatReadService.markRead(roomId, GUEST_A, lastSequence - 1, null);

        RecentCounts counts = chatUnreadMemberCounter.countRecent(roomId, 2, LocalDateTime.now()).orElseThrow();

        assertThat(counts.toSequence()).isEqualTo(lastSequence);
        assertThat(counts.fromSequence()).isEqualTo(lastSequence - 1);
        // 구간 [마지막-1, 마지막]: A가 보낸 메시지는 호스트가 읽지 않았고(1), 마지막은 A가 읽지 않았다(1). 첫 메시지는 구간 밖이다
        assertThat(m2).isEqualTo(lastSequence - 1);
        assertThat(counts.bySequence()).containsOnly(entry(m2, 1), entry(lastSequence, 1));
        assertThat(counts.version()).isPositive();
        // 같은 스냅샷의 방 현재 수신자 집합 버전: 생성(1) → 초대(2)
        assertThat(counts.recipientSetVersion()).isEqualTo(2);
        assertThat(chatUnreadMemberCounter.countRecent(987_654L, 100, LocalDateTime.now())).isEmpty();
    }

    @Test
    void 최근_구간_집계는_조회_만료된_방과_종료된_일대일_방을_세지_않는다() {
        Long groupId = createGroup();
        invite(groupId, GUEST_A);
        send(groupId, HOST_ID, "폐쇄 전");
        chatGroupRoomFacade.close(groupId, HOST_ID);
        LocalDateTime expiresAt = LocalDateTime.of(2026, 12, 1, 0, 0);
        sql.update("update chats.chat_room set history_expires_at = :at where id = :id",
                Map.of("at", expiresAt, "id", groupId));
        Long directId = chatRoomUseCase.createOrGet(HOST_ID, GUEST_ID).chatRoomId();
        chatRoomUseCase.leave(directId, GUEST_ID, null);
        chatRoomUseCase.leave(directId, HOST_ID, null);

        // 읽기 전용 방은 만료 시각 전까지 세고, 그 시각부터(같은 시각 포함) 세지 않는다(ChatRoom.isViewable과 같다)
        assertThat(chatUnreadMemberCounter.countRecent(groupId, 100, expiresAt.minusNanos(1000))).isPresent();
        assertThat(chatUnreadMemberCounter.countRecent(groupId, 100, expiresAt)).isEmpty();
        assertThat(chatUnreadMemberCounter.countRecent(directId, 100, LocalDateTime.now())).isEmpty();
    }

    /** 일반 메시지의 순번 → 안 읽은 사람 수(최신순) */
    private Map<Long, Integer> counts(Long roomId, Long viewerId) {
        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (ChatMessageResponse message : chatMessageSupporter.getMessages(roomId, viewerId, null, 100).messages()) {
            if (message.messageType() == ChatMessageType.TEXT) {
                counts.put(message.roomSequence(), message.unreadMemberCount());
            }
        }
        return counts;
    }

    private Long createGroup() {
        return chatGroupRoomFacade.createRoom(HOST_ID, RECRUIT_ID).room().chatRoomId();
    }

    private void invite(Long roomId, Long... userIds) {
        chatGroupRoomFacade.invite(roomId, HOST_ID, UUID.randomUUID(), List.of(userIds));
    }

    /** @return 보낸 메시지의 순번 */
    private long send(Long roomId, Long senderId, String content) {
        return chatMessageUseCase.send(roomId, senderId, content, null).message().roomSequence();
    }

    /** @return 보낸 메시지의 ID */
    private long sendForId(Long roomId, Long senderId, String content) {
        return chatMessageUseCase.send(roomId, senderId, content, null).message().messageId();
    }
}
