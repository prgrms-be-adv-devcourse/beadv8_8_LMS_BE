package com.hapbang.chat.app;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.hapbang.chat.app.dto.ChatMessageHistoryResponse;
import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.app.dto.ChatRoomCreateResult;
import com.hapbang.chat.app.dto.ChatRoomHistoryResponse;
import com.hapbang.chat.app.dto.ChatRoomLeaveResult;
import com.hapbang.chat.app.dto.ChatRoomLogResponse;
import com.hapbang.chat.app.dto.ChatRoomMemberResponse;
import com.hapbang.chat.app.dto.ChatRoomResponse;
import com.hapbang.chat.app.exception.ChatErrorCode;
import com.hapbang.chat.app.exception.ChatException;
import com.hapbang.chat.domain.ChatMessageType;
import com.hapbang.chat.domain.ChatRoom;
import com.hapbang.chat.domain.ChatRoomEventType;
import com.hapbang.chat.domain.ChatRoomLog;
import com.hapbang.chat.domain.ChatRoomMember;
import com.hapbang.chat.domain.ChatRoomStatus;
import com.hapbang.chat.fixture.ChatUserSeeder;
import com.hapbang.chat.out.ChatRoomLogRepository;
import com.hapbang.chat.out.ChatRoomMemberRepository;
import com.hapbang.chat.out.ChatRoomRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.testsupport.IntegrationTest;

@IntegrationTest
class ChatRoomUseCaseTest {

    @Autowired
    private ChatRoomUseCase chatRoomUseCase;

    @Autowired
    private ChatRoomSupporter chatRoomSupporter;

    @Autowired
    private ChatMessageSupporter chatMessageSupporter;

    @Autowired
    private ChatMessageUseCase chatMessageUseCase;

    @Autowired
    private ChatUserUseCase chatUserUseCase;

    @Autowired
    private ChatRoomRepository chatRoomRepository;

    @Autowired
    private ChatRoomLogRepository chatRoomLogRepository;

    @Autowired
    private ChatRoomMemberRepository chatRoomMemberRepository;

    @BeforeEach
    void setUp() {
        ChatUserSeeder.seedDefaultUsers(chatUserUseCase);
    }

    @Test
    void 인플루언서끼리_채팅방을_만들고_생성과_입장을_기록한다() {
        ChatRoomCreateResult result = chatRoomUseCase.createOrGet(HOST_ID, GUEST_ID);

        assertThat(result.created()).isTrue();
        assertThat(result.room().status()).isEqualTo(ChatRoomStatus.ACTIVE);
        assertThat(result.room().members())
                .extracting(ChatRoomMemberResponse::userId, ChatRoomMemberResponse::nickname,
                        ChatRoomMemberResponse::active)
                .containsExactly(tuple(HOST_ID, "호스트", true), tuple(GUEST_ID, "게스트", true));
        assertThat(eventsOf(result.room().chatRoomId()))
                .containsExactly(ChatRoomEventType.ROOM_CREATED, ChatRoomEventType.MEMBER_JOINED,
                        ChatRoomEventType.MEMBER_JOINED);
    }

    @Test
    void 두_사람의_종료되지_않은_방이_있으면_재사용한다() {
        Long chatRoomId = createRoom();

        ChatRoomCreateResult result = chatRoomUseCase.createOrGet(GUEST_ID, HOST_ID);

        assertThat(result.created()).isFalse();
        assertThat(result.room().chatRoomId()).isEqualTo(chatRoomId);
        assertThat(chatRoomRepository.count()).isEqualTo(1);
    }

    @Test
    void 다른_사람과는_새_방을_만든다() {
        Long first = createRoom();

        Long second = chatRoomUseCase.createOrGet(HOST_ID, OTHER_INFLUENCER_ID).room().chatRoomId();

        assertThat(second).isNotEqualTo(first);
    }

    // 수용 테스트 2: A 퇴장 후 A는 조회할 수 없고, B는 이전 메시지와 퇴장 안내를 본다.
    @Test
    void 한_명이_나가면_나간_사람은_조회할_수_없고_남은_사람은_기존_기록과_퇴장_안내를_본다() {
        Long chatRoomId = createRoom();
        chatMessageUseCase.send(chatRoomId, HOST_ID, "안녕하세요", null);
        chatMessageUseCase.send(chatRoomId, GUEST_ID, "반갑습니다", null);

        ChatRoomLeaveResult result = chatRoomUseCase.leave(chatRoomId, GUEST_ID, null);

        assertThat(result.left()).isTrue();
        assertThat(result.roomEnded()).isFalse();
        assertThat(result.remainingUserIds()).containsExactly(HOST_ID);
        assertThat(result.leftNotice().messageType()).isEqualTo(ChatMessageType.SYSTEM);
        assertThat(result.leftNotice().content()).isEqualTo("게스트님이 나갔습니다.");
        assertThat(contentsSeenBy(chatRoomId, HOST_ID))
                .containsExactly("게스트님이 나갔습니다.", "반갑습니다", "안녕하세요");
        assertThatThrownBy(() -> chatMessageSupporter.getMessages(chatRoomId, GUEST_ID, null, 30))
                .isInstanceOf(ChatException.class)
                .extracting("errorCode").isEqualTo(ChatErrorCode.NOT_CHAT_ROOM_MEMBER);
        assertThat(chatRoomRepository.findById(chatRoomId).orElseThrow().getStatus())
                .isEqualTo(ChatRoomStatus.ACTIVE);
    }

    // 수용 테스트 3: B가 남은 상태에서 다시 시작하면 같은 방, B 기록 유지, A는 빈 기록에서 시작한다.
    @Test
    void 남은_사람이_있는_방에서_다시_시작하면_같은_방에_재입장하고_재입장한_사람만_빈_기록으로_시작한다() {
        Long chatRoomId = createRoom();
        chatMessageUseCase.send(chatRoomId, HOST_ID, "안녕하세요", null);
        chatRoomUseCase.leave(chatRoomId, GUEST_ID, null);
        List<String> hostBefore = contentsSeenBy(chatRoomId, HOST_ID);

        ChatRoomCreateResult result = chatRoomUseCase.createOrGet(HOST_ID, GUEST_ID);

        assertThat(result.created()).isFalse();
        assertThat(result.room().chatRoomId()).isEqualTo(chatRoomId);
        assertThat(result.room().members()).allMatch(ChatRoomMemberResponse::active);
        assertThat(result.room().members())
                .filteredOn(member -> member.userId().equals(GUEST_ID))
                .extracting(ChatRoomMemberResponse::participationVersion)
                .containsExactly(2);
        assertThat(contentsSeenBy(chatRoomId, HOST_ID)).first().isEqualTo("게스트님이 다시 들어왔습니다.");
        assertThat(contentsSeenBy(chatRoomId, HOST_ID)).containsSubsequence(hostBefore);
        assertThat(contentsSeenBy(chatRoomId, GUEST_ID)).isEmpty();
        assertThat(chatRoomLogRepository.findByChatRoom_IdOrderByIdAsc(chatRoomId))
                .extracting(ChatRoomLog::getEventType, ChatRoomLog::getActorUserId)
                .endsWith(tuple(ChatRoomEventType.MEMBER_LEFT, GUEST_ID),
                        tuple(ChatRoomEventType.MEMBER_REJOINED, GUEST_ID));
    }

    @Test
    void 재입장하면_남아_있던_사람에게만_보이는_재입장_안내를_남긴다() {
        Long chatRoomId = createRoom();
        chatRoomUseCase.leave(chatRoomId, GUEST_ID, null);

        ChatRoomCreateResult result = chatRoomUseCase.createOrGet(GUEST_ID, HOST_ID);

        assertThat(result.rejoinNotices()).singleElement().satisfies(notice -> {
            assertThat(notice.messageType()).isEqualTo(ChatMessageType.SYSTEM);
            assertThat(notice.senderUserId()).isEqualTo(GUEST_ID);
            assertThat(notice.content()).isEqualTo("게스트님이 다시 들어왔습니다.");
        });
        assertThat(result.remainingUserIds()).containsExactly(HOST_ID);
        assertThat(contentsSeenBy(chatRoomId, HOST_ID)).first().isEqualTo("게스트님이 다시 들어왔습니다.");
        assertThat(contentsSeenBy(chatRoomId, GUEST_ID)).isEmpty();
        assertThat(result.room().lastMessage()).isNull();
        assertThat(result.room().unreadCount()).isZero();
        assertThat(chatRoomSupporter.getMyRooms(HOST_ID)).singleElement()
                .extracting(ChatRoomResponse::unreadCount).isEqualTo(2L);
    }

    @Test
    void 둘_다_참여_중인_방을_다시_시작하면_재입장_안내를_남기지_않는다() {
        Long chatRoomId = createRoom();

        ChatRoomCreateResult result = chatRoomUseCase.createOrGet(HOST_ID, GUEST_ID);

        assertThat(result.rejoinNotices()).isEmpty();
        assertThat(result.remainingUserIds()).isEmpty();
        assertThat(contentsSeenBy(chatRoomId, HOST_ID)).isEmpty();
    }

    @Test
    void 나간_사람이_직접_다시_시작해도_같은_방에_재입장한다() {
        Long chatRoomId = createRoom();
        chatRoomUseCase.leave(chatRoomId, GUEST_ID, null);

        ChatRoomCreateResult result = chatRoomUseCase.createOrGet(GUEST_ID, HOST_ID);

        assertThat(result.room().chatRoomId()).isEqualTo(chatRoomId);
        assertThat(isActiveMember(chatRoomId, GUEST_ID)).isTrue();
    }

    // 수용 테스트 5: 둘 다 나가면 방이 종료되고, 다음 대화는 새 방에서 빈 기록으로 시작한다.
    @Test
    void 두_사람_모두_나가면_방이_종료되고_다음_대화는_새_방에서_시작한다() {
        Long chatRoomId = createRoom();
        chatMessageUseCase.send(chatRoomId, HOST_ID, "안녕하세요", null);
        chatRoomUseCase.leave(chatRoomId, GUEST_ID, null);

        ChatRoomLeaveResult lastLeave = chatRoomUseCase.leave(chatRoomId, HOST_ID, null);

        assertThat(lastLeave.roomEnded()).isTrue();
        assertThat(lastLeave.remainingUserIds()).isEmpty();
        ChatRoom endedRoom = chatRoomRepository.findById(chatRoomId).orElseThrow();
        assertThat(endedRoom.getStatus()).isEqualTo(ChatRoomStatus.ENDED);
        assertThat(endedRoom.getDirectKey()).isNull();
        assertThat(eventsOf(chatRoomId)).endsWith(ChatRoomEventType.MEMBER_LEFT, ChatRoomEventType.ROOM_ENDED);
        for (Long userId : List.of(HOST_ID, GUEST_ID)) {
            assertThatThrownBy(() -> chatMessageSupporter.getMessages(chatRoomId, userId, null, 30))
                    .isInstanceOf(ChatException.class)
                    .extracting("errorCode").isEqualTo(ChatErrorCode.CHAT_ROOM_NOT_FOUND);
        }

        ChatRoomCreateResult next = chatRoomUseCase.createOrGet(GUEST_ID, HOST_ID);

        assertThat(next.created()).isTrue();
        assertThat(next.room().chatRoomId()).isNotEqualTo(chatRoomId);
        assertThat(contentsSeenBy(next.room().chatRoomId(), HOST_ID)).isEmpty();
        assertThat(contentsSeenBy(next.room().chatRoomId(), GUEST_ID)).isEmpty();
    }

    @Test
    void 이미_나간_뒤_같은_퇴장_요청은_아무것도_바꾸지_않는다() {
        Long chatRoomId = createRoom();
        chatRoomUseCase.leave(chatRoomId, GUEST_ID, null);
        long logCount = chatRoomLogRepository.count();

        ChatRoomLeaveResult retried = chatRoomUseCase.leave(chatRoomId, GUEST_ID, null);

        assertThat(retried.left()).isFalse();
        assertThat(chatRoomLogRepository.count()).isEqualTo(logCount);
        assertThat(contentsSeenBy(chatRoomId, HOST_ID)).containsExactly("게스트님이 나갔습니다.");
    }

    // 수용 테스트 6: 재입장 뒤 지연 도착한 옛 퇴장 요청은 새 참여를 끝내지 않는다.
    @Test
    void 재입장_뒤_지연_도착한_옛_세대의_퇴장_요청은_무시한다() {
        Long chatRoomId = createRoom();
        chatRoomUseCase.leave(chatRoomId, GUEST_ID, 1);
        chatRoomUseCase.createOrGet(HOST_ID, GUEST_ID);

        ChatRoomLeaveResult stale = chatRoomUseCase.leave(chatRoomId, GUEST_ID, 1);

        assertThat(stale.left()).isFalse();
        assertThat(isActiveMember(chatRoomId, GUEST_ID)).isTrue();
    }

    @Test
    void 종료된_방의_마지막_퇴장_요청을_다시_보내도_오류가_아니다() {
        Long chatRoomId = createRoom();
        chatRoomUseCase.leave(chatRoomId, GUEST_ID, null);
        chatRoomUseCase.leave(chatRoomId, HOST_ID, null);

        assertThat(chatRoomUseCase.leave(chatRoomId, HOST_ID, null).left()).isFalse();
    }

    @Test
    void 참여자가_아니면_방을_나갈_수_없다() {
        Long chatRoomId = createRoom();

        assertThatThrownBy(() -> chatRoomUseCase.leave(chatRoomId, OTHER_INFLUENCER_ID, null))
                .isInstanceOf(ChatException.class)
                .extracting("errorCode").isEqualTo(ChatErrorCode.NOT_CHAT_ROOM_MEMBER);
    }

    @Test
    void 내_목록에는_참여_중인_종료되지_않은_방만_나온다() {
        Long left = createRoom();
        Long active = chatRoomUseCase.createOrGet(HOST_ID, OTHER_INFLUENCER_ID).room().chatRoomId();
        Long ended = chatRoomUseCase.createOrGet(HOST_ID, ADMIN_ID).room().chatRoomId();
        chatRoomUseCase.leave(left, HOST_ID, null);
        chatRoomUseCase.leave(ended, HOST_ID, null);
        chatRoomUseCase.leave(ended, ADMIN_ID, null);

        assertThat(chatRoomSupporter.getMyRooms(HOST_ID)).extracting(ChatRoomResponse::chatRoomId)
                .containsExactly(active);
        assertThat(chatRoomSupporter.getMyRooms(GUEST_ID)).singleElement()
                .satisfies(room -> assertThat(room.members())
                        .extracting(ChatRoomMemberResponse::userId, ChatRoomMemberResponse::active)
                        .containsExactly(tuple(HOST_ID, false), tuple(GUEST_ID, true)));
    }

    @Test
    void 일반_유저는_채팅방_목록을_조회할_수_없다() {
        assertThatThrownBy(() -> chatRoomSupporter.getMyRooms(USER_ID))
                .isInstanceOf(ChatException.class)
                .extracting("errorCode").isEqualTo(ChatErrorCode.CHAT_NOT_ALLOWED);
    }

    @Test
    void 관리자도_채팅방을_만들_수_있다() {
        assertThat(chatRoomUseCase.createOrGet(ADMIN_ID, HOST_ID).created()).isTrue();
    }

    @Test
    void 일반_유저는_채팅방을_만들_수_없다() {
        assertThatThrownBy(() -> chatRoomUseCase.createOrGet(USER_ID, HOST_ID))
                .isInstanceOf(ChatException.class)
                .extracting("errorCode").isEqualTo(ChatErrorCode.CHAT_NOT_ALLOWED);
    }

    @Test
    void 일반_유저에게는_채팅을_걸_수_없다() {
        assertThatThrownBy(() -> chatRoomUseCase.createOrGet(HOST_ID, USER_ID))
                .isInstanceOf(ChatException.class)
                .extracting("errorCode").isEqualTo(ChatErrorCode.CHAT_NOT_ALLOWED);
    }

    @Test
    void 탈퇴한_회원과는_채팅방을_만들_수_없다() {
        chatUserUseCase.update(userDto(GUEST_ID, "게스트", Role.VERIFIED_INFLUENCER, UserStatus.WITHDRAWN));

        assertThatThrownBy(() -> chatRoomUseCase.createOrGet(HOST_ID, GUEST_ID))
                .isInstanceOf(ChatException.class)
                .extracting("errorCode").isEqualTo(ChatErrorCode.CHAT_NOT_ALLOWED);
    }

    @Test
    void 자기_자신과는_채팅방을_만들_수_없다() {
        assertThatThrownBy(() -> chatRoomUseCase.createOrGet(HOST_ID, HOST_ID))
                .isInstanceOf(ChatException.class)
                .extracting("errorCode").isEqualTo(ChatErrorCode.CANNOT_CHAT_WITH_SELF);
    }

    @Test
    void 복제된_회원_정보가_없으면_채팅방을_만들_수_없다() {
        assertThatThrownBy(() -> chatRoomUseCase.createOrGet(HOST_ID, UNKNOWN_ID))
                .isInstanceOf(ChatException.class)
                .extracting("errorCode").isEqualTo(ChatErrorCode.CHAT_USER_NOT_FOUND);
    }

    // 수용 테스트 8: 관리자는 종료된 방, 참여자별로 숨겨진 메시지, 퇴장 기록까지 본다.
    @Test
    void 관리자는_종료된_방과_숨겨진_메시지까지_기록을_조회한다() {
        Long chatRoomId = createRoom();
        chatMessageUseCase.send(chatRoomId, HOST_ID, "방송 일정 맞춰요", null);
        chatRoomUseCase.leave(chatRoomId, GUEST_ID, null);
        chatRoomUseCase.createOrGet(HOST_ID, GUEST_ID);
        chatRoomUseCase.leave(chatRoomId, HOST_ID, null);
        chatRoomUseCase.leave(chatRoomId, GUEST_ID, null);

        ChatRoomHistoryResponse history = chatRoomSupporter.getHistory(chatRoomId, ADMIN_ID, null, 100);

        assertThat(history.status()).isEqualTo(ChatRoomStatus.ENDED);
        assertThat(history.endedAt()).isNotNull();
        assertThat(history.messages())
                .extracting(ChatMessageHistoryResponse::roomSequence, ChatMessageHistoryResponse::content)
                .containsExactly(
                        tuple(1L, "방송 일정 맞춰요"),
                        tuple(2L, "게스트님이 나갔습니다."),
                        tuple(3L, "게스트님이 다시 들어왔습니다."),
                        tuple(4L, "호스트님이 나갔습니다."),
                        tuple(5L, "게스트님이 나갔습니다."));
        assertThat(history.logs()).extracting(ChatRoomLogResponse::eventType)
                .containsExactly(ChatRoomEventType.ROOM_CREATED, ChatRoomEventType.MEMBER_JOINED,
                        ChatRoomEventType.MEMBER_JOINED, ChatRoomEventType.MEMBER_LEFT,
                        ChatRoomEventType.MEMBER_REJOINED, ChatRoomEventType.MEMBER_LEFT,
                        ChatRoomEventType.MEMBER_LEFT, ChatRoomEventType.ROOM_ENDED);
    }

    @Test
    void 관리자가_아니면_기록을_조회할_수_없다() {
        Long chatRoomId = createRoom();

        assertThatThrownBy(() -> chatRoomSupporter.getHistory(chatRoomId, HOST_ID, null, 100))
                .isInstanceOf(ChatException.class)
                .extracting("errorCode").isEqualTo(ChatErrorCode.ADMIN_ONLY);
    }

    private Long createRoom() {
        return chatRoomUseCase.createOrGet(HOST_ID, GUEST_ID).room().chatRoomId();
    }

    private boolean isActiveMember(Long chatRoomId, Long userId) {
        return chatRoomMemberRepository.findByChatRoom_IdAndUserIdAndDeletedAtIsNull(chatRoomId, userId)
                .map(ChatRoomMember::isActive)
                .orElse(false);
    }

    private List<ChatRoomEventType> eventsOf(Long chatRoomId) {
        return chatRoomLogRepository.findByChatRoom_IdOrderByIdAsc(chatRoomId).stream()
                .map(ChatRoomLog::getEventType)
                .toList();
    }

    private List<String> contentsSeenBy(Long chatRoomId, Long userId) {
        return chatMessageSupporter.getMessages(chatRoomId, userId, null, 100).messages().stream()
                .map(ChatMessageResponse::content)
                .toList();
    }
}
