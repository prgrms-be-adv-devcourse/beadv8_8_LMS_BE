package com.hapbang.chat.app;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.hapbang.chat.app.dto.SentChatMessage;
import com.hapbang.chat.app.exception.ChatErrorCode;
import com.hapbang.chat.app.exception.ChatException;
import com.hapbang.chat.domain.ChatMessage;
import com.hapbang.chat.domain.ChatMessageType;
import com.hapbang.chat.domain.ChatNotifyRule;
import com.hapbang.chat.domain.ChatOutbox;
import com.hapbang.chat.domain.ChatOutboxStatus;
import com.hapbang.chat.domain.ChatPublishCheckpoint;
import com.hapbang.chat.domain.ChatPublishStatus;
import com.hapbang.chat.domain.ChatRecipientSet;
import com.hapbang.chat.domain.ChatRoom;
import com.hapbang.chat.fixture.ChatUserSeeder;
import com.hapbang.chat.in.stream.ChatDeliveryPipelineRunner;
import com.hapbang.chat.out.ChatMessageRepository;
import com.hapbang.chat.out.ChatOutboxRepository;
import com.hapbang.chat.out.ChatPublishCheckpointRepository;
import com.hapbang.chat.out.ChatRecipientSetMemberRepository;
import com.hapbang.chat.out.ChatRecipientSetRepository;
import com.hapbang.chat.out.ChatRoomMemberRepository;
import com.hapbang.chat.out.ChatRoomRepository;
import com.hapbang.shared.appearance.dto.AppearanceRequestDto;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 메시지·시스템 안내·알림 저장과 같은 트랜잭션에 전달 outbox·발행 체크포인트·불변 수신자 집합을 남기는지 검증한다.
 */
@IntegrationTest
@DirtiesContext
class ChatDeliveryOutboxRecordTest {

    @Autowired
    private ChatRoomUseCase chatRoomUseCase;

    @Autowired
    private ChatMessageUseCase chatMessageUseCase;

    @Autowired
    private ChatNotificationUseCase chatNotificationUseCase;

    @Autowired
    private ChatUserUseCase chatUserUseCase;

    @Autowired
    private ChatRoomRepository chatRoomRepository;

    @Autowired
    private ChatRoomMemberRepository chatRoomMemberRepository;

    @Autowired
    private ChatRecipientSetRepository chatRecipientSetRepository;

    @Autowired
    private ChatPublishCheckpointRepository checkpointRepository;

    @Autowired
    private JdbcClient jdbcClient;

    @MockitoSpyBean
    private ChatOutboxRepository chatOutboxRepository;

    @MockitoSpyBean
    private ChatRecipientSetMemberRepository chatRecipientSetMemberRepository;

    @MockitoSpyBean
    private ChatMessageRepository chatMessageRepository;

    @Autowired
    private ApplicationContext context;

    private Long chatRoomId;

    @BeforeEach
    void setUp() {
        ChatUserSeeder.seedDefaultUsers(chatUserUseCase);
        chatRoomId = chatRoomUseCase.createOrGet(HOST_ID, GUEST_ID).chatRoomId();
    }

    @Test
    void 방을_만들면_두_참여자로_첫_수신자_집합과_활성_인원을_만든다() {
        ChatRoom room = chatRoomRepository.findById(chatRoomId).orElseThrow();

        assertThat(room.getActiveMemberCount()).isEqualTo(2);
        ChatRecipientSet set = chatRecipientSetRepository.findById(room.getCurrentRecipientSetId()).orElseThrow();
        assertThat(set.getSetVersion()).isEqualTo(1);
        assertThat(set.getMemberCount()).isEqualTo(2);
        assertThat(chatRecipientSetMemberRepository.findUserIds(set.getId())).containsExactly(HOST_ID, GUEST_ID);
    }

    @Test
    void 새_메시지는_현재_집합과_보낸사람_제외_알림_규칙으로_outbox와_PENDING_체크포인트를_남긴다() {
        SentChatMessage sent = chatMessageUseCase.send(chatRoomId, HOST_ID, "안녕하세요", "c-1");

        ChatRoom room = chatRoomRepository.findById(chatRoomId).orElseThrow();
        ChatOutbox outbox = chatOutboxRepository.findByMessageId(sent.message().messageId()).orElseThrow();
        assertThat(outbox.getRecipientSetId()).isEqualTo(room.getCurrentRecipientSetId());
        assertThat(outbox.getNotifyRule()).isEqualTo(ChatNotifyRule.RECIPIENTS_EXCEPT_SENDER);
        assertThat(outbox.getStatus()).isEqualTo(ChatOutboxStatus.PENDING);
        assertThat(outbox.getRoomSequence()).isEqualTo(sent.message().roomSequence());
        assertThat(outbox.getChatRoomId()).isEqualTo(chatRoomId);
        ChatPublishCheckpoint checkpoint = checkpointRepository
                .findByConsumerGroupAndJobId(ChatJobStream.DELIVERY.consumerGroup(), outbox.getDeliveryId())
                .orElseThrow();
        assertThat(checkpoint.getStatus()).isEqualTo(ChatPublishStatus.PENDING);
        assertThat(checkpoint.getGeneration()).isZero();
    }

    @Test
    void 같은_전송_ID_재전송은_outbox를_만들지_않는다() {
        chatMessageUseCase.send(chatRoomId, HOST_ID, "안녕하세요", "c-1");
        chatMessageUseCase.send(chatRoomId, HOST_ID, "안녕하세요", "c-1");

        assertThat(chatOutboxRepository.findByChatRoomIdOrderByIdAsc(chatRoomId)).hasSize(1);
        assertThat(checkpointRepository.count()).isEqualTo(1);
    }

    @Test
    void 퇴장하면_새_집합을_만들고_안내는_남은_참여자_집합으로_알림_없이_보낸다() {
        Long beforeSetId = chatRoomRepository.findById(chatRoomId).orElseThrow().getCurrentRecipientSetId();

        var result = chatRoomUseCase.leave(chatRoomId, GUEST_ID, null);

        ChatRoom room = chatRoomRepository.findById(chatRoomId).orElseThrow();
        assertThat(room.getActiveMemberCount()).isEqualTo(1);
        assertThat(room.getCurrentRecipientSetId()).isNotEqualTo(beforeSetId);
        assertThat(chatRecipientSetMemberRepository.findUserIds(room.getCurrentRecipientSetId()))
                .containsExactly(HOST_ID);
        // 이전 버전의 항목은 그대로다
        assertThat(chatRecipientSetMemberRepository.findUserIds(beforeSetId)).containsExactly(HOST_ID, GUEST_ID);
        ChatOutbox outbox = chatOutboxRepository.findByMessageId(result.leftNotice().messageId()).orElseThrow();
        assertThat(outbox.getRecipientSetId()).isEqualTo(room.getCurrentRecipientSetId());
        assertThat(outbox.getNotifyRule()).isEqualTo(ChatNotifyRule.NONE);
    }

    @Test
    void 마지막_참여자가_나가_방이_끝나면_빈_집합만_남고_전달_작업은_만들지_않는다() {
        chatRoomUseCase.leave(chatRoomId, GUEST_ID, null);
        chatRoomUseCase.leave(chatRoomId, HOST_ID, null);

        ChatRoom room = chatRoomRepository.findById(chatRoomId).orElseThrow();
        assertThat(room.isEnded()).isTrue();
        assertThat(room.getActiveMemberCount()).isZero();
        assertThat(chatRecipientSetMemberRepository.findUserIds(room.getCurrentRecipientSetId())).isEmpty();
        assertThat(chatOutboxRepository.findByChatRoomIdOrderByIdAsc(chatRoomId)).hasSize(1);
    }

    @Test
    void 재입장_안내는_재입장_전_집합으로_보내고_재입장_뒤_새_집합에_재입장한_회원이_들어간다() {
        chatRoomUseCase.leave(chatRoomId, GUEST_ID, null);
        Long beforeRejoinSetId = chatRoomRepository.findById(chatRoomId).orElseThrow().getCurrentRecipientSetId();

        var result = chatRoomUseCase.createOrGet(GUEST_ID, HOST_ID);

        ChatOutbox notice = chatOutboxRepository.findByMessageId(result.rejoinNotices().getFirst().messageId())
                .orElseThrow();
        assertThat(notice.getRecipientSetId()).isEqualTo(beforeRejoinSetId);
        assertThat(notice.getNotifyRule()).isEqualTo(ChatNotifyRule.NONE);
        ChatRoom room = chatRoomRepository.findById(chatRoomId).orElseThrow();
        assertThat(room.getActiveMemberCount()).isEqualTo(2);
        assertThat(chatRecipientSetMemberRepository.findByRecipientSetIdOrderByUserIdAsc(
                room.getCurrentRecipientSetId()))
                .extracting(member -> member.getUserId() + ":" + member.getParticipationVersion())
                .containsExactly(HOST_ID + ":1", GUEST_ID + ":2");
    }

    @Test
    void 재입장한_회원은_재입장_전_메시지의_수신자_집합에_추가되지_않는다() {
        chatRoomUseCase.leave(chatRoomId, GUEST_ID, null);
        // 상대가 없는 동안에는 보낼 수 없으므로 퇴장 안내를 이전 메시지로 본다
        Long leftNoticeSetId = chatOutboxRepository.findByChatRoomIdOrderByIdAsc(chatRoomId).getLast()
                .getRecipientSetId();

        chatRoomUseCase.createOrGet(GUEST_ID, HOST_ID);
        SentChatMessage after = chatMessageUseCase.send(chatRoomId, HOST_ID, "다시 왔네요", null);

        assertThat(chatRecipientSetMemberRepository.findUserIds(leftNoticeSetId)).containsExactly(HOST_ID);
        assertThat(chatRecipientSetMemberRepository.findUserIds(
                chatOutboxRepository.findByMessageId(after.message().messageId()).orElseThrow().getRecipientSetId()))
                .containsExactly(HOST_ID, GUEST_ID);
    }

    @Test
    void 출연_알림은_현재_집합과_보낸사람_제외_알림_규칙으로_남긴다() {
        var result = chatNotificationUseCase.notifyAppearanceRequestSent(new AppearanceRequestDto(100L, HOST_ID,
                GUEST_ID, "출연 요청", 1000L, NOW.plusDays(7), NOW.plusDays(3))).orElseThrow();

        ChatOutbox outbox = chatOutboxRepository.findByMessageId(result.message().messageId()).orElseThrow();
        assertThat(outbox.getNotifyRule()).isEqualTo(ChatNotifyRule.RECIPIENTS_EXCEPT_SENDER);
        assertThat(outbox.getRecipientSetId())
                .isEqualTo(chatRoomRepository.findById(chatRoomId).orElseThrow().getCurrentRecipientSetId());
    }

    @Test
    void outbox_저장이_실패하면_메시지도_체크포인트도_남지_않는다() {
        doThrow(new IllegalStateException("outbox 저장 실패")).when(chatOutboxRepository).save(any(ChatOutbox.class));

        assertThatThrownBy(() -> chatMessageUseCase.send(chatRoomId, HOST_ID, "안녕하세요", null))
                .isInstanceOf(IllegalStateException.class);

        assertThat(chatMessageRepository.findByChatRoom_IdOrderByRoomSequenceAsc(chatRoomId)).isEmpty();
        assertThat(chatRoomRepository.findById(chatRoomId).orElseThrow().getLastSequence()).isZero();
        assertThat(checkpointRepository.count()).isZero();
    }

    @Test
    void 퇴장_안내_저장이_실패하면_참여_행_집합_포인터_모두_롤백된다() {
        ChatRoom before = chatRoomRepository.findById(chatRoomId).orElseThrow();
        long setCount = chatRecipientSetRepository.count();
        doThrow(new IllegalStateException("저장 실패")).when(chatMessageRepository)
                .save(argThat((ChatMessage message) -> message.getMessageType() == ChatMessageType.SYSTEM));

        assertThatThrownBy(() -> chatRoomUseCase.leave(chatRoomId, GUEST_ID, null))
                .isInstanceOf(IllegalStateException.class);

        ChatRoom after = chatRoomRepository.findById(chatRoomId).orElseThrow();
        assertThat(after.getCurrentRecipientSetId()).isEqualTo(before.getCurrentRecipientSetId());
        assertThat(after.getActiveMemberCount()).isEqualTo(2);
        assertThat(chatRecipientSetRepository.count()).isEqualTo(setCount);
        assertThat(chatRoomMemberRepository.findByChatRoom_IdAndUserIdAndDeletedAtIsNull(chatRoomId, GUEST_ID)
                .orElseThrow().isActive()).isTrue();
    }

    @Test
    void 집합_항목_복사가_실패하면_퇴장이_롤백된다() {
        doThrow(new IllegalStateException("복사 실패")).when(chatRecipientSetMemberRepository)
                .copyActiveMembers(anyLong(), eq(chatRoomId));

        assertThatThrownBy(() -> chatRoomUseCase.leave(chatRoomId, GUEST_ID, null))
                .isInstanceOf(IllegalStateException.class);

        assertThat(chatRoomMemberRepository.findByChatRoom_IdAndUserIdAndDeletedAtIsNull(chatRoomId, GUEST_ID)
                .orElseThrow().isActive()).isTrue();
        assertThat(chatRoomRepository.findById(chatRoomId).orElseThrow().getActiveMemberCount()).isEqualTo(2);
        assertThat(chatMessageRepository.findByChatRoom_IdOrderByRoomSequenceAsc(chatRoomId)).isEmpty();
    }

    @Test
    void 수신자_집합이_없는_방에는_보낼_수_없고_채팅을_다시_시작하면_집합을_만든다() {
        jdbcClient.sql("update chats.chat_room set current_recipient_set_id = null where id = :id")
                .param("id", chatRoomId)
                .update();

        assertThatThrownBy(() -> chatMessageUseCase.send(chatRoomId, HOST_ID, "안녕하세요", null))
                .isInstanceOf(ChatException.class)
                .extracting("errorCode").isEqualTo(ChatErrorCode.CHAT_ROOM_NOT_READY);
        assertThat(chatMessageRepository.findByChatRoom_IdOrderByRoomSequenceAsc(chatRoomId)).isEmpty();

        chatRoomUseCase.createOrGet(HOST_ID, GUEST_ID);

        assertThat(chatRoomRepository.findById(chatRoomId).orElseThrow().hasRecipientSet()).isTrue();
        assertThat(chatMessageUseCase.send(chatRoomId, HOST_ID, "안녕하세요", null).message().messageId())
                .isNotNull();
    }

    @Test
    void 집합_버전은_방_안에서_1씩_늘어난다() {
        chatRoomUseCase.leave(chatRoomId, GUEST_ID, null);
        chatRoomUseCase.createOrGet(GUEST_ID, HOST_ID);

        List<Integer> versions = jdbcClient.sql("""
                        select set_version from chats.chat_recipient_set where chat_room_id = :id order by set_version
                        """)
                .param("id", chatRoomId)
                .query(Integer.class)
                .list();
        assertThat(versions).containsExactly(1, 2, 3);
    }

    @Test
    void 파이프라인을_켜지_않으면_relay_큐_실행기_빈을_만들지_않는다() {
        assertThat(context.getBeanNamesForType(ChatOutboxRelay.class)).isEmpty();
        assertThat(context.getBeanNamesForType(ChatStreamJobProcessor.class)).isEmpty();
        assertThat(context.getBeanNamesForType(ChatDeliveryQueue.class)).isEmpty();
        assertThat(context.getBeanNamesForType(ChatDeliveryPipelineRunner.class)).isEmpty();
    }
}
