package com.hapbang.chat.app;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
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

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.chat.app.dto.MessageFanout;
import com.hapbang.chat.app.exception.ChatErrorCode;
import com.hapbang.chat.app.exception.ChatException;
import com.hapbang.chat.domain.ChatDeadReason;
import com.hapbang.chat.domain.ChatMessage;
import com.hapbang.chat.domain.ChatMessageType;
import com.hapbang.chat.domain.ChatNotifyRule;
import com.hapbang.chat.domain.ChatOutbox;
import com.hapbang.chat.domain.ChatPublishCheckpoint;
import com.hapbang.chat.domain.ChatPublishStatus;
import com.hapbang.chat.domain.ChatRoom;
import com.hapbang.chat.out.ChatMessageRepository;
import com.hapbang.chat.out.ChatRecipientSetMemberRepository;
import com.hapbang.chat.out.ChatRoomMemberRepository;
import com.hapbang.chat.out.ChatRoomRepository;
import com.hapbang.shared.appearance.dto.AppearanceRequestDto;

/**
 * 불변 수신자 집합의 전달 경계 (출시 전 급증·잠금 검증 "수신자 집합", 설계 4.6·9장 "잠금 축소·불변 수신자 집합").
 * 메시지가 저장 때 집합 버전을 참조하고, 그 뒤 참여 변경·Redis 유실 재구성·집합 누락에도 현재 참여자 목록으로 바꾸지 않는지,
 * 동시 참여 변경에서 참여 행·집계·집합 버전·포인터가 함께 움직이는지 검증한다.
 * 잠금 구간 계측(전체 참여자 로딩 제거 등)은 통합 단계 범위라 여기서 다루지 않는다.
 */
// 새 컨텍스트의 DB 연결 풀이 남아 테스트 DB 연결 수 상한을 넘지 않도록 클래스가 끝나면 닫는다
@DirtiesContext
class ChatRecipientSetDeliveryTest extends ChatDeliveryTestSupport {

    private static final int THREADS = 8;

    @DynamicPropertySource
    static void redisNamespace(DynamicPropertyRegistry registry) {
        uniqueNamespace(registry);
    }

    @Autowired
    private ChatNotificationUseCase chatNotificationUseCase;

    @Autowired
    private ChatRoomRepository chatRoomRepository;

    @Autowired
    private ChatRoomMemberRepository chatRoomMemberRepository;

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    @Autowired
    private ChatRecipientSetMemberRepository recipientSetMemberRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void 저장_뒤_상대가_퇴장_재입장해도_payload는_저장_때_집합의_참여_세대로_펼치고_Redis_유실_재구성_뒤에도_같다() {
        UUID deliveryId = send("퇴장 전 메시지");
        chatRoomUseCase.leave(chatRoomId, GUEST_ID, null);
        chatRoomUseCase.createOrGet(GUEST_ID, HOST_ID);
        relayAll(ChatJobStream.DELIVERY);
        // 소비 전에 Redis가 Stream을 잃었다
        redis.delete(keys.deliveryStream());
        assertThat(reconstructor.reconstruct(ChatJobStream.DELIVERY, Duration.ZERO)).isEqualTo(3);

        pollAll(ChatJobStream.DELIVERY, WORKER);

        MessageFanout payload = payloadOf(deliveryId);
        // 재입장 뒤의 집합(버전 3)이 아니라 저장 때 집합(버전 1)이다. 받는 앱이 버전으로 판단한다
        assertThat(payload.recipientSetVersion()).isEqualTo(1);
        assertThat(payload.notifyRule()).isEqualTo(ChatNotifyRule.RECIPIENTS_EXCEPT_SENDER);
    }

    @Test
    void 메시지가_참조한_집합은_지울_수_없고_집합이_없으면_현재_집합으로_대신_보내지_않고_격리한다() {
        UUID deliveryId = send("집합이 사라질 메시지");
        Long referencedSetId = outbox(deliveryId).getRecipientSetId();
        // 참여 변경으로 현재 집합은 다른 버전이 됐다
        chatRoomUseCase.leave(chatRoomId, GUEST_ID, null);
        chatRoomUseCase.createOrGet(GUEST_ID, HOST_ID);
        assertThat(chatRoomRepository.findById(chatRoomId).orElseThrow().getCurrentRecipientSetId())
                .isNotEqualTo(referencedSetId);

        // 참조 원본 보존: outbox가 가리키는 집합은 지울 수 없다
        assertThatThrownBy(() -> sql.update("delete from chats.chat_recipient_set where id = :id",
                Map.of("id", referencedSetId)))
                .isInstanceOf(DataIntegrityViolationException.class);

        // 그래도 운영 실수·복구 누락으로 집합이 사라졌다(제약 검사를 끄고 지운다)
        deleteRecipientSetIgnoringConstraints(referencedSetId);
        relayAll(ChatJobStream.DELIVERY);
        pollAll(ChatJobStream.DELIVERY, WORKER);

        ChatPublishCheckpoint dead = checkpoint(deliveryId);
        assertThat(dead.getStatus()).isEqualTo(ChatPublishStatus.DEAD);
        assertThat(dead.getDeadReason()).isEqualTo(ChatDeadReason.SOURCE_MISSING);
        assertThat(dead.getDeadStreamEntryId()).isEqualTo(outbox(deliveryId).getStreamEntryId());
        assertThat(fanout.messages()).extracting(MessageFanout::deliveryId).doesNotContain(deliveryId);
        assertThat(pendingCount(ChatJobStream.DELIVERY)).isZero();
        // 격리 작업은 Redis 유실만으로 다시 적재하지 않는다
        redis.delete(keys.deliveryStream());
        assertThat(reconstructor.reconstruct(ChatJobStream.DELIVERY, Duration.ZERO)).isZero();
    }

    /**
     * 집합 헤더는 남았지만 항목(chat_recipient_set_member)이 사라진 경우. 방 topic 결정 뒤 전달은 집합의 버전만 싣고 항목은 쓰지
     * 않는다(설계 Q21). 그래서 항목이 없어도 버전을 실어 발행하고, 받는 앱이 버전 확인 규칙으로 참여 상태를 다시 읽는다.
     */
    @Test
    void 집합_항목이_사라져도_전달은_버전만_쓰므로_발행한다() {
        UUID deliveryId = send("항목이 사라질 메시지");
        sql.update("delete from chats.chat_recipient_set_member where recipient_set_id = :id",
                Map.of("id", outbox(deliveryId).getRecipientSetId()));
        relayAll(ChatJobStream.DELIVERY);

        pollAll(ChatJobStream.DELIVERY, WORKER);

        assertThat(payloadOf(deliveryId).recipientSetVersion()).isEqualTo(1);
        assertThat(checkpoint(deliveryId).getStatus()).isEqualTo(ChatPublishStatus.PUBLISHED);
    }

    @Test
    void 나간_상대에게_출연_알림을_보내면_재입장_안내는_남아_있던_사람에게_알림은_재입장_뒤_집합에_보낸다() {
        chatRoomUseCase.leave(chatRoomId, GUEST_ID, null);
        relayAll(ChatJobStream.DELIVERY);
        pollAll(ChatJobStream.DELIVERY, WORKER);
        fanout.reset();

        var result = chatNotificationUseCase.notifyAppearanceRequestSent(new AppearanceRequestDto(100L, HOST_ID,
                GUEST_ID, "출연 요청", 1000L, NOW.plusDays(7), NOW.plusDays(3))).orElseThrow();
        relayAll(ChatJobStream.DELIVERY);
        pollAll(ChatJobStream.DELIVERY, WORKER);

        List<ChatOutbox> outboxes = chatOutboxRepository.findByChatRoomIdOrderByIdAsc(chatRoomId);
        ChatOutbox rejoinNotice = outboxes.get(outboxes.size() - 2);
        ChatOutbox notification = outboxes.getLast();
        assertThat(notification.getMessageId()).isEqualTo(result.sent().message().messageId());
        assertThat(rejoinNotice.getNotifyRule()).isEqualTo(ChatNotifyRule.NONE);
        assertThat(notification.getNotifyRule()).isEqualTo(ChatNotifyRule.RECIPIENTS_EXCEPT_SENDER);

        MessageFanout noticePayload = payloadOf(rejoinNotice.getDeliveryId());
        assertThat(noticePayload.recipientSetVersion()).isEqualTo(2);
        assertThat(noticePayload.notifyRule()).isEqualTo(ChatNotifyRule.NONE);
        MessageFanout notificationPayload = payloadOf(notification.getDeliveryId());
        assertThat(notificationPayload.recipientSetVersion()).isEqualTo(3);
        assertThat(notificationPayload.notifyRule()).isEqualTo(ChatNotifyRule.RECIPIENTS_EXCEPT_SENDER);
    }

    @Test
    void 전송과_퇴장이_동시에_와도_메시지마다_저장_순간의_집합을_참조하고_집계와_포인터가_참여_행과_맞는다() throws Exception {
        List<Object> results = runConcurrently(THREADS, index -> {
            if (index == THREADS / 2) {
                return chatRoomUseCase.leave(chatRoomId, GUEST_ID, null);
            }
            try {
                return chatMessageUseCase.send(chatRoomId, HOST_ID, "메시지" + index, null);
            } catch (ChatException e) {
                assertThat(e.getErrorCode()).isEqualTo(ChatErrorCode.CHAT_PARTNER_LEFT);
                return e.getErrorCode();
            }
        });
        assertThat(results).doesNotContainNull();

        List<ChatMessage> messages = chatMessageRepository.findByChatRoom_IdOrderByRoomSequenceAsc(chatRoomId);
        long leftNoticeSequence = messages.stream()
                .filter(message -> message.getMessageType() == ChatMessageType.SYSTEM)
                .mapToLong(ChatMessage::getRoomSequence)
                .min().orElseThrow();
        for (ChatMessage message : messages) {
            ChatOutbox outbox = chatOutboxRepository.findByMessageId(message.getId()).orElseThrow();
            List<Long> recipients = recipientSetMemberRepository.findUserIds(outbox.getRecipientSetId());
            if (message.getMessageType() == ChatMessageType.SYSTEM) {
                assertThat(recipients).containsExactly(HOST_ID);
            } else {
                // 퇴장 뒤에는 보낼 수 없고, 퇴장 전 메시지는 두 사람 모두에게 간다
                assertThat(message.getRoomSequence()).isLessThan(leftNoticeSequence);
                assertThat(recipients).containsExactly(HOST_ID, GUEST_ID);
            }
            assertThat(checkpoint(outbox.getDeliveryId()).getStatus()).isEqualTo(ChatPublishStatus.PENDING);
        }
        assertRoomAggregatesMatchParticipation();
    }

    @Test
    void 나간_상대와_동시에_여러_번_채팅을_다시_시작해도_재입장은_한_번이고_집합_버전이_겹치거나_비지_않는다() throws Exception {
        chatRoomUseCase.leave(chatRoomId, GUEST_ID, null);

        runConcurrently(THREADS, index -> index % 2 == 0
                ? chatRoomUseCase.createOrGet(GUEST_ID, HOST_ID)
                : chatRoomUseCase.createOrGet(HOST_ID, GUEST_ID));

        List<ChatMessage> systemMessages = chatMessageRepository.findByChatRoom_IdOrderByRoomSequenceAsc(chatRoomId)
                .stream().filter(message -> message.getMessageType() == ChatMessageType.SYSTEM).toList();
        // 퇴장 안내 1개, 재입장 안내 1개
        assertThat(systemMessages).hasSize(2);
        ChatOutbox rejoinNotice = chatOutboxRepository.findByMessageId(systemMessages.getLast().getId())
                .orElseThrow();
        assertThat(recipientSetMemberRepository.findUserIds(rejoinNotice.getRecipientSetId()))
                .containsExactly(HOST_ID);
        assertThat(recipientSetMemberRepository.findByRecipientSetIdOrderByUserIdAsc(
                chatRoomRepository.findById(chatRoomId).orElseThrow().getCurrentRecipientSetId()))
                .extracting(member -> member.getUserId() + ":" + member.getParticipationVersion())
                .containsExactly(HOST_ID + ":1", GUEST_ID + ":2");
        assertRoomAggregatesMatchParticipation();
    }

    /** 집합 버전은 1부터 빈틈없이 늘고, 현재 포인터는 마지막 버전이며, 집계·항목 수가 활성 참여 행 수와 같다 */
    private void assertRoomAggregatesMatchParticipation() {
        ChatRoom room = chatRoomRepository.findById(chatRoomId).orElseThrow();
        List<long[]> sets = sql.rows("""
                        select s.id, s.set_version, s.member_count,
                               (select count(*) from chats.chat_recipient_set_member m where m.recipient_set_id = s.id)
                                   as item_count
                        from chats.chat_recipient_set s
                        where s.chat_room_id = :roomId
                        order by s.set_version
                        """, Map.of("roomId", chatRoomId)).stream()
                .map(row -> new long[] {longOf(row, "id"), longOf(row, "set_version"), longOf(row, "member_count"),
                        longOf(row, "item_count")})
                .toList();
        for (int i = 0; i < sets.size(); i++) {
            assertThat(sets.get(i)[1]).isEqualTo(i + 1);
            assertThat(sets.get(i)[2]).isEqualTo(sets.get(i)[3]);
        }
        long[] current = sets.getLast();
        long activeMembers = chatRoomMemberRepository.countActiveMembers(chatRoomId);
        assertThat(room.getCurrentRecipientSetId()).isEqualTo(current[0]);
        assertThat((long) room.getActiveMemberCount()).isEqualTo(activeMembers);
        assertThat(current[2]).isEqualTo(activeMembers);
    }

    private static long longOf(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).longValue();
    }

    private MessageFanout payloadOf(UUID deliveryId) {
        return fanout.messages().stream()
                .filter(payload -> payload.deliveryId().equals(deliveryId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("발행되지 않음 deliveryId=" + deliveryId));
    }

    /** 외래 키 검사를 끄고(session_replication_role) 집합 헤더와 항목을 지운다. 테스트 DB 사용자는 superuser다 */
    private void deleteRecipientSetIgnoringConstraints(Long recipientSetId) {
        transactionTemplate.executeWithoutResult(status -> {
            sql.update("set local session_replication_role = replica");
            sql.update("delete from chats.chat_recipient_set_member where recipient_set_id = :id",
                    Map.of("id", recipientSetId));
            sql.update("delete from chats.chat_recipient_set where id = :id", Map.of("id", recipientSetId));
        });
    }

    private <T> List<T> runConcurrently(int count, IndexedTask<T> task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int index = i;
                Callable<T> callable = () -> {
                    ready.countDown();
                    start.await();
                    return task.run(index);
                };
                futures.add(executor.submit(callable));
            }
            ready.await(5, TimeUnit.SECONDS);
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
        T run(int index);
    }
}
