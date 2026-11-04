package com.hapbang.chat.app;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.hapbang.chat.app.ChatRecipientStateReader.RoomMembership;
import com.hapbang.chat.app.LocalChatSender.RoomTarget;
import com.hapbang.chat.app.LocalChatSender.SubscriptionSignal;
import com.hapbang.chat.app.LocalRoomSubscriptions.Subscription;
import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.app.dto.ChatRecipientState;
import com.hapbang.chat.app.dto.ChatResyncReason;
import com.hapbang.chat.app.dto.ChatSubscriptionReason;
import com.hapbang.chat.app.dto.MessageFanout;
import com.hapbang.chat.app.dto.RoomActivityFanout;
import com.hapbang.chat.domain.ChatMessageType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 방 메시지를 로컬 방 topic 구독에 내보낸다(설계 4.2 버전 확인 규칙).
 * <ol>
 *     <li>그 방의 로컬 구독이 없으면 DB를 읽지 않는다.</li>
 *     <li>메시지가 가리킨 수신자 집합 버전 이상을 반영한 방 참여 상태를 캐시에서 얻는다(필요하면 한 번 다시 읽는다). 그래서 퇴장
 *     커밋 뒤 저장된 메시지는 그 퇴장을 본 상태로 판단한다.</li>
 *     <li>구독 세대와 현재 세대가 같고, 활성 참여이고, 순번이 가시 범위 안이고, 채팅할 수 있는 회원의 구독에만 쓴다. 맞지 않는 구독이
 *     있으면 회수하기 전에 DB를 한 번 다시 읽는다(캐시가 재입장 전에 읽혀 새 세대 구독을 옛 상태로 보는 일을 막는다). 다시 읽어도
 *     나갔거나 더 새 세대가 있는 구독만 회수하고 회수 프레임을 쓴다. 조회에 실패하면 아무에게도 쓰지 않는다.</li>
 * </ol>
 * 보낸 사람 회원의 연결에 쓰는 방 topic 프레임에만 전송 ID를 붙인다. 전송 결과 확인({@link #confirmToSender})은 늘 구독하는 서버
 * 채널의 알림 재료로 보내므로, 보낸 사람이 그 방을 구독하지 않았어도 간다. 회원이 보낸 일반 메시지면 전송 ID가 없어도 보낸다.
 * 판단 뒤 이미 전송 중인 프레임의 회수는 보장하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultChatFanoutDeliveryService implements ChatFanoutDeliveryService {

    private final LocalChatConnections localChatConnections;
    private final LocalRoomSubscriptions localRoomSubscriptions;
    private final ChatRoomMembershipCache chatRoomMembershipCache;
    private final LocalChatSender localChatSender;

    @Override
    public void deliver(MessageFanout fanout) {
        if (!isDeliverable(fanout)) {
            log.warn("처리할 수 없는 실시간 전달 작업을 버림 deliveryId={}, type={}", fanout.deliveryId(), fanout.type());
            return;
        }
        Long roomId = fanout.roomId();
        ChatMessageResponse message = fanout.message();
        Long senderId = message.senderUserId();
        List<Subscription> subscriptions = localRoomSubscriptions.subscriptions(roomId);
        if (subscriptions.isEmpty()) {
            return;
        }

        RoomMembership cached;
        try {
            cached = chatRoomMembershipCache.get(roomId, fanout.recipientSetVersion());
        } catch (RuntimeException exception) {
            // 확인하지 못한 구독을 자격 있음으로 보지 않는다. 받지 못한 회원은 이력 대조로 복구한다
            log.error("실시간 전달 직전 참여 상태 조회 실패로 버림 deliveryId={}, roomId={}, messageId={}",
                    fanout.deliveryId(), roomId, message.messageId(), exception);
            resyncRoomSubscribers(roomId, ChatResyncReason.DELIVERY_DROPPED);
            return;
        }
        RoomMembership membership = cached;
        if (subscriptions.stream().anyMatch(subscription -> disagrees(cached, subscription))) {
            // 캐시가 구독보다 먼저 읽혔을 수 있다(재입장 뒤 새 세대로 등록한 구독을 재입장 전 상태로 보는 경우). 회수하기 전에 지금 DB를
            // 다시 읽는다. 다시 읽은 상태는 이 구독들의 등록(그때 DB 확인) 뒤에 읽은 것이다
            try {
                membership = chatRoomMembershipCache.refresh(roomId);
            } catch (RuntimeException exception) {
                log.error("구독 회수 직전 참여 상태 조회 실패로 버림 deliveryId={}, roomId={}", fanout.deliveryId(), roomId,
                        exception);
                resyncRoomSubscribers(roomId, ChatResyncReason.DELIVERY_DROPPED);
                return;
            }
        }

        ChatMessageResponse publicMessage = message.clientMessageId() == null ? message
                : message.withClientMessageId(null);
        long roomSequence = message.roomSequence();
        List<RoomTarget> publicTargets = new ArrayList<>();
        List<RoomTarget> senderTargets = new ArrayList<>();
        for (Subscription subscription : subscriptions) {
            ChatRecipientState state = membership.members().get(subscription.userId());
            if (disagrees(membership, subscription)) {
                if (state == null || !state.activeParticipant()
                        || state.participationVersion() > subscription.participationVersion()) {
                    revoke(subscription, state);
                }
                // 다시 읽은 DB보다 구독의 세대가 새로우면(일어나지 않는다) 지우지도 쓰지도 않는다
                continue;
            }
            if (!state.canChat() || roomSequence < state.visibleFromSequence()) {
                // 자격 상실은 회원 상태 재검사가 연결을 닫는다. 가시 범위 밖은 이 메시지만 건너뛴다
                continue;
            }
            RoomTarget target = new RoomTarget(subscription.sessionId(), subscription.subscriptionId(),
                    subscription.participationVersion());
            (subscription.userId().equals(senderId) && fanout.senderClientMessageId() != null
                    ? senderTargets : publicTargets).add(target);
        }
        if (!publicTargets.isEmpty()) {
            localChatSender.sendRoomMessage(roomId, publicMessage, publicTargets);
        }
        if (!senderTargets.isEmpty()) {
            localChatSender.sendRoomMessage(roomId, publicMessage.withClientMessageId(fanout.senderClientMessageId()),
                    senderTargets);
        }
    }

    @Override
    public void confirmToSender(RoomActivityFanout activity) {
        Long senderId = activity.senderUserId();
        ChatMessageResponse message = activity.lastMessage();
        // 결과 확인은 회원이 보낸 일반 메시지에만 보낸다(시스템 안내·알림 제외). 전송 ID는 선택이라 없으면 null로 보낸다
        if (senderId == null || message == null || message.messageType() != ChatMessageType.TEXT
                || localChatConnections.sessionIdsOf(senderId).isEmpty()) {
            return;
        }
        RoomMembership membership;
        try {
            membership = chatRoomMembershipCache.get(activity.roomId(), activity.recipientSetVersion());
        } catch (RuntimeException exception) {
            // 결과 확인을 못 받으면 FE가 이력에서 전송 ID를 찾는다
            log.warn("전송 결과 확인 직전 참여 상태 조회 실패로 버림 deliveryId={}, roomId={}", activity.deliveryId(),
                    activity.roomId(), exception);
            return;
        }
        ChatRecipientState sender = membership.members().get(senderId);
        if (sender != null && sender.canReceive(sender.participationVersion(), message.roomSequence())) {
            localChatSender.sendMessage(senderId, message.withClientMessageId(activity.senderClientMessageId()),
                    sender.participationVersion());
        }
    }

    @Override
    public void resyncAll(ChatResyncReason reason) {
        LocalDateTime occurredAt = LocalDateTime.now();
        Set<Long> userIds = localChatConnections.connectedUserIds();
        for (Long userId : userIds) {
            localChatSender.sendResync(userId, reason, occurredAt);
        }
        log.info("로컬 연결에 재동기화 신호 보냄 reason={}, users={}", reason, userIds.size());
    }

    @Override
    public void resyncRoomSubscribers(Long roomId, ChatResyncReason reason) {
        Set<Long> userIds = new HashSet<>();
        for (Subscription subscription : localRoomSubscriptions.subscriptions(roomId)) {
            userIds.add(subscription.userId());
        }
        LocalDateTime occurredAt = LocalDateTime.now();
        for (Long userId : userIds) {
            localChatSender.sendResync(userId, reason, occurredAt);
        }
        log.warn("방 구독 회원에게 재동기화 신호 보냄 roomId={}, reason={}, users={}", roomId, reason, userIds.size());
    }

    /** 구독의 세대와 방 참여 상태가 맞지 않는다(나갔거나 세대가 다르다) */
    private static boolean disagrees(RoomMembership membership, Subscription subscription) {
        ChatRecipientState state = membership.members().get(subscription.userId());
        return state == null || !state.activeParticipant()
                || state.participationVersion() != subscription.participationVersion();
    }

    /** 나갔거나 세대가 바뀐 구독을 회수한다. 이미 다른 처리가 지웠으면 프레임을 다시 쓰지 않는다 */
    private void revoke(Subscription subscription, ChatRecipientState state) {
        if (!localRoomSubscriptions.remove(subscription)) {
            return;
        }
        boolean stale = state != null && state.activeParticipant();
        ChatSubscriptionReason reason = stale ? ChatSubscriptionReason.STALE_PARTICIPATION
                : ChatSubscriptionReason.LEFT;
        localChatSender.sendSubscriptionSignal(subscription.roomId(),
                new RoomTarget(subscription.sessionId(), subscription.subscriptionId(),
                        subscription.participationVersion()),
                SubscriptionSignal.SUBSCRIPTION_REVOKED, reason, stale ? state.participationVersion() : null);
        log.info("방 topic 구독 회수 roomId={}, userId={}, sessionId={}, reason={}", subscription.roomId(),
                subscription.userId(), subscription.sessionId(), reason);
    }

    private static boolean isDeliverable(MessageFanout fanout) {
        return fanout.type() == MessageFanout.Type.MESSAGE && fanout.roomId() != null && fanout.message() != null
                && Objects.equals(fanout.roomId(), fanout.message().chatRoomId());
    }
}
