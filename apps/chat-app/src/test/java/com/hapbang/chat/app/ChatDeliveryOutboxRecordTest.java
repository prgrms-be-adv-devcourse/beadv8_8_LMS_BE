package com.hapbang.chat.app;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;

import com.hapbang.chat.app.dto.ChatJobStream;
import com.hapbang.chat.app.dto.SentChatMessage;
import com.hapbang.chat.app.exception.ChatErrorCode;
import com.hapbang.chat.app.exception.ChatException;
import com.hapbang.chat.app.port.ChatMessageRepository;
import com.hapbang.chat.app.port.ChatOutboxRepository;
import com.hapbang.chat.app.port.ChatRecipientSetMemberRepository;
import com.hapbang.chat.app.port.ChatRecipientSetRepository;
import com.hapbang.chat.app.port.ChatRoomMemberRepository;
import com.hapbang.chat.app.port.ChatRoomRepository;
import com.hapbang.chat.domain.ChatMessage;
import com.hapbang.chat.domain.ChatMessageType;
import com.hapbang.chat.domain.ChatNotifyRule;
import com.hapbang.chat.domain.ChatOutbox;
import com.hapbang.chat.domain.ChatOutboxStatus;
import com.hapbang.chat.domain.ChatPublishCheckpoint;
import com.hapbang.chat.domain.ChatPublishStatus;
import com.hapbang.chat.domain.ChatRecipientSet;
import com.hapbang.chat.domain.ChatRecipientSetMember;
import com.hapbang.chat.domain.ChatRoom;
import com.hapbang.chat.fixture.ChatNativeSql;
import com.hapbang.chat.fixture.ChatUserSeeder;
import com.hapbang.chat.support.ChatMessageTestRepository;
import com.hapbang.chat.support.ChatOutboxTestRepository;
import com.hapbang.chat.support.ChatPublishCheckpointTestRepository;
import com.hapbang.chat.support.ChatRecipientSetMemberTestRepository;
import com.hapbang.chat.support.ChatRecipientSetTestRepository;
import com.hapbang.shared.appearance.dto.AppearanceRequestDto;
import com.hapbang.testsupport.IntegrationTest;

import jakarta.persistence.EntityManager;

/**
 * 메시지·시스템 안내·알림 저장과 같은 트랜잭션에 전달 outbox·발행 체크포인트·불변 수신자 집합을 남기는지 검증한다.
 */
@IntegrationTest
@DirtiesContext
class ChatDeliveryOutboxRecordTest {

    @Autowired
    private DefaultChatRoomService chatRoomService;

    @Autowired
    private DefaultChatMessageService chatMessageService;

    @Autowired
    private DefaultChatNotificationService chatNotificationService;

    @Autowired
    private ChatUserUseCase chatUserUseCase;

    @Autowired
    private ChatRoomRepository chatRoomRepository;

    @Autowired
    private ChatRoomMemberRepository chatRoomMemberRepository;

    @Autowired
    private ChatRecipientSetRepository chatRecipientSetRepository;

    @Autowired
    private ChatRecipientSetTestRepository chatRecipientSetTestRepository;

    @Autowired
    private ChatPublishCheckpointTestRepository checkpointTestRepository;

    private ChatNativeSql sql;

    @MockitoSpyBean
    private ChatOutboxRepository chatOutboxRepository;

    @Autowired
    private ChatOutboxTestRepository chatOutboxTestRepository;

    @MockitoSpyBean
    private ChatRecipientSetMemberRepository chatRecipientSetMemberRepository;

    @Autowired
    private ChatRecipientSetMemberTestRepository chatRecipientSetMemberTestRepository;

    @MockitoSpyBean
    private ChatMessageRepository chatMessageRepository;

    @Autowired
    private ChatMessageTestRepository chatMessageTestRepository;

    private Long chatRoomId;

    @Autowired
    void setUpSql(EntityManager entityManager, PlatformTransactionManager transactionManager) {
        sql = ChatNativeSql.of(entityManager, transactionManager);
    }

    @BeforeEach
    void setUp() {
        ChatUserSeeder.seedDefaultUsers(chatUserUseCase);
        chatRoomId = chatRoomService.createOrGet(HOST_ID, GUEST_ID).chatRoomId();
    }

    @Test
    void 방을_만들면_두_참여자로_첫_수신자_집합과_활성_인원을_만든다() {
        ChatRoom room = chatRoomRepository.findById(chatRoomId).orElseThrow();

        assertThat(room.getActiveMemberCount()).isEqualTo(2);
        ChatRecipientSet set = chatRecipientSetRepository.findById(room.getCurrentRecipientSetId()).orElseThrow();
        assertThat(set.getSetVersion()).isEqualTo(1);
        assertThat(set.getMemberCount()).isEqualTo(2);
        assertThat(recipientUserIds(set.getId())).containsExactly(HOST_ID, GUEST_ID);
    }

    @Test
    void 새_메시지는_현재_집합과_보낸사람_제외_알림_규칙으로_outbox와_PENDING_체크포인트를_남긴다() {
        SentChatMessage sent = chatMessageService.send(chatRoomId, HOST_ID, "안녕하세요", "c-1");

        ChatRoom room = chatRoomRepository.findById(chatRoomId).orElseThrow();
        ChatOutbox outbox = chatOutboxTestRepository.findByMessageId(sent.message().messageId()).orElseThrow();
        assertThat(outbox.getRecipientSetId()).isEqualTo(room.getCurrentRecipientSetId());
        assertThat(outbox.getNotifyRule()).isEqualTo(ChatNotifyRule.RECIPIENTS_EXCEPT_SENDER);
        assertThat(outbox.getStatus()).isEqualTo(ChatOutboxStatus.PENDING);
        assertThat(outbox.getRoomSequence()).isEqualTo(sent.message().roomSequence());
        assertThat(outbox.getChatRoomId()).isEqualTo(chatRoomId);
        ChatPublishCheckpoint checkpoint = checkpointTestRepository
                .findByConsumerGroupAndJobId(ChatJobStream.DELIVERY.consumerGroup(), outbox.getDeliveryId())
                .orElseThrow();
        assertThat(checkpoint.getStatus()).isEqualTo(ChatPublishStatus.PENDING);
        assertThat(checkpoint.getGeneration()).isZero();
    }

    @Test
    void 같은_전송_ID_재전송은_outbox를_만들지_않는다() {
        chatMessageService.send(chatRoomId, HOST_ID, "안녕하세요", "c-1");
        chatMessageService.send(chatRoomId, HOST_ID, "안녕하세요", "c-1");

        assertThat(chatOutboxTestRepository.findByChatRoomIdOrderByIdAsc(chatRoomId)).hasSize(1);
        assertThat(checkpointTestRepository.count()).isEqualTo(1);
    }

    @Test
    void 퇴장하면_새_집합을_만들고_안내는_남은_참여자_집합으로_알림_없이_보낸다() {
        Long beforeSetId = chatRoomRepository.findById(chatRoomId).orElseThrow().getCurrentRecipientSetId();

        chatRoomService.leave(chatRoomId, GUEST_ID, null);

        ChatRoom room = chatRoomRepository.findById(chatRoomId).orElseThrow();
        assertThat(room.getActiveMemberCount()).isEqualTo(1);
        assertThat(room.getCurrentRecipientSetId()).isNotEqualTo(beforeSetId);
        assertThat(recipientUserIds(room.getCurrentRecipientSetId()))
                .containsExactly(HOST_ID);
        // 이전 버전의 항목은 그대로다
        assertThat(recipientUserIds(beforeSetId)).containsExactly(HOST_ID, GUEST_ID);
        // 퇴장 안내의 전달 작업이 그 방의 마지막 outbox다
        ChatOutbox outbox = chatOutboxTestRepository.findByChatRoomIdOrderByIdAsc(chatRoomId).getLast();
        assertThat(outbox.getMessageId()).isEqualTo(lastMessageId());
        assertThat(outbox.getRecipientSetId()).isEqualTo(room.getCurrentRecipientSetId());
        assertThat(outbox.getNotifyRule()).isEqualTo(ChatNotifyRule.NONE);
    }

    @Test
    void 마지막_참여자가_나가_방이_끝나면_빈_집합만_남고_전달_작업은_만들지_않는다() {
        chatRoomService.leave(chatRoomId, GUEST_ID, null);
        chatRoomService.leave(chatRoomId, HOST_ID, null);

        ChatRoom room = chatRoomRepository.findById(chatRoomId).orElseThrow();
        assertThat(room.isEnded()).isTrue();
        assertThat(room.getActiveMemberCount()).isZero();
        assertThat(recipientUserIds(room.getCurrentRecipientSetId())).isEmpty();
        assertThat(chatOutboxTestRepository.findByChatRoomIdOrderByIdAsc(chatRoomId)).hasSize(1);
    }

    @Test
    void 재입장_안내는_재입장_전_집합으로_보내고_재입장_뒤_새_집합에_재입장한_회원이_들어간다() {
        chatRoomService.leave(chatRoomId, GUEST_ID, null);
        Long beforeRejoinSetId = chatRoomRepository.findById(chatRoomId).orElseThrow().getCurrentRecipientSetId();

        chatRoomService.createOrGet(GUEST_ID, HOST_ID);

        // 퇴장 안내 다음의 전달 작업이 재입장 안내다
        List<ChatOutbox> outboxes = chatOutboxTestRepository.findByChatRoomIdOrderByIdAsc(chatRoomId);
        assertThat(outboxes).hasSize(2);
        ChatOutbox notice = outboxes.getLast();
        assertThat(notice.getMessageId()).isEqualTo(lastMessageId());
        assertThat(notice.getRecipientSetId()).isEqualTo(beforeRejoinSetId);
        assertThat(notice.getNotifyRule()).isEqualTo(ChatNotifyRule.NONE);
        ChatRoom room = chatRoomRepository.findById(chatRoomId).orElseThrow();
        assertThat(room.getActiveMemberCount()).isEqualTo(2);
        assertThat(chatRecipientSetMemberTestRepository.findByRecipientSetIdOrderByUserIdAsc(
                room.getCurrentRecipientSetId()))
                .extracting(member -> member.getUserId() + ":" + member.getParticipationVersion())
                .containsExactly(HOST_ID + ":1", GUEST_ID + ":2");
    }

    @Test
    void 재입장한_회원은_재입장_전_메시지의_수신자_집합에_추가되지_않는다() {
        chatRoomService.leave(chatRoomId, GUEST_ID, null);
        // 상대가 없는 동안에는 보낼 수 없으므로 퇴장 안내를 이전 메시지로 본다
        Long leftNoticeSetId = chatOutboxTestRepository.findByChatRoomIdOrderByIdAsc(chatRoomId).getLast()
                .getRecipientSetId();

        chatRoomService.createOrGet(GUEST_ID, HOST_ID);
        SentChatMessage after = chatMessageService.send(chatRoomId, HOST_ID, "다시 왔네요", null);

        assertThat(recipientUserIds(leftNoticeSetId)).containsExactly(HOST_ID);
        assertThat(recipientUserIds(
                chatOutboxTestRepository.findByMessageId(after.message().messageId()).orElseThrow()
                        .getRecipientSetId()))
                .containsExactly(HOST_ID, GUEST_ID);
    }

    @Test
    void 출연_알림은_현재_집합과_보낸사람_제외_알림_규칙으로_남긴다() {
        chatNotificationService.notifyAppearanceRequestSent(new AppearanceRequestDto(100L, HOST_ID,
                GUEST_ID, "출연 요청", 1000L, NOW.plusDays(7), NOW.plusDays(3)));

        ChatOutbox outbox = chatOutboxTestRepository.findByChatRoomIdOrderByIdAsc(chatRoomId).getLast();
        assertThat(outbox.getMessageId()).isEqualTo(lastMessageId());
        assertThat(outbox.getNotifyRule()).isEqualTo(ChatNotifyRule.RECIPIENTS_EXCEPT_SENDER);
        assertThat(outbox.getRecipientSetId())
                .isEqualTo(chatRoomRepository.findById(chatRoomId).orElseThrow().getCurrentRecipientSetId());
    }

    @Test
    void outbox_저장이_실패하면_메시지도_체크포인트도_남지_않는다() {
        doThrow(new IllegalStateException("outbox 저장 실패")).when(chatOutboxRepository).save(any(ChatOutbox.class));

        assertThatThrownBy(() -> chatMessageService.send(chatRoomId, HOST_ID, "안녕하세요", null))
                .isInstanceOf(IllegalStateException.class);

        assertThat(chatMessageTestRepository.findByChatRoom_IdOrderByRoomSequenceAsc(chatRoomId)).isEmpty();
        assertThat(chatRoomRepository.findById(chatRoomId).orElseThrow().getLastSequence()).isZero();
        assertThat(checkpointTestRepository.count()).isZero();
    }

    @Test
    void 퇴장_안내_저장이_실패하면_참여_행_집합_포인터_모두_롤백된다() {
        ChatRoom before = chatRoomRepository.findById(chatRoomId).orElseThrow();
        long setCount = chatRecipientSetTestRepository.count();
        doThrow(new IllegalStateException("저장 실패")).when(chatMessageRepository)
                .save(argThat((ChatMessage message) -> message.getMessageType() == ChatMessageType.SYSTEM));

        assertThatThrownBy(() -> chatRoomService.leave(chatRoomId, GUEST_ID, null))
                .isInstanceOf(IllegalStateException.class);

        ChatRoom after = chatRoomRepository.findById(chatRoomId).orElseThrow();
        assertThat(after.getCurrentRecipientSetId()).isEqualTo(before.getCurrentRecipientSetId());
        assertThat(after.getActiveMemberCount()).isEqualTo(2);
        assertThat(chatRecipientSetTestRepository.count()).isEqualTo(setCount);
        assertThat(chatRoomMemberRepository.findByChatRoom_IdAndUserIdAndDeletedAtIsNull(chatRoomId, GUEST_ID)
                .orElseThrow().isActive()).isTrue();
    }

    @Test
    void 집합_항목_복사가_실패하면_퇴장이_롤백된다() {
        doThrow(new IllegalStateException("복사 실패")).when(chatRecipientSetMemberRepository)
                .copyActiveMembers(anyLong(), eq(chatRoomId));

        assertThatThrownBy(() -> chatRoomService.leave(chatRoomId, GUEST_ID, null))
                .isInstanceOf(IllegalStateException.class);

        assertThat(chatRoomMemberRepository.findByChatRoom_IdAndUserIdAndDeletedAtIsNull(chatRoomId, GUEST_ID)
                .orElseThrow().isActive()).isTrue();
        assertThat(chatRoomRepository.findById(chatRoomId).orElseThrow().getActiveMemberCount()).isEqualTo(2);
        assertThat(chatMessageTestRepository.findByChatRoom_IdOrderByRoomSequenceAsc(chatRoomId)).isEmpty();
    }

    @Test
    void 수신자_집합이_없는_방에는_보낼_수_없고_채팅을_다시_시작하면_집합을_만든다() {
        sql.update("update chats.chat_room set current_recipient_set_id = null where id = :id",
                Map.of("id", chatRoomId));

        assertThatThrownBy(() -> chatMessageService.send(chatRoomId, HOST_ID, "안녕하세요", null))
                .isInstanceOf(ChatException.class)
                .extracting("errorCode").isEqualTo(ChatErrorCode.CHAT_ROOM_NOT_READY);
        assertThat(chatMessageTestRepository.findByChatRoom_IdOrderByRoomSequenceAsc(chatRoomId)).isEmpty();

        chatRoomService.createOrGet(HOST_ID, GUEST_ID);

        assertThat(chatRoomRepository.findById(chatRoomId).orElseThrow().hasRecipientSet()).isTrue();
        assertThat(chatMessageService.send(chatRoomId, HOST_ID, "안녕하세요", null).message().messageId())
                .isNotNull();
    }

    @Test
    void 집합_버전은_방_안에서_1씩_늘어난다() {
        chatRoomService.leave(chatRoomId, GUEST_ID, null);
        chatRoomService.createOrGet(GUEST_ID, HOST_ID);

        List<Integer> versions = sql.list("""
                        select set_version from chats.chat_recipient_set where chat_room_id = :id order by set_version
                        """, Integer.class, Map.of("id", chatRoomId));
        assertThat(versions).containsExactly(1, 2, 3);
    }

    /** 수신자 집합에 든 회원 ID(회원 ID 순) */
    private List<Long> recipientUserIds(Long recipientSetId) {
        return chatRecipientSetMemberTestRepository.findByRecipientSetIdOrderByUserIdAsc(recipientSetId).stream()
                .map(ChatRecipientSetMember::getUserId)
                .toList();
    }

    /** 방의 마지막 순번 메시지 ID */
    private Long lastMessageId() {
        return chatMessageTestRepository.findByChatRoom_IdOrderByRoomSequenceAsc(chatRoomId).getLast().getId();
    }
}
