package com.hapbang.chat.app;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import com.hapbang.chat.app.ChatRecruitSourceException.Reason;
import com.hapbang.chat.app.dto.ChatGroupCloseResponse;
import com.hapbang.chat.app.dto.ChatGroupRoomsResponse;
import com.hapbang.chat.app.dto.ChatInviteCandidatePageResponse;
import com.hapbang.chat.app.dto.ChatInviteCandidateResponse;
import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.app.dto.ChatRoomCreateResult;
import com.hapbang.chat.app.dto.ChatRoomLeaveResult;
import com.hapbang.chat.app.dto.ChatRoomLogResponse;
import com.hapbang.chat.app.dto.ChatRoomResponse;
import com.hapbang.chat.app.exception.ChatErrorCode;
import com.hapbang.chat.app.exception.ChatException;
import com.hapbang.chat.domain.ChatGroupInviteFailure;
import com.hapbang.chat.domain.ChatGroupInviteOutcome;
import com.hapbang.chat.domain.ChatMessageType;
import com.hapbang.chat.domain.ChatRoom;
import com.hapbang.chat.domain.ChatRoomEventType;
import com.hapbang.chat.domain.ChatRoomMember;
import com.hapbang.chat.domain.ChatRoomStatus;
import com.hapbang.chat.domain.ChatRoomStatusReason;
import com.hapbang.chat.domain.ChatRoomType;
import com.hapbang.chat.domain.GroupMembersInvitedData;
import com.hapbang.chat.domain.NotificationKind;
import com.hapbang.chat.fixture.ChatNativeSql;
import com.hapbang.chat.fixture.ChatUserSeeder;
import com.hapbang.chat.out.ChatRoomMemberRepository;
import com.hapbang.chat.out.ChatRoomRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.IntegrationTest;

import jakarta.persistence.EntityManager;

/**
 * 모집용 단체방(설계 3장)의 생성·후보 검색·부분 초대·강퇴·나가기·폐쇄·조회 만료를 실제 DB와 출연 앱의 메모리 구현으로 검증한다.
 * 정원 3명·한 요청 4명으로 줄여 시험한다(운영 기본값은 코드에 있다).
 */
@IntegrationTest
@Import(ChatGroupTestConfig.class)
@TestPropertySource(properties = {
        "hapbang.chat.group.max-guest-members=3",
        "hapbang.chat.group.invite-max-users=4"
})
class ChatGroupRoomFacadeTest {

    private static final Long RECRUIT_ID = 70L;
    private static final Long OTHER_RECRUIT_ID = 71L;
    private static final Long NOT_APPLICANT_ID = 99L;
    private static final Long GUEST_A = 11L;
    private static final Long GUEST_B = 12L;
    private static final Long GUEST_C = 13L;
    private static final Long GUEST_D = 14L;

    /** Facade의 @PreAuthorize를 통과할 로그인 상태. 스레드마다 따로라 작업 스레드에도 넣는다 */
    private static final Authentication INFLUENCER = new TestingAuthenticationToken("test", null, "ROLE_INFLUENCER");

    @Autowired
    private ChatGroupRoomFacade chatGroupRoomFacade;

    @Autowired
    private ChatRoomFacade chatRoomFacade;

    @Autowired
    private ChatRoomSupporter chatRoomSupporter;

    @Autowired
    private ChatMessageUseCase chatMessageUseCase;

    @Autowired
    private ChatMessageSupporter chatMessageSupporter;

    @Autowired
    private ChatReceiptService chatReceiptService;

    @Autowired
    private ChatRecipientStateReader chatRecipientStateReader;

    @Autowired
    private ChatUserUseCase chatUserUseCase;

    @Autowired
    private ChatRoomRepository chatRoomRepository;

    @Autowired
    private ChatRoomMemberRepository chatRoomMemberRepository;

    @Autowired
    private InMemoryChatRecruitSource recruits;

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
        chatUserUseCase.createIfAbsent(activeUser(GUEST_C, "다른닉", Role.VERIFIED_INFLUENCER));
        chatUserUseCase.createIfAbsent(activeUser(GUEST_D, "게스트D", Role.INFLUENCER));
        recruits.reset();
        recruits.putRecruit(RECRUIT_ID, HOST_ID, "주말 합방 모집", "RECRUITING");
        // 지원 상태와 관계없이 지원 이력이 있는 회원 전부다. USER_ID는 지원했지만 채팅할 수 없는 회원이다
        recruits.addApplicants(RECRUIT_ID, GUEST_ID, OTHER_INFLUENCER_ID, USER_ID, GUEST_A, GUEST_B, GUEST_C, GUEST_D);
        SecurityContextHolder.getContext().setAuthentication(INFLUENCER);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ===== 생성 =====

    @Test
    void 모집_중인_모집글의_호스트가_만들면_호스트가_첫_참여자이고_제목과_정원은_서버가_정한다() {
        ChatRoomCreateResult result = chatGroupRoomFacade.createRoom(HOST_ID, RECRUIT_ID);

        ChatRoomResponse room = result.room();
        assertThat(result.created()).isTrue();
        assertThat(room.roomType()).isEqualTo(ChatRoomType.GROUP);
        assertThat(room.status()).isEqualTo(ChatRoomStatus.ACTIVE);
        assertThat(room.members()).isEmpty();
        assertThat(room.group().recruitId()).isEqualTo(RECRUIT_ID);
        assertThat(room.group().hostUserId()).isEqualTo(HOST_ID);
        assertThat(room.group().title()).isEqualTo("주말 합방 모집");
        assertThat(room.group().maxGuestMembers()).isEqualTo(3);
        assertThat(room.group().memberCount()).isEqualTo(1);
        assertThat(room.group().myParticipationVersion()).isEqualTo(1);
        assertThat(room.group().readOnlyAt()).isNull();
        assertThat(logsOf(room.chatRoomId())).extracting(ChatRoomLogResponse::eventType)
                .containsExactly(ChatRoomEventType.ROOM_CREATED, ChatRoomEventType.MEMBER_JOINED);
    }

    @Test
    void 모집글의_호스트가_아니면_만들_수_없다() {
        assertError(() -> chatGroupRoomFacade.createRoom(GUEST_ID, RECRUIT_ID), ChatErrorCode.NOT_RECRUIT_HOST);
        assertThat(chatRoomRepository.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"HOLD_PENDING", "HOLD_FAILED", "CLOSED", "CANCELED"})
    void 모집_중이_아닌_모집글에는_만들_수_없다(String status) {
        recruits.putRecruit(RECRUIT_ID, HOST_ID, "주말 합방 모집", status);

        assertError(() -> chatGroupRoomFacade.createRoom(HOST_ID, RECRUIT_ID), ChatErrorCode.RECRUIT_NOT_OPEN);
        assertThat(chatRoomRepository.count()).isZero();
    }

    @Test
    void 없는_모집글이면_404() {
        assertError(() -> chatGroupRoomFacade.createRoom(HOST_ID, OTHER_RECRUIT_ID), ChatErrorCode.RECRUIT_NOT_FOUND);
    }

    @Test
    void 현재_방이_있으면_출연_앱을_부르지_않고_같은_방을_돌려준다() {
        Long roomId = createRoom();

        ChatRoomCreateResult again = chatGroupRoomFacade.createRoom(HOST_ID, RECRUIT_ID);

        assertThat(again.created()).isFalse();
        assertThat(again.room().chatRoomId()).isEqualTo(roomId);
        assertThat(recruits.recruitCalls()).isEqualTo(1);
        assertError(() -> chatGroupRoomFacade.createRoom(GUEST_ID, RECRUIT_ID), ChatErrorCode.NOT_RECRUIT_HOST);
        assertThat(chatRoomRepository.count()).isEqualTo(1);
    }

    @Test
    void 폐쇄한_뒤에는_새_방을_만들고_이전_방은_읽기_전용으로_남으며_새_생성도_모집_상태를_다시_본다() {
        Long oldRoomId = createRoom();
        chatGroupRoomFacade.close(oldRoomId, HOST_ID);

        ChatRoomCreateResult renewed = chatGroupRoomFacade.createRoom(HOST_ID, RECRUIT_ID);

        assertThat(renewed.created()).isTrue();
        assertThat(renewed.room().chatRoomId()).isNotEqualTo(oldRoomId);
        ChatGroupRoomsResponse rooms = chatGroupRoomFacade.getRooms(HOST_ID, RECRUIT_ID);
        assertThat(rooms.currentRoom().chatRoomId()).isEqualTo(renewed.room().chatRoomId());
        assertThat(rooms.previousRooms()).singleElement().satisfies(previous -> {
            assertThat(previous.chatRoomId()).isEqualTo(oldRoomId);
            assertThat(previous.status()).isEqualTo(ChatRoomStatus.READ_ONLY);
        });

        chatGroupRoomFacade.close(renewed.room().chatRoomId(), HOST_ID);
        recruits.putRecruit(RECRUIT_ID, HOST_ID, "주말 합방 모집", "CLOSED");
        assertError(() -> chatGroupRoomFacade.createRoom(HOST_ID, RECRUIT_ID), ChatErrorCode.RECRUIT_NOT_OPEN);
    }

    @Test
    void 같은_모집글에_동시에_만들어도_현재_방은_하나다() throws Exception {
        List<Long> roomIds = runConcurrently(8,
                index -> chatGroupRoomFacade.createRoom(HOST_ID, RECRUIT_ID).room().chatRoomId());

        assertThat(roomIds).hasSize(8).containsOnly(roomIds.getFirst());
        assertThat(chatRoomRepository.count()).isEqualTo(1);
    }

    @Test
    void 현재_방은_모집글마다_하나만_저장된다() {
        LocalDateTime now = LocalDateTime.now();
        chatRoomRepository.saveAndFlush(ChatRoom.createGroup(RECRUIT_ID, HOST_ID, "첫 방", 3, now));

        assertThatThrownBy(() -> chatRoomRepository.saveAndFlush(
                ChatRoom.createGroup(RECRUIT_ID, HOST_ID, "둘째 방", 3, now)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 출연_앱이_응답하지_않으면_생성은_503이고_아무것도_저장하지_않는다() {
        recruits.failWith(Reason.UNAVAILABLE);
        assertError(() -> chatGroupRoomFacade.createRoom(HOST_ID, RECRUIT_ID), ChatErrorCode.APPEARANCE_UNAVAILABLE);

        recruits.failWith(Reason.REJECTED);
        assertError(() -> chatGroupRoomFacade.createRoom(HOST_ID, RECRUIT_ID), ChatErrorCode.APPEARANCE_UNAVAILABLE);

        assertThat(chatRoomRepository.count()).isZero();
        assertThat(sql.single("select count(*) from chats.chat_room_log", Long.class)).isZero();
    }

    // ===== 초대 후보 =====

    @Test
    void 초대_후보는_지원_이력이_있는_참여_중이_아닌_회원이고_회원_ID_정확_일치_닉네임_부분_일치로_찾는다() {
        Long roomId = createRoom();
        invite(roomId, GUEST_ID);

        ChatInviteCandidatePageResponse all = candidates(roomId, null, null, 20);
        assertThat(all.candidates()).extracting(ChatInviteCandidateResponse::userId)
                .containsExactly(OTHER_INFLUENCER_ID, USER_ID, GUEST_A, GUEST_B, GUEST_C, GUEST_D);
        assertThat(all.candidates()).filteredOn(candidate -> candidate.userId().equals(USER_ID)).singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.invitable()).isFalse();
                    assertThat(candidate.reason()).isEqualTo(ChatGroupInviteFailure.CHAT_NOT_ALLOWED);
                });
        assertThat(all.nextCursor()).isNull();
        assertThat(all.remainingGuestSlots()).isEqualTo(2);
        // 지원자 목록을 키셋으로 끝까지 읽는다(메모리 구현의 한 페이지는 2명)
        assertThat(recruits.applicantCalls()).isGreaterThan(1);

        assertThat(candidates(roomId, "게스트", null, 20).candidates()).extracting(ChatInviteCandidateResponse::userId)
                .containsExactly(GUEST_A, GUEST_B, GUEST_D);
        assertThat(candidates(roomId, String.valueOf(GUEST_C), null, 20).candidates())
                .extracting(ChatInviteCandidateResponse::userId, ChatInviteCandidateResponse::nickname)
                .containsExactly(tuple(GUEST_C, "다른닉"));
        assertThat(candidates(roomId, "없는닉네임", null, 20).candidates()).isEmpty();
    }

    @Test
    void 초대_후보는_회원_ID_순서의_커서로_나눠_주고_모집글_제목_변경을_방에_반영한다() {
        Long roomId = createRoom();
        recruits.putRecruit(RECRUIT_ID, HOST_ID, "바뀐 제목", "CLOSED");

        ChatInviteCandidatePageResponse first = candidates(roomId, "게스트", null, 2);
        ChatInviteCandidatePageResponse second = candidates(roomId, "게스트", first.nextCursor(), 2);

        assertThat(first.candidates()).extracting(ChatInviteCandidateResponse::userId)
                .containsExactly(GUEST_ID, GUEST_A);
        assertThat(first.nextCursor()).isEqualTo(GUEST_A);
        assertThat(second.candidates()).extracting(ChatInviteCandidateResponse::userId)
                .containsExactly(GUEST_B, GUEST_D);
        assertThat(second.nextCursor()).isNull();
        // 모집 마감 뒤에도 기존 방의 초대는 유지한다. 제목은 모집글을 따른다
        assertThat(chatRoomFacade.getRoom(roomId, HOST_ID).group().title()).isEqualTo("바뀐 제목");
    }

    @Test
    void 초대_후보는_호스트만_찾는다() {
        Long roomId = createRoom();
        invite(roomId, GUEST_ID);

        assertError(() -> chatGroupRoomFacade.getInviteCandidates(roomId, GUEST_ID, null, null, 20),
                ChatErrorCode.NOT_GROUP_HOST);
        assertThat(recruits.applicantCalls()).isZero();
    }

    @Test
    void 출연_앱이_응답하지_않으면_후보_검색은_503이다() {
        Long roomId = createRoom();
        recruits.failWith(Reason.UNAVAILABLE);

        assertError(() -> candidates(roomId, null, null, 20), ChatErrorCode.APPEARANCE_UNAVAILABLE);
    }

    // ===== 초대 =====

    @Test
    void 여러_명을_초대하면_회원별로_결과를_주고_요청_순서대로_정원을_채운다() {
        Long roomId = createRoom();
        int setVersionBefore = currentSetVersion(roomId);

        List<ChatGroupInviteOutcome> first = invite(roomId, GUEST_ID, USER_ID, NOT_APPLICANT_ID, GUEST_ID, GUEST_A);

        assertThat(first).containsExactly(ChatGroupInviteOutcome.joined(GUEST_ID),
                ChatGroupInviteOutcome.failed(USER_ID, ChatGroupInviteFailure.CHAT_NOT_ALLOWED),
                ChatGroupInviteOutcome.failed(NOT_APPLICANT_ID, ChatGroupInviteFailure.NOT_APPLICANT),
                ChatGroupInviteOutcome.joined(GUEST_A));
        // 여러 명을 넣어도 수신자 집합은 요청마다 한 번 만든다
        assertThat(currentSetVersion(roomId)).isEqualTo(setVersionBefore + 1);

        List<ChatGroupInviteOutcome> second = invite(roomId, GUEST_B, GUEST_C, GUEST_D);

        assertThat(second).containsExactly(ChatGroupInviteOutcome.joined(GUEST_B),
                ChatGroupInviteOutcome.failed(GUEST_C, ChatGroupInviteFailure.ROOM_FULL),
                ChatGroupInviteOutcome.failed(GUEST_D, ChatGroupInviteFailure.ROOM_FULL));
        assertThat(invite(roomId, GUEST_ID, HOST_ID)).containsExactly(ChatGroupInviteOutcome.alreadyMember(GUEST_ID),
                ChatGroupInviteOutcome.alreadyMember(HOST_ID));
        // 이미 참여 중인 회원만 있으면 수신자 집합·안내를 새로 만들지 않는다
        assertThat(currentSetVersion(roomId)).isEqualTo(setVersionBefore + 2);

        ChatRoomResponse room = chatRoomFacade.getRoom(roomId, HOST_ID);
        assertThat(room.group().memberCount()).isEqualTo(4);
        assertThat(chatRoomFacade.getMembers(roomId, GUEST_ID, null, 10).members())
                .extracting(member -> member.userId())
                .containsExactly(HOST_ID, GUEST_ID, GUEST_A, GUEST_B);
        assertThat(logsOf(roomId)).filteredOn(log -> log.eventType() == ChatRoomEventType.MEMBER_INVITED)
                .extracting(ChatRoomLogResponse::actorUserId, ChatRoomLogResponse::targetUserId)
                .containsExactly(tuple(HOST_ID, GUEST_ID), tuple(HOST_ID, GUEST_A), tuple(HOST_ID, GUEST_B));
    }

    @Test
    void 초대_안내는_값만_남기고_초대된_회원도_본다() {
        Long roomId = createRoom();
        chatMessageUseCase.send(roomId, HOST_ID, "초대 전 메시지", null);

        invite(roomId, GUEST_ID, GUEST_A);

        List<ChatMessageResponse> seenByGuest = chatMessageSupporter.getMessages(roomId, GUEST_ID, null, 30).messages();
        assertThat(seenByGuest).singleElement().satisfies(notice -> {
            assertThat(notice.messageType()).isEqualTo(ChatMessageType.SYSTEM);
            assertThat(notice.content()).isEmpty();
            assertThat(notice.notificationKind()).isEqualTo(NotificationKind.GROUP_MEMBERS_INVITED);
            assertThat(notice.senderUserId()).isEqualTo(HOST_ID);
            assertThat(notice.groupMembersInvited()).isEqualTo(new GroupMembersInvitedData(1, HOST_ID,
                    List.of(GUEST_ID, GUEST_A)));
        });
        // 초대된 회원에게 안내는 안 읽은 메시지다
        assertThat(chatRoomFacade.getMyRooms(GUEST_ID)).singleElement()
                .satisfies(room -> assertThat(room.unreadCount()).isEqualTo(1));
    }

    @Test
    void 한_요청의_인원_상한을_넘으면_400이고_출연_앱을_부르지_않는다() {
        Long roomId = createRoom();

        assertError(() -> invite(roomId, GUEST_ID, GUEST_A, GUEST_B, GUEST_C, GUEST_D), ChatErrorCode.INVALID_REQUEST);
        // 상한은 중복을 뺀 수다
        assertThat(invite(roomId, GUEST_ID, GUEST_ID, GUEST_ID, GUEST_ID, GUEST_ID))
                .containsExactly(ChatGroupInviteOutcome.joined(GUEST_ID));
        assertThat(recruits.appliedCalls()).isEqualTo(1);
    }

    @Test
    void 같은_요청_ID의_재시도는_저장한_결과를_돌려주고_그_사이_내보낸_회원을_다시_넣지_않는다() {
        Long roomId = createRoom();
        UUID requestId = UUID.randomUUID();
        chatGroupRoomFacade.invite(roomId, HOST_ID, requestId, List.of(GUEST_ID));
        chatGroupRoomFacade.kick(roomId, HOST_ID, GUEST_ID, 1);
        int appliedCalls = recruits.appliedCalls();

        List<ChatGroupInviteOutcome> retried = chatGroupRoomFacade.invite(roomId, HOST_ID, requestId,
                List.of(GUEST_ID)).results();

        assertThat(retried).containsExactly(ChatGroupInviteOutcome.joined(GUEST_ID));
        assertThat(memberOf(roomId, GUEST_ID).isActive()).isFalse();
        assertThat(recruits.appliedCalls()).isEqualTo(appliedCalls);

        // 보존 기간이 지나면 같은 요청 ID도 새 요청으로 처리한다
        sql.update("update chats.chat_group_invite_request set recorded_at = recorded_at - interval '25 hours'");
        chatGroupRoomFacade.invite(roomId, HOST_ID, requestId, List.of(GUEST_ID));

        ChatRoomMember rejoined = memberOf(roomId, GUEST_ID);
        assertThat(rejoined.isActive()).isTrue();
        assertThat(rejoined.getParticipationVersion()).isEqualTo(2);
    }

    @Test
    void 나갔던_회원은_재초대로만_새_참여_세대로_들어오고_이전_메시지는_보이지_않는다() {
        Long roomId = createRoom();
        invite(roomId, GUEST_ID);
        chatMessageUseCase.send(roomId, HOST_ID, "퇴장 전 메시지", null);
        chatRoomFacade.leave(roomId, GUEST_ID, 1);

        invite(roomId, GUEST_ID);

        ChatRoomMember member = memberOf(roomId, GUEST_ID);
        assertThat(member.getParticipationVersion()).isEqualTo(2);
        assertThat(chatMessageSupporter.getMessages(roomId, GUEST_ID, null, 30).messages())
                .extracting(ChatMessageResponse::notificationKind)
                .containsExactly(NotificationKind.GROUP_MEMBERS_INVITED);
    }

    @Test
    void 출연_앱이_응답하지_않으면_초대는_503이고_아무것도_저장하지_않는다() {
        Long roomId = createRoom();
        recruits.failWith(Reason.UNAVAILABLE);

        assertError(() -> invite(roomId, GUEST_ID), ChatErrorCode.APPEARANCE_UNAVAILABLE);

        assertThat(memberRow(roomId, GUEST_ID)).isNull();
        assertThat(sql.single("select count(*) from chats.chat_group_invite_request", Long.class)).isZero();
        assertThat(sql.single("select count(*) from chats.chat_message", Long.class)).isZero();
    }

    @Test
    void 초대는_호스트만_한다() {
        Long roomId = createRoom();
        invite(roomId, GUEST_ID);

        assertError(() -> chatGroupRoomFacade.invite(roomId, GUEST_ID, UUID.randomUUID(), List.of(GUEST_A)),
                ChatErrorCode.NOT_GROUP_HOST);
    }

    // ===== 강퇴 =====

    @Test
    void 강퇴는_대상의_참여_세대를_확인하고_남은_참여자에게만_안내한다() {
        Long roomId = createRoom();
        invite(roomId, GUEST_ID, GUEST_A);

        chatGroupRoomFacade.kick(roomId, HOST_ID, GUEST_ID, 1);

        assertThat(memberOf(roomId, GUEST_ID).isActive()).isFalse();
        assertError(() -> chatMessageSupporter.getMessages(roomId, GUEST_ID, null, 30),
                ChatErrorCode.NOT_CHAT_ROOM_MEMBER);
        assertThat(chatMessageSupporter.getMessages(roomId, GUEST_A, null, 30).messages().getFirst())
                .satisfies(notice -> {
                    assertThat(notice.notificationKind()).isEqualTo(NotificationKind.GROUP_MEMBER_KICKED);
                    assertThat(notice.groupMemberKicked().kickedUserId()).isEqualTo(GUEST_ID);
                    assertThat(notice.groupMemberKicked().kickedByUserId()).isEqualTo(HOST_ID);
                });
        assertThat(logsOf(roomId)).filteredOn(log -> log.eventType() == ChatRoomEventType.MEMBER_KICKED)
                .extracting(ChatRoomLogResponse::actorUserId, ChatRoomLogResponse::targetUserId)
                .containsExactly(tuple(HOST_ID, GUEST_ID));

        // 같은 세대의 재시도는 바꾸지 않는다
        chatGroupRoomFacade.kick(roomId, HOST_ID, GUEST_ID, 1);
        // 재초대 뒤에 도착한 옛 세대의 강퇴는 새 참여를 끝내지 않는다
        invite(roomId, GUEST_ID);
        assertError(() -> chatGroupRoomFacade.kick(roomId, HOST_ID, GUEST_ID, 1), ChatErrorCode.STALE_PARTICIPATION);
        assertThat(memberOf(roomId, GUEST_ID).isActive()).isTrue();
    }

    @Test
    void 호스트는_내보낼_수_없고_참여한_적_없는_회원과_호스트가_아닌_요청은_거절한다() {
        Long roomId = createRoom();
        invite(roomId, GUEST_ID);

        assertError(() -> chatGroupRoomFacade.kick(roomId, HOST_ID, HOST_ID, 1), ChatErrorCode.CANNOT_KICK_HOST);
        assertError(() -> chatGroupRoomFacade.kick(roomId, HOST_ID, GUEST_A, 1), ChatErrorCode.KICK_TARGET_NOT_MEMBER);
        assertError(() -> chatGroupRoomFacade.kick(roomId, GUEST_ID, HOST_ID, 1), ChatErrorCode.NOT_GROUP_HOST);
    }

    // ===== 전송·나가기 =====

    @Test
    void 사용_중인_단체방은_호스트_혼자여도_보낼_수_있다() {
        Long roomId = createRoom();

        assertThat(chatMessageUseCase.send(roomId, HOST_ID, "혼자 공지", null).message().content())
                .isEqualTo("혼자 공지");
    }

    @Test
    void 호스트는_사용_중인_방을_나갈_수_없고_게스트가_나가면_방은_유지되고_나가기_안내가_남는다() {
        Long roomId = createRoom();
        invite(roomId, GUEST_ID);

        assertError(() -> chatRoomFacade.leave(roomId, HOST_ID, null), ChatErrorCode.HOST_CANNOT_LEAVE);

        ChatRoomLeaveResult left = chatRoomFacade.leave(roomId, GUEST_ID, null);

        assertThat(left.left()).isTrue();
        assertThat(left.roomEnded()).isFalse();
        assertThat(left.leftNotice().notificationKind()).isEqualTo(NotificationKind.GROUP_MEMBER_LEFT);
        assertThat(left.leftNotice().groupMemberLeft().leftUserId()).isEqualTo(GUEST_ID);
        assertThat(left.leftNotice().content()).isEmpty();
        assertThat(chatRoomRepository.findById(roomId).orElseThrow().getStatus()).isEqualTo(ChatRoomStatus.ACTIVE);
    }

    // ===== 폐쇄·조회 만료 =====

    @Test
    void 폐쇄하면_참여를_유지한_읽기_전용이_되고_조회_읽음_수신_확인만_된다() {
        Long roomId = createRoom();
        invite(roomId, GUEST_ID);
        long sent = chatMessageUseCase.send(roomId, GUEST_ID, "폐쇄 전 메시지", null).message().roomSequence();

        ChatGroupCloseResponse closed = chatGroupRoomFacade.close(roomId, HOST_ID);

        assertThat(closed.status()).isEqualTo(ChatRoomStatus.READ_ONLY);
        assertThat(closed.statusReason()).isEqualTo(ChatRoomStatusReason.CLOSED_BY_HOST);
        assertThat(closed.historyExpiresAt()).isEqualTo(closed.readOnlyAt().plusDays(30));
        assertThat(memberOf(roomId, GUEST_ID).isActive()).isTrue();
        // 이미 폐쇄한 방은 같은 상태를 돌려준다(DB 시각은 마이크로초까지라 가까운지로 본다)
        ChatGroupCloseResponse closedAgain = chatGroupRoomFacade.close(roomId, HOST_ID);
        assertThat(closedAgain.status()).isEqualTo(ChatRoomStatus.READ_ONLY);
        assertThat(closedAgain.historyExpiresAt()).isCloseTo(closed.historyExpiresAt(), within(1, ChronoUnit.MILLIS));

        assertError(() -> chatMessageUseCase.send(roomId, GUEST_ID, "폐쇄 뒤", null), ChatErrorCode.GROUP_ROOM_NOT_ACTIVE);
        assertError(() -> chatMessageUseCase.send(roomId, HOST_ID, "폐쇄 뒤", null), ChatErrorCode.GROUP_ROOM_NOT_ACTIVE);
        assertError(() -> invite(roomId, GUEST_A), ChatErrorCode.GROUP_ROOM_NOT_ACTIVE);
        assertError(() -> chatGroupRoomFacade.kick(roomId, HOST_ID, GUEST_ID, 1), ChatErrorCode.GROUP_ROOM_NOT_ACTIVE);
        assertError(() -> candidates(roomId, null, null, 20), ChatErrorCode.GROUP_ROOM_NOT_ACTIVE);

        List<ChatMessageResponse> history = chatMessageSupporter.getMessages(roomId, GUEST_ID, null, 30).messages();
        assertThat(history.getFirst().notificationKind()).isEqualTo(NotificationKind.GROUP_ROOM_CLOSED);
        assertThat(history.getFirst().groupRoomClosed().closedByUserId()).isEqualTo(HOST_ID);
        long lastSequence = history.getFirst().roomSequence();
        chatRoomFacade.markRead(roomId, GUEST_ID, lastSequence, 1);
        assertThat(chatReceiptService.markReceived(roomId, GUEST_ID, 1, sent).lastReceivedSequence()).isEqualTo(sent);
        assertThat(chatRecipientStateReader.checkSubscription(roomId, GUEST_ID).roomOpen()).isTrue();
        assertThat(chatRoomFacade.getMyRooms(GUEST_ID)).singleElement().satisfies(room -> {
            assertThat(room.status()).isEqualTo(ChatRoomStatus.READ_ONLY);
            assertThat(room.unreadCount()).isZero();
            assertThat(room.group().historyExpiresAt())
                    .isCloseTo(closed.historyExpiresAt(), within(1, ChronoUnit.MILLIS));
        });

        // 읽기 전용 방에서 나가면 안내를 남기지 않는다(폐쇄 안내가 마지막 메시지다). 폐쇄한 호스트도 나갈 수 있다
        assertThat(chatRoomFacade.leave(roomId, GUEST_ID, 1).leftNotice()).isNull();
        assertThat(chatRoomFacade.leave(roomId, HOST_ID, 1).left()).isTrue();
        assertThat(chatRoomRepository.findById(roomId).orElseThrow().getLastSequence()).isEqualTo(lastSequence);
    }

    @Test
    void 조회_만료_시각이_지나면_참여자는_볼_수_없고_관리자는_기록을_본다() {
        Long roomId = createRoom();
        invite(roomId, GUEST_ID);
        chatGroupRoomFacade.close(roomId, HOST_ID);
        sql.update("update chats.chat_room set history_expires_at = :at where id = :id",
                Map.of("at", LocalDateTime.now().minusSeconds(1), "id", roomId));

        assertError(() -> chatMessageSupporter.getMessages(roomId, GUEST_ID, null, 30),
                ChatErrorCode.CHAT_ROOM_NOT_FOUND);
        assertError(() -> chatRoomFacade.markRead(roomId, GUEST_ID, 1, 1), ChatErrorCode.CHAT_ROOM_NOT_FOUND);
        assertError(() -> chatReceiptService.markReceived(roomId, GUEST_ID, 1, 1), ChatErrorCode.CHAT_ROOM_NOT_FOUND);
        assertError(() -> chatRoomFacade.getRoom(roomId, GUEST_ID), ChatErrorCode.CHAT_ROOM_NOT_FOUND);
        assertError(() -> chatRoomFacade.getMembers(roomId, GUEST_ID, null, 10), ChatErrorCode.CHAT_ROOM_NOT_FOUND);
        assertError(() -> chatRoomFacade.leave(roomId, GUEST_ID, 1), ChatErrorCode.CHAT_ROOM_NOT_FOUND);
        assertThat(chatRoomFacade.getMyRooms(GUEST_ID)).isEmpty();
        assertThat(chatGroupRoomFacade.getRooms(GUEST_ID, RECRUIT_ID).previousRooms()).isEmpty();
        assertThat(chatRecipientStateReader.checkSubscription(roomId, GUEST_ID).roomOpen()).isFalse();

        var history = chatRoomSupporter.getHistory(roomId, ADMIN_ID, null, 50);
        assertThat(history.status()).isEqualTo(ChatRoomStatus.EXPIRED);
        assertThat(history.group().recruitId()).isEqualTo(RECRUIT_ID);
        assertThat(history.messages()).extracting(message -> message.notificationKind())
                .containsExactly(NotificationKind.GROUP_MEMBERS_INVITED, NotificationKind.GROUP_ROOM_CLOSED);
    }

    @Test
    void 일대일_방_응답은_참여자를_싣고_단체방_정보는_없다() {
        Long directRoomId = chatRoomFacade.createOrGet(HOST_ID, GUEST_ID).room().chatRoomId();
        createRoom();

        assertThat(chatRoomFacade.getMyRooms(HOST_ID)).hasSize(2)
                .filteredOn(room -> room.chatRoomId().equals(directRoomId)).singleElement()
                .satisfies(room -> {
                    assertThat(room.roomType()).isEqualTo(ChatRoomType.DIRECT);
                    assertThat(room.group()).isNull();
                    assertThat(room.members()).hasSize(2);
                });
    }

    private Long createRoom() {
        return chatGroupRoomFacade.createRoom(HOST_ID, RECRUIT_ID).room().chatRoomId();
    }

    private List<ChatGroupInviteOutcome> invite(Long roomId, Long... userIds) {
        return chatGroupRoomFacade.invite(roomId, HOST_ID, UUID.randomUUID(), List.of(userIds)).results();
    }

    private ChatInviteCandidatePageResponse candidates(Long roomId, String keyword, Long cursor, int size) {
        return chatGroupRoomFacade.getInviteCandidates(roomId, HOST_ID, keyword, cursor, size);
    }

    private ChatRoomMember memberOf(Long roomId, Long userId) {
        return chatRoomMemberRepository.findByChatRoom_IdAndUserIdAndDeletedAtIsNull(roomId, userId).orElseThrow();
    }

    private ChatRoomMember memberRow(Long roomId, Long userId) {
        return chatRoomMemberRepository.findByChatRoom_IdAndUserIdAndDeletedAtIsNull(roomId, userId).orElse(null);
    }

    private int currentSetVersion(Long roomId) {
        return sql.single("""
                select s.set_version from chats.chat_room r join chats.chat_recipient_set s
                    on s.id = r.current_recipient_set_id
                where r.id = :id
                """, Integer.class, Map.of("id", roomId));
    }

    private List<ChatRoomLogResponse> logsOf(Long roomId) {
        return chatRoomSupporter.getHistory(roomId, ADMIN_ID, null, 100).logs();
    }

    private static void assertError(Runnable call, ChatErrorCode errorCode) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ChatException.class)
                .extracting("errorCode").isEqualTo(errorCode);
    }

    private static <T> List<T> runConcurrently(int threads, IndexedTask<T> task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int index = i;
                Callable<T> callable = () -> {
                    SecurityContextHolder.getContext().setAuthentication(INFLUENCER);
                    try {
                        ready.countDown();
                        start.await();
                        return task.run(index);
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                };
                futures.add(executor.submit(callable));
            }
            ready.await(10, TimeUnit.SECONDS);
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface IndexedTask<T> {
        T run(int index) throws Exception;
    }
}
